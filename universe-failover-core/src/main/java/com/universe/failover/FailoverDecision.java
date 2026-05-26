package com.universe.jdbc.failover;

/**
 * Result of evaluating whether the routing layer should change the active universe.
 */
public enum FailoverDecision {
  /** Keep routing to the current active universe. */
  NO_ACTION,
  /**
   * Switch application traffic to the configured standby universe. The routing layer performs
   * the switch; this decision does not run YBA DR promotion.
   */
  FAILOVER_TO_STANDBY,
  /**
   * Switch back to the configured primary universe. Disabled by default in {@link
   * JdbcThresholdFailoverPolicy}.
   */
  FAILBACK_TO_PRIMARY,
  /**
   * Failover criteria met but preconditions failed (for example standby probe unhealthy or
   * cooldown active).
   */
  BLOCKED
}
