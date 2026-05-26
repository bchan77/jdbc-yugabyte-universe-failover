package com.universe.jdbc.failover;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** In-memory counters exposed for logging and future Micrometer integration. */
public final class UniverseFailoverMetrics {
  private final AtomicReference<String> activeUniverseId = new AtomicReference<>();
  private final AtomicInteger consecutiveActiveProbeFailures = new AtomicInteger();
  private final AtomicInteger consecutiveStandbyProbeSuccesses = new AtomicInteger();
  private final AtomicInteger recentActiveConnectionFailures = new AtomicInteger();
  private final AtomicInteger failoverCount = new AtomicInteger();
  private final AtomicReference<Instant> lastSwitchAt = new AtomicReference<>();
  private final AtomicReference<HealthStatus> activeProbeStatus =
      new AtomicReference<>(HealthStatus.UNKNOWN);
  private final AtomicReference<HealthStatus> standbyProbeStatus =
      new AtomicReference<>(HealthStatus.UNKNOWN);

  void setActiveUniverseId(String id) {
    activeUniverseId.set(id);
  }

  void recordActiveProbe(HealthStatus status) {
    activeProbeStatus.set(status);
    if (status == HealthStatus.HEALTHY) {
      consecutiveActiveProbeFailures.set(0);
    } else if (status == HealthStatus.UNHEALTHY) {
      consecutiveActiveProbeFailures.incrementAndGet();
    }
  }

  void recordStandbyProbe(HealthStatus status) {
    standbyProbeStatus.set(status);
    if (status == HealthStatus.HEALTHY) {
      consecutiveStandbyProbeSuccesses.incrementAndGet();
    } else {
      consecutiveStandbyProbeSuccesses.set(0);
    }
  }

  void recordActiveConnectionFailure() {
    recentActiveConnectionFailures.incrementAndGet();
  }

  void resetRecentConnectionFailures() {
    recentActiveConnectionFailures.set(0);
  }

  void recordSwitch(Instant at) {
    failoverCount.incrementAndGet();
    lastSwitchAt.set(at);
    consecutiveActiveProbeFailures.set(0);
    recentActiveConnectionFailures.set(0);
  }

  public String activeUniverseId() {
    return activeUniverseId.get();
  }

  public int consecutiveActiveProbeFailures() {
    return consecutiveActiveProbeFailures.get();
  }

  public int consecutiveStandbyProbeSuccesses() {
    return consecutiveStandbyProbeSuccesses.get();
  }

  public int recentActiveConnectionFailures() {
    return recentActiveConnectionFailures.get();
  }

  public int failoverCount() {
    return failoverCount.get();
  }

  public Instant lastSwitchAt() {
    return lastSwitchAt.get();
  }

  public HealthStatus activeProbeStatus() {
    return activeProbeStatus.get();
  }

  public HealthStatus standbyProbeStatus() {
    return standbyProbeStatus.get();
  }
}
