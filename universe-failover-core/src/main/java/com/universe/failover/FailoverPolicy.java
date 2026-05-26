package com.universe.jdbc.failover;

/**
 * Decides when to switch the active Yugabyte universe. Implementations should treat single-node
 * blips inside a universe as out of scope — SmartDriver and the per-universe pool handle those.
 */
public interface FailoverPolicy {

  /**
   * Evaluate whether to change the active universe. Called periodically by background probes and
   * after recorded connection failures on the active pool.
   */
  FailoverDecision evaluate(FailoverContext context);

  /**
   * Optional hook when {@link UniverseFailoverDataSource#getConnection()} fails against the active
   * universe. Default implementation is a no-op.
   */
  default void onConnectionFailure(String universeId, Throwable failure) {}
}
