package com.universe.jdbc.failover;

/** Result of a lightweight JDBC health probe against one universe pool. */
public enum HealthStatus {
  HEALTHY,
  UNHEALTHY,
  UNKNOWN
}
