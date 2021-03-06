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

package org.apache.drill.exec.planner.physical;

import java.util.Iterator;
import java.util.List;

import org.apache.drill.common.expression.FieldReference;
import org.apache.drill.common.logical.data.JoinCondition;
import org.apache.drill.exec.planner.common.DrillJoinRelBase;
import org.apache.drill.exec.planner.physical.visitor.PrelVisitor;
import org.eigenbase.rel.InvalidRelException;
import org.eigenbase.rel.JoinRelType;
import org.eigenbase.rel.RelNode;
import org.eigenbase.relopt.RelOptCluster;
import org.eigenbase.relopt.RelOptUtil;
import org.eigenbase.relopt.RelTraitSet;
import org.eigenbase.reltype.RelDataType;
import org.eigenbase.reltype.RelDataTypeField;
import org.eigenbase.rex.RexNode;
import org.eigenbase.rex.RexUtil;
import org.eigenbase.sql.SqlKind;
import org.eigenbase.util.Pair;

import com.google.common.collect.Lists;

/**
 *
 * Base class for MergeJoinPrel and HashJoinPrel
 *
 */
public abstract class JoinPrel extends DrillJoinRelBase implements Prel{

  public JoinPrel(RelOptCluster cluster, RelTraitSet traits, RelNode left, RelNode right, RexNode condition,
      JoinRelType joinType) throws InvalidRelException{
    super(cluster, traits, left, right, condition, joinType);
  }

  @Override
  public <T, X, E extends Throwable> T accept(PrelVisitor<T, X, E> logicalVisitor, X value) throws E {
    return logicalVisitor.visitJoin(this, value);
  }

  @Override
  public Iterator<Prel> iterator() {
    return PrelUtil.iter(getLeft(), getRight());
  }

  /**
   * Check to make sure that the fields of the inputs are the same as the output field names.  If not, insert a project renaming them.
   * @param implementor
   * @param i
   * @param offset
   * @param input
   * @return
   */
  public RelNode getJoinInput(int offset, RelNode input) {
    assert uniqueFieldNames(input.getRowType());
    final List<String> fields = getRowType().getFieldNames();
    final List<String> inputFields = input.getRowType().getFieldNames();
    final List<String> outputFields = fields.subList(offset, offset + inputFields.size());
    if (!outputFields.equals(inputFields)) {
      // Ensure that input field names are the same as output field names.
      // If there are duplicate field names on left and right, fields will get
      // lost.
      // In such case, we need insert a rename Project on top of the input.
      return rename(input, input.getRowType().getFieldList(), outputFields);
    } else {
      return input;
    }
  }

  private RelNode rename(RelNode input, List<RelDataTypeField> inputFields, List<String> outputFieldNames) {
    List<RexNode> exprs = Lists.newArrayList();

    for (RelDataTypeField field : inputFields) {
      RexNode expr = input.getCluster().getRexBuilder().makeInputRef(field.getType(), field.getIndex());
      exprs.add(expr);
    }

    RelDataType rowType = RexUtil.createStructType(input.getCluster().getTypeFactory(), exprs, outputFieldNames);

    ProjectPrel proj = new ProjectPrel(input.getCluster(), input.getTraitSet(), input, exprs, rowType);

    return proj;
  }

  @Override
  public boolean needsFinalColumnReordering() {
    return true;
  }

  /**
   * Build the list of join conditions for this join.
   * A join condition is built only for equality and IS NOT DISTINCT FROM comparisons. The difference is:
   * null == null is FALSE whereas null IS NOT DISTINCT FROM null is TRUE
   * For a use case of the IS NOT DISTINCT FROM comparison, see
   * {@link org.eigenbase.rel.rules.RemoveDistinctAggregateRule}
   * @param conditions populated list of join conditions
   * @param leftFields join fields from the left input
   * @param rightFields join fields from the right input
   */
  protected void buildJoinConditions(List<JoinCondition> conditions,
      List<String> leftFields,
      List<String> rightFields,
      List<Integer> leftKeys,
      List<Integer> rightKeys) {
    List<RexNode> conjuncts = RelOptUtil.conjunctions(this.getCondition());
    short i=0;

    RexNode comp1 = null, comp2 = null;
    for (Pair<Integer, Integer> pair : Pair.zip(leftKeys, rightKeys)) {
      if (comp1 == null) {
        comp1 = conjuncts.get(i++);
        if ( ! (comp1.getKind() == SqlKind.EQUALS || comp1.getKind() == SqlKind.IS_NOT_DISTINCT_FROM)) {
          throw new IllegalArgumentException("This type of join only supports '=' and 'is not distinct from' comparators.");
        }
      } else {
        comp2 = conjuncts.get(i++);
        if (comp1.getKind() != comp2.getKind()) {
          // it does not seem necessary at this time to support join conditions which have mixed comparators - e.g
          // 'a1 = a2 AND b1 IS NOT DISTINCT FROM b2'
          String msg = String.format("This type of join does not support mixed comparators: '%s' and '%s'.", comp1, comp2);
          throw new IllegalArgumentException(msg);
        }

      }
      conditions.add(new JoinCondition(comp1.getKind().toString(), new FieldReference(leftFields.get(pair.left)),
          new FieldReference(rightFields.get(pair.right))));
    }

  }

}
