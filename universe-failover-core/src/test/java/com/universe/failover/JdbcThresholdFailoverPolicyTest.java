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
}
