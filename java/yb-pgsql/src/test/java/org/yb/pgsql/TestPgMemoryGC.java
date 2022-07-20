package org.yb.pgsql;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collector;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.yb.util.YBTestRunnerNonTsanOnly;

@RunWith(value=YBTestRunnerNonTsanOnly.class)
public class TestPgMemoryGC extends BasePgSQLTest {

  private static final String PROC_STATUS_FILE_PATH = "/proc/%s/status";
  private static final String PROC_RSS_FIELD_NAME = "VmRSS";

  @Test
  public void testMetrics() throws Exception {
    try (Statement stmt = connection.createStatement()) {
        stmt.execute("CREATE TABLE tst (c1 INT PRIMARY KEY, c2 INT, c3 INT);");
        stmt.execute("INSERT INTO tst SELECT x, x+1, x+2 FROM GENERATE_SERIES(1, 1000000) x;");

        stmt.execute("SET work_mem=\"1GB\";");
        final String pg_pid = getPgPid(stmt);
        long rssBefore = getRssForPid(pg_pid);
        // For quick sorting 1M rows, it takes around 78MB memory.
        // This will trigger the PG's memory GC. Our GC threshold is 10MB by default.
        stmt.executeQuery("SELECT * FROM tst ORDER BY c2;");
        long rssAfter = getRssForPid(pg_pid);

        assertTrue("Freed bytes should be freed when GC threshold is reached",
            (rssAfter - rssBefore) < 10 * 1024);
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

  private long getRssForPid(final String pid) throws Exception {
    final Path procFilePath = Paths.get(String.format(PROC_STATUS_FILE_PATH, pid));
    assertTrue(Files.exists(procFilePath));

    try (Stream<String> stream = Files.lines(procFilePath)) {
      final List<String> rss =
          stream.filter(l -> l.contains(PROC_RSS_FIELD_NAME)).collect(Collectors.toList());
      assertEquals(rss.size(), 1);
      final String[] tokens = rss.get(0).split(" ");
      for (final String tk : tokens) {
        if (tk.trim().matches("\\d+")) {
          return Long.valueOf(tk);
        }
      }
    }

    throw new Exception("RSS stats not found.");
  }
}
