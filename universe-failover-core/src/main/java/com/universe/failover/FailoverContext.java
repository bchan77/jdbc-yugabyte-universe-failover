package com.universe.jdbc.failover;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Snapshot passed to {@link FailoverPolicy#evaluate(FailoverContext)} when deciding whether to
 * change the active universe.
 */
public final class FailoverContext {
  private final String activeUniverseId;
  private final String primaryUniverseId;
  private final String standbyUniverseId;
  private final HealthStatus activeProbeStatus;
  private final HealthStatus standbyProbeStatus;
  private final int consecutiveActiveProbeFailures;
  private final int consecutiveStandbyProbeSuccesses;
  private final int recentActiveConnectionFailures;
  private final Duration timeSinceLastSwitch;
  private final Optional<Throwable> lastConnectionFailure;

  FailoverContext(
      String activeUniverseId,
      String primaryUniverseId,
      String standbyUniverseId,
      HealthStatus activeProbeStatus,
      HealthStatus standbyProbeStatus,
      int consecutiveActiveProbeFailures,
      int consecutiveStandbyProbeSuccesses,
      int recentActiveConnectionFailures,
      Duration timeSinceLastSwitch,
      Throwable lastConnectionFailure) {
    this.activeUniverseId = activeUniverseId;
    this.primaryUniverseId = primaryUniverseId;
    this.standbyUniverseId = standbyUniverseId;
    this.activeProbeStatus = activeProbeStatus;
    this.standbyProbeStatus = standbyProbeStatus;
    this.consecutiveActiveProbeFailures = consecutiveActiveProbeFailures;
    this.consecutiveStandbyProbeSuccesses = consecutiveStandbyProbeSuccesses;
    this.recentActiveConnectionFailures = recentActiveConnectionFailures;
    this.timeSinceLastSwitch = timeSinceLastSwitch;
    this.lastConnectionFailure = Optional.ofNullable(lastConnectionFailure);
  }

  public String activeUniverseId() {
    return activeUniverseId;
  }

  public String primaryUniverseId() {
    return primaryUniverseId;
  }

  public String standbyUniverseId() {
    return standbyUniverseId;
  }

  public HealthStatus activeProbeStatus() {
    return activeProbeStatus;
  }

  public HealthStatus standbyProbeStatus() {
    return standbyProbeStatus;
  }

  public int consecutiveActiveProbeFailures() {
    return consecutiveActiveProbeFailures;
  }

  public int consecutiveStandbyProbeSuccesses() {
    return consecutiveStandbyProbeSuccesses;
  }

  public int recentActiveConnectionFailures() {
    return recentActiveConnectionFailures;
  }

  public Duration timeSinceLastSwitch() {
    return timeSinceLastSwitch;
  }

  public Optional<Throwable> lastConnectionFailure() {
    return lastConnectionFailure;
  }

  public boolean activeIsPrimary() {
    return activeUniverseId.equals(primaryUniverseId);
  }

  public Instant evaluatedAt() {
    return Instant.now();
  }
}
