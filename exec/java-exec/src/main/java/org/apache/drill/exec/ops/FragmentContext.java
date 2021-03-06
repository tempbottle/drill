/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.drill.exec.ops;

import io.netty.buffer.DrillBuf;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import net.hydromatic.optiq.SchemaPlus;
import net.hydromatic.optiq.jdbc.SimpleOptiqSchema;

import org.apache.drill.common.DeferredException;
import org.apache.drill.common.config.DrillConfig;
import org.apache.drill.common.exceptions.ExecutionSetupException;
import org.apache.drill.exec.exception.ClassTransformationException;
import org.apache.drill.exec.expr.ClassGenerator;
import org.apache.drill.exec.expr.CodeGenerator;
import org.apache.drill.exec.expr.fn.FunctionImplementationRegistry;
import org.apache.drill.exec.memory.BufferAllocator;
import org.apache.drill.exec.memory.OutOfMemoryException;
import org.apache.drill.exec.proto.BitControl.PlanFragment;
import org.apache.drill.exec.proto.CoordinationProtos.DrillbitEndpoint;
import org.apache.drill.exec.proto.ExecProtos.FragmentHandle;
import org.apache.drill.exec.rpc.control.ControlTunnel;
import org.apache.drill.exec.rpc.data.DataTunnel;
import org.apache.drill.exec.rpc.user.UserServer.UserClientConnection;
import org.apache.drill.exec.server.DrillbitContext;
import org.apache.drill.exec.server.options.FragmentOptionManager;
import org.apache.drill.exec.server.options.OptionList;
import org.apache.drill.exec.server.options.OptionManager;
import org.apache.drill.exec.work.batch.IncomingBuffers;

import com.google.common.collect.Maps;

/**
 * Contextual objects required for execution of a particular fragment.
 */
public class FragmentContext implements AutoCloseable, UdfUtilities {
  private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(FragmentContext.class);

  private final Map<DrillbitEndpoint, DataTunnel> tunnels = Maps.newHashMap();
  private final DrillbitContext context;
  private final UserClientConnection connection;
  private final FragmentStats stats;
  private final FunctionImplementationRegistry funcRegistry;
  private final BufferAllocator allocator;
  private final PlanFragment fragment;
  private final QueryDateTimeInfo queryDateTimeInfo;
  private IncomingBuffers buffers;
  private final OptionManager fragmentOptions;
  private final BufferManager bufferManager;

  private final DeferredException deferredException = new DeferredException();
  private volatile FragmentContextState state = FragmentContextState.OK;

  /*
   * TODO we need a state that indicates that cancellation has been requested and
   * is in progress. Early termination (such as from limit queries) could also use
   * this, as the cleanup steps should be exactly the same.
   */
  private static enum FragmentContextState {
    OK,
    FAILED,
    CANCELED
  }

  public FragmentContext(final DrillbitContext dbContext, final PlanFragment fragment,
      final UserClientConnection connection, final FunctionImplementationRegistry funcRegistry)
    throws ExecutionSetupException {
    this.context = dbContext;
    this.connection = connection;
    this.fragment = fragment;
    this.funcRegistry = funcRegistry;
    queryDateTimeInfo = new QueryDateTimeInfo(fragment.getQueryStartTime(), fragment.getTimeZone());

    logger.debug("Getting initial memory allocation of {}", fragment.getMemInitial());
    logger.debug("Fragment max allocation: {}", fragment.getMemMax());

    try {
      OptionList list;
      if (!fragment.hasOptionsJson() || fragment.getOptionsJson().isEmpty()) {
        list = new OptionList();
      } else {
        list = dbContext.getConfig().getMapper().readValue(fragment.getOptionsJson(), OptionList.class);
      }
      fragmentOptions = new FragmentOptionManager(context.getOptionManager(), list);
    } catch (Exception e) {
      throw new ExecutionSetupException("Failure while reading plan options.", e);
    }

    // Add the fragment context to the root allocator.
    // The QueryManager will call the root allocator to recalculate all the memory limits for all the fragments
    try {
      allocator = context.getAllocator().getChildAllocator(
          this, fragment.getMemInitial(), fragment.getMemMax(), true);
      assert (allocator != null);
    } catch(Throwable e) {
      throw new ExecutionSetupException("Failure while getting memory allocator for fragment.", e);
    }

    stats = new FragmentStats(allocator, dbContext.getMetrics(), fragment.getAssignment());
    bufferManager = new BufferManager(this.allocator, this);
  }

  public OptionManager getOptions() {
    return fragmentOptions;
  }

  public void setBuffers(IncomingBuffers buffers) {
    this.buffers = buffers;
  }

  public void fail(Throwable cause) {
    logger.error("Fragment Context received failure.", cause);
    setState(FragmentContextState.FAILED);
    deferredException.addThrowable(cause);
  }

  public void cancel() {
    setState(FragmentContextState.CANCELED);
  }

