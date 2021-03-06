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
package org.apache.drill.exec.vector.complex.fn;


import java.util.List;
import java.util.Objects;

import org.apache.drill.BaseTestQuery;
import org.apache.drill.exec.record.RecordBatchLoader;
import org.apache.drill.exec.rpc.user.QueryResultBatch;
import org.apache.drill.exec.util.JsonStringArrayList;
import org.apache.drill.exec.util.JsonStringHashMap;
import org.apache.drill.exec.vector.ValueVector;
import org.junit.Test;

public class TestJsonReaderWithSparseFiles extends BaseTestQuery {

  static interface Function<T> {
    void apply(T param);
  }

  static class TypeConverter {

    public Object convert(Object obj) {
      if (obj instanceof JsonStringArrayList || obj instanceof JsonStringHashMap) {
        return obj.toString();
      }
      return obj;
    }

  }

  static class Verifier implements Function<RecordBatchLoader> {

    private final int count;
    private final Object[][] values;
    private final TypeConverter converter = new TypeConverter();

    protected Verifier(int count, Object[][] values) {
      this.count = count;
      this.values = values;
    }

    @Override
    public void apply(RecordBatchLoader loader) {
      assert loader.getRecordCount() == count : "invalid record count returned";

      Object[] row;
      Object expected;
      Object actual;
      for (int r=0;r<values.length;r++) {
        row = values[r];
        for (int c=0; c<values[r].length; c++) {
          expected = row[c];
          actual = loader.getValueAccessorById(ValueVector.class, c).getValueVector().getAccessor().getObject(r);
          actual = converter.convert(actual);
          assert Objects.equals(actual, expected) : String.format("row:%d - col:%d - expected:%s[%s] - actual:%s[%s]",
              r, c,
              expected,
              expected==null?"null":expected.getClass().getSimpleName(),
              actual,
              actual==null?"null":actual.getClass().getSimpleName());
        }
      }
    }
  }

  protected void query(final String query, final Function<RecordBatchLoader> testBody) throws Exception {
    List<QueryResultBatch> batches = testSqlWithResults(query);
    RecordBatchLoader loader = new RecordBatchLoader(client.getAllocator());
    try {
      // first batch at index 0 is empty and used for fast schema return. Load the second one for the tests
      QueryResultBatch batch = batches.get(0);
      loader.load(batch.getHeader().getDef(), batch.getData());
      testBody.apply(loader);
    } finally {
      for (QueryResultBatch batch:batches) {
        batch.release();
      }
      loader.clear();
    }
  }

  @Test
  public void testIfDrillCanReadSparseRecords() throws Exception {
    final String sql = "select * from cp.`vector/complex/fn/sparse.json`";
    //XXX: make sure value order matches vector order
    final Object[][] values = new Object[][] {
        {null, null},
        {1L, null},
        {null, 2L},
        {3L, 3L}
    };
    query(sql, new Verifier(4, values));
  }

  @Test
  public void testIfDrillCanReadSparseNestedRecordsWithoutRaisingException() throws Exception {
    final String sql = "select * from cp.`vector/complex/fn/nested-with-nulls.json`";
    //XXX: make sure value order matches vector order
    final Object[][] values = new Object[][] {
        {"[{},{},{},{\"name\":\"doe\"},{}]"},
        {"[]"},
        {"[{\"name\":\"john\",\"id\":10}]"},
        {"[{},{}]"},
    };
    query(sql, new Verifier(4, values));
  }

  @Test
  public void testIfDrillCanQuerySingleRecordWithEmpties() throws Exception {
    final String sql = "select * from cp.`vector/complex/fn/single-record-with-empties.json`";
    //XXX: make sure value order matches vector order
    final Object[][] values = new Object[][] {
        {"[{},{}]"},
    };
    query(sql, new Verifier(1, values));
  }

}
