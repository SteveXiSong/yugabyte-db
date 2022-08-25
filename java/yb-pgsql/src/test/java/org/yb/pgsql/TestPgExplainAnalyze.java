// Copyright (c) YugaByte, Inc.
//
// Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
// in compliance with the License.  You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software distributed under the License
// is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
// or implied.  See the License for the specific language governing permissions and limitations
// under the License.
//
package org.yb.pgsql;

import static org.yb.AssertionWrappers.assertEquals;
import static org.yb.AssertionWrappers.assertGreaterThan;
import static org.yb.AssertionWrappers.assertTrue;

import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.yb.util.YBTestRunnerNonTsanOnly;

/**
 * Test EXPLAIN ANALYZE command. Just verify non-zero values for volatile measures
 * such as RPC wait times.
 */
@RunWith(value=YBTestRunnerNonTsanOnly.class)
public class TestPgExplainAnalyze extends BasePgSQLTest {
  private static final Logger LOG = LoggerFactory.getLogger(TestPgExplainAnalyze.class);
  private static final int kPrefetchLimit = 1024;
  private static final int kSessionMaxBatchSize = 512;
  private static final String kTableName = "explain_test_table";
  private static final String kPkIndexName = String.format("%s_pkey", kTableName);
  private static final String kIndexName = String.format("i_%s_c3_c2", kTableName);
  private static final int kTableRows = 5000;

  @Override
  protected int getReplicationFactor() {
    return 1;
  }

  @Override
  protected int getInitialNumTServers() {
    return 1;
  }

  @Override
  protected Map<String, String> getTServerFlags() {
    Map<String, String> flagMap = super.getTServerFlags();
    flagMap.put("ysql_prefetch_limit", Integer.toString(kPrefetchLimit));
    flagMap.put("ysql_session_max_batch_size", Integer.toString(kSessionMaxBatchSize));
    flagMap.put("TEST_use_monotime_for_rpc_wait_time", "true");
    return flagMap;
  }

  @Before
  public void setUp() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.execute(String.format(
          "CREATE TABLE %s (c1 bigint, c2 bigint, c3 bigint, c4 text, "
          + "PRIMARY KEY(c1 HASH, c2 ASC, c3 ASC))", kTableName));

      stmt.execute(String.format(
          "INSERT INTO %s SELECT i %% 1000, i %% 11, i %% 20, rpad(i::text, 256, '#') "
          + "FROM generate_series(1, %d) i",
          kTableName, kTableRows));