  /**
   * Allowed transitions from left to right: OK -> FAILED -> CANCELED
   * @param newState
   */
  private synchronized void setState(FragmentContextState newState) {
    if (state == FragmentContextState.OK) {
      state = newState;
    } else if (newState == FragmentContextState.CANCELED) {
      state = newState;
    }
  }

  public DrillbitContext getDrillbitContext() {
    return context;
  }

  public SchemaPlus getRootSchema() {
    if (connection == null) {
      fail(new UnsupportedOperationException("Schema tree can only be created in root fragment. " +
          "This is a non-root fragment."));
      return null;
    }

    final SchemaPlus root = SimpleOptiqSchema.createRootSchema(false);
    context.getStorage().getSchemaFactory().registerSchemas(connection.getSession(), root);
    return root;
  }

  /**
   * Get this node's identity.
   * @return A DrillbitEndpoint object.
   */
  public DrillbitEndpoint getIdentity() {
    return context.getEndpoint();
  }

  public FragmentStats getStats() {
    return stats;
  }

  @Override
  public QueryDateTimeInfo getQueryDateTimeInfo(){
    return this.queryDateTimeInfo;
  }

  public DrillbitEndpoint getForemanEndpoint() {
    return fragment.getForeman();
  }

  /**
   * The FragmentHandle for this Fragment
   * @return FragmentHandle
   */
  public FragmentHandle getHandle() {
    return fragment.getHandle();
  }

  private String getFragIdString() {
    final FragmentHandle handle = getHandle();
    final String frag = handle != null ? handle.getMajorFragmentId() + ":" + handle.getMinorFragmentId() : "0:0";
    return frag;
  }

  /**
   * Get this fragment's allocator.
   * @return the allocator
   */
  @Deprecated
  public BufferAllocator getAllocator() {
    if (allocator == null) {
      logger.debug("Fragment: " + getFragIdString() + " Allocator is NULL");
    }
    return allocator;
  }

  public BufferAllocator getNewChildAllocator(final long initialReservation,
                                              final long maximumReservation,
                                              final boolean applyFragmentLimit) throws OutOfMemoryException {
    return allocator.getChildAllocator(this, initialReservation, maximumReservation, applyFragmentLimit);
  }

  public <T> T getImplementationClass(final ClassGenerator<T> cg)
      throws ClassTransformationException, IOException {
    return getImplementationClass(cg.getCodeGenerator());
  }

  public <T> T getImplementationClass(final CodeGenerator<T> cg)
      throws ClassTransformationException, IOException {
    return context.getCompiler().getImplementationClass(cg);
  }

  public <T> List<T> getImplementationClass(ClassGenerator<T> cg, int instanceCount) throws ClassTransformationException, IOException {
    return getImplementationClass(cg.getCodeGenerator(), instanceCount);
  }

  public <T> List<T> getImplementationClass(CodeGenerator<T> cg, int instanceCount) throws ClassTransformationException, IOException {
    return context.getCompiler().getImplementationClass(cg, instanceCount);
  }

  /**
   * Get the user connection associated with this fragment.  This return null unless this is a root fragment.
   * @return The RPC connection to the query submitter.
   */
  public UserClientConnection getConnection() {
    return connection;
  }

  public ControlTunnel getControlTunnel(final DrillbitEndpoint endpoint) {
    return context.getController().getTunnel(endpoint);
  }

  public DataTunnel getDataTunnel(DrillbitEndpoint endpoint) {
    DataTunnel tunnel = tunnels.get(endpoint);
    if (tunnel == null) {
      tunnel = context.getDataConnectionsPool().getTunnel(endpoint);
      tunnels.put(endpoint, tunnel);
    }
    return tunnel;
  }

  public IncomingBuffers getBuffers() {
    return buffers;
  }

  public Throwable getFailureCause() {
    return deferredException.getException();
  }

  public boolean isFailed() {
    return state == FragmentContextState.FAILED;
  }

  public boolean isCancelled() {
    return state == FragmentContextState.CANCELED;
  }

  public FunctionImplementationRegistry getFunctionRegistry() {
    return funcRegistry;
  }

  public DrillConfig getConfig() {
    return context.getConfig();
  }

  public void setFragmentLimit(final long limit) {
    allocator.setFragmentLimit(limit);
  }

  public DeferredException getDeferredException() {
    return deferredException;
  }

  @Override
  public void close() throws Exception {
    /*
     * TODO wait for threads working on this Fragment to terminate (or at least stop working
     * on this Fragment's query)
     */
    deferredException.suppressingClose(bufferManager);
    deferredException.suppressingClose(buffers);
    deferredException.suppressingClose(allocator);

    deferredException.close(); // must be last, as this may throw
  }

  public DrillBuf replace(DrillBuf old, int newSize) {
    return bufferManager.replace(old, newSize);
  }

  @Override
  public DrillBuf getManagedBuffer() {
    return bufferManager.getManagedBuffer();
  }

  public DrillBuf getManagedBuffer(int size) {
    return bufferManager.getManagedBuffer(size);
  }
}
