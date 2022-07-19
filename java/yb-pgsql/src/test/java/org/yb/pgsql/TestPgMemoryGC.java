package org.yb.pgsql;

import static org.junit.Assert.assertTrue;

import java.nio.file.Files;
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
    
  @Test
  public void testMetrics() throws Exception {
    try (Statement stmt = connection.createStatement()) {
        stmt.execute("CREATE TABLE tst (c1 INT PRIMARY KEY, c2 INT, c3 INT);");
        stmt.execute("INSERT INTO tst SELECT x, x+1, x+2 FROM GENERATE_SERIES(1, 1000000) x;");

        stmt.execute("SET work_mem=\"10GB\";"); 
        String pg_pid = getPgPid(stmt); 
        long rssBefore = getRssForPid(pg_pid);
        stmt.executeQuery("SELECT * FROM tst ORDER BY c2;");
        long rssAfter = getRssForPid(pg_pid);

        assertTrue("RSS show be freed when GC threshold is reached",
            (rssAfter - rssBefore) < 10 * 1024);
    }
  }

  private String getPgPid(final Statement stmt) throws Exception {
    ResultSet pg_pid_rs = stmt.executeQuery("SELECT pg_backend_pid();");
    String pg_pid = null;
    while (pg_pid_rs.next()) {
      pg_pid = pg_pid_rs.getString(1);
      break;
    }

    if (pg_pid == null) {
      return "";
    }

    return pg_pid;
  }

  private long getRssForPid(final String pid) throws Exception {
    long rss_kb = 0;
    try (Stream<String> stream = Files.lines(Paths.get("/proc/" + pid + "/status"))) {
      List<String> rss = stream.filter(l -> l.contains("VmRSS")).collect(Collectors.toList());
      if (rss.size() != 1) {
        return 0;
      }
      String[] tokens = rss.get(0).split(" ");
      for (String tk : tokens) {
        if (tk.trim().matches("\\d+")) {
          rss_kb = Long.valueOf(tk);
          break;
        }
      }
    }

    return rss_kb;
  }
}
