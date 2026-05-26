package com.universe.jdbc.failover;

import java.sql.SQLException;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JDBC-only failover policy. Fails over only after sustained active-universe probe failures and
 * optional standby health confirmation. Does not trigger on a single transient connection error.
 */
public final class JdbcThresholdFailoverPolicy implements FailoverPolicy {

  private static final Logger LOG = LoggerFactory.getLogger(JdbcThresholdFailoverPolicy.class);

  private final int consecutiveActiveProbeFailures;
  private final int minStandbyProbeSuccesses;
  private final Duration cooldownAfterSwitch;
  private final boolean requireStandbyHealthy;
  private final boolean allowAutoFailback;
  private final int minRecentConnectionFailures;

  private JdbcThresholdFailoverPolicy(Builder builder) {
    this.consecutiveActiveProbeFailures = builder.consecutiveActiveProbeFailures;
    this.minStandbyProbeSuccesses = builder.minStandbyProbeSuccesses;
    this.cooldownAfterSwitch = builder.cooldownAfterSwitch;
    this.requireStandbyHealthy = builder.requireStandbyHealthy;
    this.allowAutoFailback = builder.allowAutoFailback;
    this.minRecentConnectionFailures = builder.minRecentConnectionFailures;
  }

  public static Builder builder() {
    return new Builder();
  }

  @Override
  public FailoverDecision evaluate(FailoverContext context) {
    PolicyEvaluation evaluation = evaluateDetailed(context);
    FailoverLog.logPolicyEvaluation(LOG, evaluation.decision(), evaluation.reason(), context);
    return evaluation.decision();
  }

  /** Same rules as {@link #evaluate(FailoverContext)} with an explanatory reason string. */
  public PolicyEvaluation evaluateDetailed(FailoverContext context) {
    if (context.timeSinceLastSwitch().compareTo(cooldownAfterSwitch) < 0) {
      return new PolicyEvaluation(
          FailoverDecision.BLOCKED,
          "cooldown active ("
              + context.timeSinceLastSwitch()
              + " since last switch, required "
              + cooldownAfterSwitch
              + ")");
    }

    if (allowAutoFailback
        && !context.activeIsPrimary()
        && context.activeProbeStatus() == HealthStatus.HEALTHY
        && context.consecutiveActiveProbeFailures() == 0) {
      return new PolicyEvaluation(
          FailoverDecision.FAILBACK_TO_PRIMARY, "standby healthy and auto-failback enabled");
    }

    boolean activeUnhealthy =
        context.activeProbeStatus() == HealthStatus.UNHEALTHY
            && context.consecutiveActiveProbeFailures() >= consecutiveActiveProbeFailures;

    boolean connectionPressure =
        minRecentConnectionFailures > 0
            && context.recentActiveConnectionFailures() >= minRecentConnectionFailures
            && context.activeProbeStatus() != HealthStatus.HEALTHY;

    if (!activeUnhealthy && !connectionPressure) {
      if (context.consecutiveActiveProbeFailures() > 0) {
        return new PolicyEvaluation(
            FailoverDecision.NO_ACTION,
            "active probe failures "
                + context.consecutiveActiveProbeFailures()
                + "/"
                + consecutiveActiveProbeFailures
                + " (threshold not met)");
      }
      return new PolicyEvaluation(
          FailoverDecision.NO_ACTION, "active universe healthy or insufficient failure signals");
    }

    if (requireStandbyHealthy) {
      boolean standbyReady =
          context.standbyProbeStatus() == HealthStatus.HEALTHY
              && context.consecutiveStandbyProbeSuccesses() >= minStandbyProbeSuccesses;
      if (!standbyReady) {
        return new PolicyEvaluation(
            FailoverDecision.BLOCKED,
            "standby not ready (probe="
                + context.standbyProbeStatus()
                + ", successes="
                + context.consecutiveStandbyProbeSuccesses()
                + "/"
                + minStandbyProbeSuccesses
                + ")");
      }
    }

    if (context.activeIsPrimary()) {
      String trigger =
          activeUnhealthy
              ? "active probes failed "
                  + context.consecutiveActiveProbeFailures()
                  + " times (threshold "
                  + consecutiveActiveProbeFailures
                  + ")"
              : "connection failures "
                  + context.recentActiveConnectionFailures()
                  + " with unhealthy active probe";
      return new PolicyEvaluation(FailoverDecision.FAILOVER_TO_STANDBY, trigger);
    }

    return new PolicyEvaluation(
        FailoverDecision.NO_ACTION, "active universe already standby; no further switch");
  }

  @Override
  public void onConnectionFailure(String universeId, Throwable failure) {
    if (LOG.isDebugEnabled()) {
      String detail = failure.getMessage();
      if (failure instanceof SQLException sql) {
        detail = "sqlState=" + sql.getSQLState() + " message=" + sql.getMessage();
      }
      LOG.debug("Connection failure recorded for universe [{}]: {}", universeId, detail);
    }
  }

  public static final class Builder {
    private int consecutiveActiveProbeFailures = 3;
    private int minStandbyProbeSuccesses = 1;
    private Duration cooldownAfterSwitch = Duration.ofMinutes(5);
    private boolean requireStandbyHealthy = true;
    private boolean allowAutoFailback = false;
    private int minRecentConnectionFailures = 0;

    public Builder consecutiveActiveProbeFailures(int value) {
      this.consecutiveActiveProbeFailures = value;
      return this;
    }

    public Builder minStandbyProbeSuccesses(int value) {
      this.minStandbyProbeSuccesses = value;
      return this;
    }

    public Builder cooldownAfterSwitch(Duration value) {
      this.cooldownAfterSwitch = Objects.requireNonNull(value);
      return this;
    }

    public Builder requireStandbyHealthy(boolean value) {
      this.requireStandbyHealthy = value;
      return this;
    }

    public Builder allowAutoFailback(boolean value) {
      this.allowAutoFailback = value;
      return this;
    }

    /**
     * When {@code > 0}, connection failures on {@code getConnection()} contribute to failover
     * only together with unhealthy probes (not alone).
     */
    public Builder minRecentConnectionFailures(int value) {
      this.minRecentConnectionFailures = value;
      return this;
    }

    public JdbcThresholdFailoverPolicy build() {
      if (consecutiveActiveProbeFailures < 1) {
        throw new IllegalArgumentException("consecutiveActiveProbeFailures must be >= 1");
      }
      if (minStandbyProbeSuccesses < 1) {
        throw new IllegalArgumentException("minStandbyProbeSuccesses must be >= 1");
      }
      if (LOG.isInfoEnabled()) {
        LOG.info(
            "JdbcThresholdFailoverPolicy configured: consecutiveActiveProbeFailures={} "
                + "minStandbyProbeSuccesses={} cooldownAfterSwitch={} requireStandbyHealthy={} "
                + "allowAutoFailback={} minRecentConnectionFailures={}",
            consecutiveActiveProbeFailures,
            minStandbyProbeSuccesses,
            cooldownAfterSwitch,
            requireStandbyHealthy,
            allowAutoFailback,
            minRecentConnectionFailures);
      }
      return new JdbcThresholdFailoverPolicy(this);
    }
  }
}