      stmt.execute(String.format(
          "CREATE INDEX %s ON %s (c3 ASC, c2 ASC)", kIndexName, kTableName));
    }
  }

  @After
  public void tearDown() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      stmt.execute("DROP TABLE " + kTableName);
    }
  }

  private static class NodeIdentifier {
    final String nodeType;
    final String relationName;
    final String alias;
    final String indexName;

    NodeIdentifier(String nodeType, String relationName, String alias, String indexName) {
      this.nodeType = nodeType;
      this.relationName = relationName;
      this.alias = alias;
      this.indexName = indexName;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("[");
      sb.append(nodeType);
      if (!relationName.isEmpty()) {
        sb.append(" ");
        sb.append(relationName);
      }
      if (!alias.isEmpty()) {
        sb.append(" ");
        sb.append(alias);
      }
      if (!indexName.isEmpty()) {
        sb.append(" (");
        sb.append(indexName);
        sb.append(")");
      }
      sb.append("]");
      return sb.toString();
    }
  };

  private static class ExpectedNodeFields {
    final NodeIdentifier nodeIdent;
    final Map<String, Double> expectedFields;

    ExpectedNodeFields(String nodeType, String relationName, String alias, String indexName,
                       Map<String, Double> expectedFields) {
      this.nodeIdent = new NodeIdentifier(nodeType, relationName, alias, indexName);
      this.expectedFields = expectedFields;
    }
  };

  private static String q(String s) {
    return "\"" + s + "\"";
  }

  private static JsonObject findPlanNode(JsonObject plan, NodeIdentifier nodeIdent) {
    final String kNodeType = "Node Type";
    final String kRelationName = "Relation Name";
    final String kAlias = "Alias";
    final String kIndexName = "Index Name";

    final JsonElement nodeTypeElem = plan.get(kNodeType);
    if (nodeTypeElem != null
        && nodeTypeElem.getAsString().equals(nodeIdent.nodeType)
        && Objects.toString(plan.get(kRelationName),
                            q("")).equals(q(nodeIdent.relationName))
        && Objects.toString(plan.get(kAlias),
                            q("")).equals(q(nodeIdent.alias))
        && Objects.toString(plan.get(kIndexName),
                            q("")).equals(q(nodeIdent.indexName))) {
      return plan;
    }

    final JsonArray subplans = plan.getAsJsonArray("Plans");
    if (subplans != null) {
      for (JsonElement spElem : subplans) {
        assertTrue(spElem.isJsonObject());
        final JsonObject subplan = findPlanNode(spElem.getAsJsonObject(), nodeIdent);
        if (subplan != null) {
          return subplan;
        }
      }
    }

    return null;
  }

  private static final String kReadRpcCount = "Read RPC";
  private static final String kReadRpcWaitTime = "Read RPC Wait Time";
  private static final String kTableReadRpcCount = "Table Read RPC";
  private static final String kTableReadRpcWaitTime = "Table Read RPC Wait Time";

  private static final String kTotalReadRpcWaitTime = "Total Read RPC Wait Time";
  private static final String kTotalWriteRpcCount = "Total Write RPC";
  private static final String kTotalWriteRpcWaitTime = "Total Write RPC Wait Time";

  private static final String kDocDBScannedTableRows = "DocDB Scanned Table Rows";
  private static final String kDocDBScannedIndexRows = "DocDB Scanned Index Rows";
  private static final String kDocDBScannedRows = "DocDB Scanned Rows";


  // Special expected field values for negative tests and checking nondeterministic measures
  private static final double kShouldNotExist = -1.0;
  private static final double kGreaterThanZero = -2.0;

  private static final String kFmtNotFound = "%s: %s not found: \"%s\"";
  private static final String kFmtFieldShouldNotExist = "%s: Field should not exist: \"%s\"";
  private static final String kFmtExpectedGt = "%s: \"%s\": Expected %s to be greater than %s";

  private static void checkTheFields(String query, JsonObject obj,
                                     Map<String, Double> expectedFields) throws Exception {
    for (Map.Entry<String, Double> entry : expectedFields.entrySet()) {
      JsonElement elem = obj.get(entry.getKey());
      if (entry.getValue() == kShouldNotExist) {
        assertTrue(String.format(kFmtFieldShouldNotExist, query, entry.getKey()),
                   elem == null);
        continue;
      }
      assertTrue(String.format(kFmtNotFound, query, "Field", entry.getKey()),
                 elem != null);

      if (entry.getValue() == kGreaterThanZero) {
        assertGreaterThan(
            String.format(kFmtExpectedGt, query, entry.getKey(), elem.getAsDouble(), 0.0),
            elem.getAsDouble(), 0.0);
      } else {
        assertEquals(query + ": " + entry.toString(), entry.getValue(), elem.getAsDouble());
        // *** To avoid stopping at each diff and get all the diffs after adding or modifying the
        // tests, comment out the assertEquals above and uncomment the lines below.
        // if (entry.getValue() != elem.getAsDouble()) {
        //   LOG.info(String.format("%s: [%s] Expected %s but %s",
        //                          query, entry.toString(),
        //                          entry.getValue(), elem.getAsDouble()));
        // }
      }
    }
  }

  private static final String kExplainOpts = "FORMAT json, ANALYZE true, SUMMARY true, RPC true, DOCDB true";
  private static final String kExplainOptsNoTiming = kExplainOpts + ", TIMING false";

  private void testOneQuery(Statement stmt, String explainOpts, String query,
                            List<ExpectedNodeFields> expectedNodeFieldList,
                            Map<String, Double> expectedSummaryFields) throws Exception {
    LOG.info("Query: " + query);
    String resultJson = getRowList(stmt,
                                   String.format("EXPLAIN (%s) %s", explainOpts, query))
                        .get(0).get(0).toString();
    JsonObject obj = new JsonParser().parse(resultJson).getAsJsonArray().get(0).getAsJsonObject();
    Gson gson = new GsonBuilder().setPrettyPrinting().create();
    LOG.info("Plan: " + gson.toJson(obj));

    // Check the execution nodes of interest
    for (ExpectedNodeFields nodeFields : expectedNodeFieldList) {
      JsonObject node = findPlanNode(obj.getAsJsonObject("Plan"), nodeFields.nodeIdent);
      assertTrue(String.format(kFmtNotFound, query, "Node", nodeFields.nodeIdent.toString()),
                 node != null);
      checkTheFields(query, node, nodeFields.expectedFields);
    }

    // Check the Summary section
    checkTheFields(query, obj, expectedSummaryFields);
  }

  private void testExplainOneQuery(Statement stmt, String query,
                                   List<ExpectedNodeFields> expectedNodeFieldList,
                                   Map<String, Double> expectedSummaryFields) throws Exception {
    testOneQuery(stmt, kExplainOpts, query, expectedNodeFieldList, expectedSummaryFields);
  }

  private void testExplainOneQueryNoTiming(Statement stmt, String query,
                                           List<ExpectedNodeFields> expectedNodeFieldList,
                                           Map<String, Double> expectedSummaryFields)
      throws Exception {
    testOneQuery(stmt, kExplainOptsNoTiming, query, expectedNodeFieldList, expectedSummaryFields);
  }

  @Test
  public void testPgExplainAnalyze() throws Exception {
    try (Statement stmt = connection.createStatement()) {
      // Seq Scan (ybc_fdw ForeignScan)
      testExplainOneQuery(stmt, String.format(
          "SELECT * FROM %s", kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Seq Scan", kTableName, kTableName, "",
                           ImmutableMap.of(
                               kReadRpcCount, 5.0,
                               kReadRpcWaitTime, kGreaterThanZero,
                               kTableReadRpcCount, kShouldNotExist,
                               kTableReadRpcWaitTime, kShouldNotExist,
                               kDocDBScannedTableRows, (double)kTableRows))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, kShouldNotExist,
                       kTotalWriteRpcWaitTime, kShouldNotExist,
                       kDocDBScannedRows, (double)kTableRows));

      // real Seq Scan
      testExplainOneQuery(stmt, String.format(
          "/*+ SeqScan(texpl) */SELECT * FROM %s", kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Seq Scan", kTableName, kTableName, "",
                           ImmutableMap.of(
                               kReadRpcCount, 5.0,
                               kReadRpcWaitTime, kGreaterThanZero,
                               kTableReadRpcCount, kShouldNotExist,
                               kTableReadRpcWaitTime, kShouldNotExist,
                               kDocDBScannedTableRows, (double)kTableRows))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, kShouldNotExist,
                       kTotalWriteRpcWaitTime, kShouldNotExist,
                       kDocDBScannedRows, (double)kTableRows));

      {// PK Index Scan
      final ImmutableMap<String, Double> kExpIndexScanNodeFields = ImmutableMap.<String, Double>builder()
          .put(kReadRpcCount, 1.0)
          .put(kReadRpcWaitTime, kGreaterThanZero)
          .put(kTableReadRpcCount, kShouldNotExist)
          .put(kTableReadRpcWaitTime, kShouldNotExist)
          .put(kDocDBScannedIndexRows, 0.0)
          .put(kDocDBScannedTableRows, 5.0).build();

      testExplainOneQuery(stmt, String.format(
          "SELECT * FROM %s WHERE c1 = 10", kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Index Scan", kTableName, kTableName, kPkIndexName,
                           kExpIndexScanNodeFields)),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, kShouldNotExist,
                       kTotalWriteRpcWaitTime, kShouldNotExist,
                       kDocDBScannedRows, 5.0));
      }

      {// Secondary Index Scan
      final ImmutableMap<String, Double> kExpIndexScanNodeFields = ImmutableMap.<String, Double>builder()
          .put(kReadRpcCount, 4.0)
          .put(kReadRpcWaitTime, kGreaterThanZero)
          .put(kTableReadRpcCount, 4.0)
          .put(kTableReadRpcWaitTime, kGreaterThanZero)
          .put(kDocDBScannedIndexRows, 4000.0)
          .put(kDocDBScannedTableRows, 4000.0).build();

      testExplainOneQuery(stmt, String.format(
          "/*+ IndexScan(t %s) */SELECT * FROM %s t WHERE c3 <= 15",
          kIndexName, kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Index Scan", kTableName, "t", kIndexName,
                           kExpIndexScanNodeFields)),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, kShouldNotExist,
                       kTotalWriteRpcWaitTime, kShouldNotExist,
                       kDocDBScannedRows, 8000.0));
      }

      {// Secondary Index Only Scan
      testExplainOneQuery(stmt, String.format(
          "/*+ IndexOnlyScan(t %s) */SELECT c2, c3 FROM %s t WHERE c3 <= 15",
          kIndexName, kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Index Only Scan", kTableName, "t", kIndexName,
                           ImmutableMap.of(
                               kReadRpcCount, 4.0,
                               kReadRpcWaitTime, kGreaterThanZero,
                               kTableReadRpcCount, kShouldNotExist,
                               kTableReadRpcWaitTime, kShouldNotExist,
                               kDocDBScannedIndexRows, 4000.0))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, kShouldNotExist,
                       kTotalWriteRpcWaitTime, kShouldNotExist,
                       kDocDBScannedRows, 4000.0));
      }

      {// NestLoop accesses the inner table as many times as the rows from the outer
      final ImmutableMap<String, Double> kExpIndexScanNodeFieldsT1 = ImmutableMap.<String, Double>builder()
          .put(kReadRpcCount, 1.0)
          .put(kReadRpcWaitTime, kGreaterThanZero)
          .put(kTableReadRpcCount, kShouldNotExist)
          .put(kTableReadRpcWaitTime, kShouldNotExist)
          .put(kDocDBScannedIndexRows, 0.0)
          .put(kDocDBScannedTableRows, 5.0).build();

      final ImmutableMap<String, Double> kExpIndexScanNodeFieldsT2 = ImmutableMap.<String, Double>builder()
          .put(kReadRpcCount, 4.0)
          .put(kReadRpcWaitTime, kGreaterThanZero)
          .put(kTableReadRpcCount, 4.0)
          .put(kTableReadRpcWaitTime, kGreaterThanZero)
          .put(kDocDBScannedIndexRows, 500.0)
          .put(kDocDBScannedTableRows, 500.0).build();

      testExplainOneQuery(stmt, String.format(
          "/*+ IndexScan(t1 %s) IndexScan(t2 %s) Leading((t1 t2)) NestLoop(t1 t2) */"
          + "SELECT * FROM %s AS t1 JOIN %s AS t2 ON t1.c2 <= t2.c3 AND t1.c1 = 1",
          kPkIndexName, kIndexName, kTableName, kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Index Scan", kTableName, "t1", kPkIndexName,
                           kExpIndexScanNodeFieldsT1),
                       new ExpectedNodeFields(
                           "Index Scan", kTableName, "t2", kIndexName,
                           kExpIndexScanNodeFieldsT2)),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, kShouldNotExist,
                       kTotalWriteRpcWaitTime, kShouldNotExist,
                       kDocDBScannedRows, 5005.0));
      }

      {// Inner table never executed
      final ImmutableMap<String, Double> kExpIndexScanNodeFieldsT1 = ImmutableMap.<String, Double>builder()
          .put(kReadRpcCount, 1.0)
          .put(kReadRpcWaitTime, kGreaterThanZero)
          .put(kTableReadRpcCount, kShouldNotExist)
          .put(kTableReadRpcWaitTime, kShouldNotExist)
          .put(kDocDBScannedIndexRows, 0.0)
          .put(kDocDBScannedTableRows, 5.0).build();

      final ImmutableMap<String, Double> kExpIndexScanNodeFieldsT2 = ImmutableMap.<String, Double>builder()
          .put(kReadRpcCount, kShouldNotExist)
          .put(kReadRpcWaitTime, kShouldNotExist)
          .put(kTableReadRpcCount, kShouldNotExist)
          .put(kTableReadRpcWaitTime, kShouldNotExist)
          .put(kDocDBScannedIndexRows, 0.0)
          .put(kDocDBScannedTableRows, 0.0).build();

      testExplainOneQuery(stmt, String.format(
          "/*+ IndexScan(t1 %s) IndexScan(t2 %s) Leading((t1 t2)) NestLoop(t1 t2) */"
          + "SELECT * FROM %s AS t1 JOIN %s AS t2 ON t1.c2 <= t2.c3 AND t1.c1 = -1",
          kPkIndexName, kIndexName, kTableName, kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Index Scan", kTableName, "t1", kPkIndexName, kExpIndexScanNodeFieldsT1),
                       new ExpectedNodeFields(
                           "Index Scan", kTableName, "t2", kIndexName, kExpIndexScanNodeFieldsT2)),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, kShouldNotExist,
                       kTotalWriteRpcWaitTime, kShouldNotExist,
                       kDocDBScannedRows, 5.0));
      }

      // Modification statements

      // INSERT value list
      // reduce the batch size to avoid 0 wait time
      stmt.execute("SET ysql_session_max_batch_size = 4");
      testExplainOneQuery(stmt, String.format(
          "INSERT INTO %s VALUES (1001, 0, 0, 'xyz'), (1002, 0, 0, 'wxy'), (1003, 0, 0, 'vwx'), "
          + "(1004, 0, 0, 'vwx')", kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Values Scan", "", "*VALUES*", "",
                           ImmutableMap.of(
                               kReadRpcCount, kShouldNotExist,
                               kReadRpcWaitTime, kShouldNotExist,
                               kTableReadRpcCount, kShouldNotExist,
                               kTableReadRpcWaitTime, kShouldNotExist))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kShouldNotExist,
                       kTotalWriteRpcCount, 2.0,
                       kTotalWriteRpcWaitTime, kGreaterThanZero));

      // no buffering
      stmt.execute("SET ysql_session_max_batch_size = 1");
      testExplainOneQuery(stmt, String.format(
          "INSERT INTO %s VALUES (1601, 0, 0, 'xyz'), (1602, 0, 0, 'wxy'), (1603, 0, 0, 'vwx'), "
          + "(1604, 0, 0, 'vwx')", kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Values Scan", "", "*VALUES*", "",
                           ImmutableMap.of(
                               kReadRpcCount, kShouldNotExist,
                               kReadRpcWaitTime, kShouldNotExist,
                               kTableReadRpcCount, kShouldNotExist,
                               kTableReadRpcWaitTime, kShouldNotExist))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kShouldNotExist,
                       kTotalWriteRpcCount, 8.0,
                       kTotalWriteRpcWaitTime, kGreaterThanZero));
      stmt.execute("RESET ysql_session_max_batch_size");

      // INSERT ... SELECT FORM non-YB table
      testExplainOneQuery(stmt, String.format(
          "INSERT INTO %s SELECT %d + i %% 1000, i %% 11, i %% 20, rpad(i::text, 256, '#') "
          + "FROM generate_series(%d, %d) i",
          kTableName, kTableRows, kTableRows+1, kTableRows + kTableRows/2),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Function Scan", "", "i", "",
                           ImmutableMap.of(
                               kReadRpcCount, kShouldNotExist,
                               kReadRpcWaitTime, kShouldNotExist,
                               kTableReadRpcCount, kShouldNotExist,
                               kTableReadRpcWaitTime, kShouldNotExist))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kShouldNotExist,
                       kTotalWriteRpcCount, 10.0,
                       kTotalWriteRpcWaitTime, kGreaterThanZero));

      // UPDATE using index, a large payload updating wide text column
      testExplainOneQuery(stmt, String.format(
          "/*+ IndexScan(t %s) */"
          + "UPDATE %s AS t SET c4 = rpad(c1::text, 256, '@') WHERE c2 = 3 AND c3 <= 8",
          kIndexName, kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Index Scan", kTableName, "t", kIndexName,
                           ImmutableMap.of(
                               kReadRpcCount, 1.0,
                               kReadRpcWaitTime, kGreaterThanZero,
                               kTableReadRpcCount, 1.0,
                               kTableReadRpcWaitTime, kGreaterThanZero))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, 308.0,
                       kTotalWriteRpcWaitTime, kGreaterThanZero));

      // DELETE using index
      testExplainOneQuery(stmt, String.format(
          "/*+ IndexScan(t %s) */DELETE FROM %s AS t WHERE c1 >= 1000", kPkIndexName, kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Index Scan", kTableName, "t", kPkIndexName,
                           ImmutableMap.of(
                               kReadRpcCount, 8.0,
                               kReadRpcWaitTime, kGreaterThanZero,
                               kTableReadRpcCount, kShouldNotExist,
                               kTableReadRpcWaitTime, kShouldNotExist))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, 14.0,
                       kTotalWriteRpcWaitTime, kGreaterThanZero));


      // start transaction before deleting everything so we can rollback and get the data
      // back for subsequent tests
      stmt.execute("BEGIN");

      // DELETE without WHERE (
      testExplainOneQuery(stmt, String.format(
          "DELETE FROM %s", kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Seq Scan", kTableName, kTableName, "",
                           ImmutableMap.of(
                               kReadRpcCount, 5.0,
                               kReadRpcWaitTime, kGreaterThanZero,
                               kTableReadRpcCount, kShouldNotExist,
                               kTableReadRpcWaitTime, kShouldNotExist))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, 20.0,
                       kTotalWriteRpcWaitTime, kGreaterThanZero));

      // Do it again - should be no writes
      testExplainOneQuery(stmt, String.format(
          "DELETE FROM %s", kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Seq Scan", kTableName, kTableName, "",
                           ImmutableMap.of(
                               kReadRpcCount, 1.0,
                               kReadRpcWaitTime, kGreaterThanZero,
                               kTableReadRpcCount, kShouldNotExist,
                               kTableReadRpcWaitTime, kShouldNotExist))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, kShouldNotExist,
                       kTotalWriteRpcWaitTime, kShouldNotExist));

      stmt.execute("ROLLBACK");

      // Test that "TIMING false" option suppressing RPC wait time printing
      stmt.execute("BEGIN");
      testExplainOneQueryNoTiming(stmt, String.format(
          "/*+ IndexScan(t %s) */"
          + "UPDATE %s AS t SET c4 = rpad(c1::text, 256, '@') WHERE c2 = 1 AND c3 <= 8",
          kIndexName, kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Index Scan", kTableName, "t", kIndexName,
                           ImmutableMap.of(
                               kReadRpcCount, 1.0,
                               kReadRpcWaitTime, kShouldNotExist,
                               kTableReadRpcCount, 1.0,
                               kTableReadRpcWaitTime, kShouldNotExist))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kShouldNotExist,
                       kTotalWriteRpcCount, 206.0,
                       kTotalWriteRpcWaitTime, kShouldNotExist));
      stmt.execute("ROLLBACK");


      // Modification statements with RETURNING

      testExplainOneQuery(stmt, String.format(
          "INSERT INTO %s VALUES (1001, 0, 0, 'abc') RETURNING *", kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Result", "", "", "",
                           ImmutableMap.of(
                               kReadRpcCount, kShouldNotExist,
                               kReadRpcWaitTime, kShouldNotExist,
                               kTableReadRpcCount, kShouldNotExist,
                               kTableReadRpcWaitTime, kShouldNotExist))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kShouldNotExist,
                       kTotalWriteRpcCount, 1.0,
                       kTotalWriteRpcWaitTime, kGreaterThanZero));

      testExplainOneQuery(stmt, String.format(
          "UPDATE %s SET c4 = rpad(c1::text, 256, '*') WHERE c1 = 1001 RETURNING *", kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Index Scan", kTableName, kTableName, kPkIndexName,
                           ImmutableMap.of(
                               kReadRpcCount, 1.0,
                               kReadRpcWaitTime, kGreaterThanZero,
                               kTableReadRpcCount, kShouldNotExist,
                               kTableReadRpcWaitTime, kShouldNotExist))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, 2.0,
                       kTotalWriteRpcWaitTime, kGreaterThanZero));

      testExplainOneQuery(stmt, String.format(
          "/*+ IndexScan(t %s) */DELETE FROM %s AS t WHERE c1 >= 500 RETURNING *",
          kPkIndexName, kTableName),
                   ImmutableList.of(
                       new ExpectedNodeFields(
                           "Index Scan", kTableName, "t", kPkIndexName,
                           ImmutableMap.of(
                               kReadRpcCount, 5.0,
                               kReadRpcWaitTime, kGreaterThanZero,
                               kTableReadRpcCount, kShouldNotExist,
                               kTableReadRpcWaitTime, kShouldNotExist))),
                   ImmutableMap.of(
                       kTotalReadRpcWaitTime, kGreaterThanZero,
                       kTotalWriteRpcCount, 13.0,
                       kTotalWriteRpcWaitTime, kGreaterThanZero));
    }
  }
}
