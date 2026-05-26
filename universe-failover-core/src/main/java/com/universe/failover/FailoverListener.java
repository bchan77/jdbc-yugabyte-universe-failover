package com.universe.jdbc.failover;

import java.time.Instant;

/** Callback when the active universe changes. */
@FunctionalInterface
public interface FailoverListener {
  void onFailover(
      String fromUniverseId, String toUniverseId, FailoverDecision decision, Instant switchedAt);
}
