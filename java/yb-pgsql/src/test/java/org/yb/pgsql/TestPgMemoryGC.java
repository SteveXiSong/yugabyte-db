package org.yb.pgsql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.sql.ResultSet;
import java.sql.Statement;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.yb.util.CoreFileUtil;
import org.yb.util.YBTestRunnerNonTsanOnly;

@RunWith(value = YBTestRunnerNonTsanOnly.class)
public class TestPgMemoryGC extends BasePgSQLTest {

  private static final String DEFAULT_YB_PG_GC_THRESHOLD = "10MB";
  private static final long RSS_ACCEPTED_DIFF_AFTER_GC_BYTES = 10 * 1024;
  private static final String RSS_CMD = "ps -p %s -o rss=";

  /*
   * Verify that the freed memory allocated by a query is released to OS.
   */
  @Test
  public void testMetrics() throws Exception {
    // This is specically for Linux platforms. MAC doesn't use TCmalloc.
    if (CoreFileUtil.IS_MAC) {
      return;
    }

    try (Statement stmt = connection.createStatement()) {
      stmt.execute("CREATE TABLE tst (c1 INT PRIMARY KEY, c2 INT, c3 INT);");
      stmt.execute("INSERT INTO tst SELECT x, x+1, x+2 FROM GENERATE_SERIES(1, 1000000) x;");

      stmt.execute("SET work_mem=\"1GB\";");

      stmt.execute("SET yb_pg_mem_gc_threshold='10MB';");
      ResultSet thresholdRs = stmt.executeQuery("SHOW yb_pg_mem_gc_threshold;");
      assertTrue(thresholdRs.next());
      String threshold = thresholdRs.getString(1);
      assertEquals(DEFAULT_YB_PG_GC_THRESHOLD, threshold);

      final String pg_pid = getPgPid(stmt);
      long rssBefore = getRssForPid(pg_pid);
      // For quick sorting 1M rows, it takes around 78MB memory.
      // This will trigger the PG's memory GC. Our GC threshold is 10MB by default.
      stmt.executeQuery("SELECT * FROM tst ORDER BY c2;");
      long rssAfter = getRssForPid(pg_pid);

      assertTrue("Freed bytes should be freed when GC threshold is reached",
          (rssAfter - rssBefore) < RSS_ACCEPTED_DIFF_AFTER_GC_BYTES);

      // Make sure no memory leak, freed memory recycled even after multiple queries.
      for (int i = 0; i < 10; ++i) {
        stmt.executeQuery("SELECT * FROM tst ORDER BY c2;");
      }

      rssAfter = getRssForPid(pg_pid);
      assertTrue("Freed bytes should be freed when GC threshold is reached",
          (rssAfter - rssBefore) < RSS_ACCEPTED_DIFF_AFTER_GC_BYTES);
    }
  }

  /*
   * A helper method to get current connection's PID.
   */
  private String getPgPid(final Statement stmt) throws Exception {
    final ResultSet pidRs = stmt.executeQuery("SELECT pg_backend_pid();");
    String pid = null;
    while (pidRs.next()) {
      pid = pidRs.getString(1);
      break;
    }
    assertNotNull(pid);
    return pid;
  }

  /*
   * A helper method to get the current RSS memory for a PID.
   */
  private long getRssForPid(final String pid) throws Exception {
    final String cmd = String.format(RSS_CMD, pid);
    final StringBuilder stringBuilder = new StringBuilder();

    final Process process = Runtime.getRuntime().exec(cmd);
    final BufferedReader bufferedReader = new BufferedReader(
        new InputStreamReader(process.getInputStream()));
    String line;
    while ((line = bufferedReader.readLine()) != null) {
      stringBuilder.append(line);
    }
    final String rssStr = stringBuilder.toString();

    final long rss = Long.valueOf(rssStr);
    return rss;
  }
}
