package com.universe.jdbc.failover;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class JdbcThresholdFailoverPolicyTest {

  private final JdbcThresholdFailoverPolicy policy =
      JdbcThresholdFailoverPolicy.builder()
          .consecutiveActiveProbeFailures(3)
          .minStandbyProbeSuccesses(1)
          .cooldownAfterSwitch(Duration.ofMinutes(5))
          .requireStandbyHealthy(true)
          .build();

  @Test
  void healthyActiveDoesNotFailover() {
    FailoverContext ctx =
        new FailoverContext(
            "eu",
            "eu",
            "us",
            HealthStatus.HEALTHY,
            HealthStatus.HEALTHY,
            0,
            2,
            0,
            Duration.ofHours(1),
            null);
    assertEquals(FailoverDecision.NO_ACTION, policy.evaluate(ctx));
  }

  @Test
  void singleProbeFailureDoesNotFailover() {
    FailoverContext ctx =
        new FailoverContext(
            "eu",
            "eu",
            "us",
            HealthStatus.UNHEALTHY,
            HealthStatus.HEALTHY,
            1,
            2,
            0,
            Duration.ofHours(1),
            null);
    assertEquals(FailoverDecision.NO_ACTION, policy.evaluate(ctx));
  }

  @Test
  void sustainedActiveFailureWithHealthyStandbyFailsOver() {
    FailoverContext ctx =
        new FailoverContext(
            "eu",
            "eu",
            "us",
            HealthStatus.UNHEALTHY,
            HealthStatus.HEALTHY,
            3,
            1,
            0,
            Duration.ofHours(1),
            null);
    assertEquals(FailoverDecision.FAILOVER_TO_STANDBY, policy.evaluate(ctx));
  }

  @Test
  void activeUnhealthyWithoutStandbyIsBlocked() {
    FailoverContext ctx =
        new FailoverContext(
            "eu",
            "eu",
            "us",
            HealthStatus.UNHEALTHY,
            HealthStatus.UNHEALTHY,
            5,
            0,
            0,
            Duration.ofHours(1),
            null);
    assertEquals(FailoverDecision.BLOCKED, policy.evaluate(ctx));
  }

  @Test
  void cooldownBlocksFailover() {
    FailoverContext ctx =
        new FailoverContext(
            "eu",
            "eu",
            "us",
            HealthStatus.UNHEALTHY,
            HealthStatus.HEALTHY,
            5,
            2,
            0,
            Duration.ofSeconds(30),
            null);
    assertEquals(FailoverDecision.BLOCKED, policy.evaluate(ctx));
  }

  @Test
  void autoFailbackDisabled_doesNotReturnToPrimaryWhenOnStandby() {
    FailoverContext ctx = onStandbyAfterFailover(HealthStatus.HEALTHY, 0);

    assertEquals(FailoverDecision.NO_ACTION, policy.evaluate(ctx));
  }

  @Test
  void autoFailbackEnabled_returnsToPrimaryWhenStandbyActiveAndHealthy() {
    JdbcThresholdFailoverPolicy failbackPolicy =
        JdbcThresholdFailoverPolicy.builder()
            .consecutiveActiveProbeFailures(3)
            .cooldownAfterSwitch(Duration.ofMinutes(5))
            .allowAutoFailback(true)
            .build();

    FailoverContext ctx = onStandbyAfterFailover(HealthStatus.HEALTHY, 0);

    assertEquals(FailoverDecision.FAILBACK_TO_PRIMARY, failbackPolicy.evaluate(ctx));
  }

  @Test
  void autoFailbackEnabled_blockedDuringCooldown() {
    JdbcThresholdFailoverPolicy failbackPolicy =
        JdbcThresholdFailoverPolicy.builder()
            .cooldownAfterSwitch(Duration.ofMinutes(5))
            .allowAutoFailback(true)
            .build();

    FailoverContext ctx = onStandbyAfterFailover(HealthStatus.HEALTHY, 0, Duration.ofSeconds(30));

    assertEquals(FailoverDecision.BLOCKED, failbackPolicy.evaluate(ctx));
  }

  @Test
  void autoFailbackEnabled_doesNotReturnWhenActiveProbeFailuresAccumulating() {
    JdbcThresholdFailoverPolicy failbackPolicy =
        JdbcThresholdFailoverPolicy.builder()
            .consecutiveActiveProbeFailures(3)
            .cooldownAfterSwitch(Duration.ofMinutes(5))
            .allowAutoFailback(true)
            .build();

    FailoverContext ctx = onStandbyAfterFailover(HealthStatus.UNHEALTHY, 2);

    assertEquals(FailoverDecision.NO_ACTION, failbackPolicy.evaluate(ctx));
  }

  @Test
  void autoFailbackEnabled_doesNotApplyWhenAlreadyOnPrimary() {
    JdbcThresholdFailoverPolicy failbackPolicy =
        JdbcThresholdFailoverPolicy.builder().allowAutoFailback(true).build();

    FailoverContext ctx =
        new FailoverContext(
            "eu",
            "eu",
            "us",
            HealthStatus.HEALTHY,
            HealthStatus.HEALTHY,
            0,
            2,
            0,
            Duration.ofHours(1),
            null);

    assertEquals(FailoverDecision.NO_ACTION, failbackPolicy.evaluate(ctx));
  }

  /** Active on standby universe {@code us} after prior failover from primary {@code eu}. */
  private static FailoverContext onStandbyAfterFailover(
      HealthStatus activeProbeStatus, int consecutiveActiveProbeFailures) {
    return onStandbyAfterFailover(
        activeProbeStatus, consecutiveActiveProbeFailures, Duration.ofHours(1));
  }

  private static FailoverContext onStandbyAfterFailover(
      HealthStatus activeProbeStatus,
      int consecutiveActiveProbeFailures,
      Duration timeSinceLastSwitch) {
    return new FailoverContext(
        "us",
        "eu",
        "us",
        activeProbeStatus,
        HealthStatus.HEALTHY,
        consecutiveActiveProbeFailures,
        2,
        0,
        timeSinceLastSwitch,
        null);
  }
}
