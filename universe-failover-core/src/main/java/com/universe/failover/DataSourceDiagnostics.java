package com.universe.jdbc.failover;

import java.lang.reflect.Method;
import javax.sql.DataSource;

/**
 * Best-effort description of a {@link DataSource} for logs. Uses reflection for HikariCP when
 * present so core does not require a compile-time dependency on Hikari.
 */
public final class DataSourceDiagnostics {
  private static final String HIKARI_CLASS = "com.zaxxer.hikari.HikariDataSource";

  private DataSourceDiagnostics() {}

  /** Returns a JDBC URL safe to print in logs (passwords redacted). */
  public static String sanitizeJdbcUrl(String jdbcUrl) {
    return JdbcUrlSanitizer.sanitize(jdbcUrl);
  }

  public static String describe(DataSource dataSource) {
    if (dataSource == null) {
      return "null";
    }
    if (HIKARI_CLASS.equals(dataSource.getClass().getName())) {
      return describeHikari(dataSource);
    }
    return dataSource.getClass().getName();
  }

  private static String describeHikari(DataSource dataSource) {
    String poolName = invokeString(dataSource, "getPoolName");
    String jdbcUrl = JdbcUrlSanitizer.sanitize(invokeString(dataSource, "getJdbcUrl"));
    String driver = invokeString(dataSource, "getDriverClassName");
    Integer maxPool = invokeInteger(dataSource, "getMaximumPoolSize");
    Long connTimeout = invokeLong(dataSource, "getConnectionTimeout");

    StringBuilder sb = new StringBuilder("HikariCP");
    if (poolName != null) {
      sb.append(" pool=").append(poolName);
    }
    if (jdbcUrl != null) {
      sb.append(" url=").append(jdbcUrl);
    }
    if (driver != null && !driver.isBlank()) {
      sb.append(" driver=").append(driver);
    }
    if (maxPool != null) {
      sb.append(" maxPool=").append(maxPool);
    }
    if (connTimeout != null) {
      sb.append(" connTimeoutMs=").append(connTimeout);
    }
    return sb.toString();
  }

  private static String invokeString(Object target, String method) {
    Object value = invoke(target, method);
    return value == null ? null : value.toString();
  }

  private static Integer invokeInteger(Object target, String method) {
    Object value = invoke(target, method);
    return value instanceof Number number ? number.intValue() : null;
  }

  private static Long invokeLong(Object target, String method) {
    Object value = invoke(target, method);
    return value instanceof Number number ? number.longValue() : null;
  }

  private static Object invoke(Object target, String methodName) {
    try {
      Method method = target.getClass().getMethod(methodName);
      method.setAccessible(true);
      return method.invoke(target);
    } catch (ReflectiveOperationException ignored) {
      return null;
    }
  }
}
