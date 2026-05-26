package com.universe.jdbc.failover;

import java.sql.SQLException;
import java.time.Duration;
import org.slf4j.Logger;

/** Structured log helpers for universe failover troubleshooting. */
final class FailoverLog {
  private FailoverLog() {}

  static void logStartup(
      Logger log,
      UniverseTarget primary,
      UniverseTarget standby,
      Duration probeInterval,
      Duration probeQueryTimeout,
      boolean backgroundProbes,
      FailoverPolicy policy) {
    if (!log.isInfoEnabled()) {
      return;
    }
    log.info(
        "Universe failover initialized: initialActive={} primary={} standby={} probeInterval={} "
            + "probeQueryTimeout={} backgroundProbes={} policy={}",
        primary.id(),
        primary.id(),
        standby.id(),
        probeInterval,
        probeQueryTimeout,
        backgroundProbes,
        policy.getClass().getSimpleName());
    log.info("Primary universe [{}]: {}", primary.id(), primary.connectionSummary());
    log.info("Standby universe [{}]: {}", standby.id(), standby.connectionSummary());
  }

  static void logProbeResult(
      Logger log,
      String universeId,
      HealthStatus status,
      Duration elapsed,
      boolean activeUniverse) {
    if (log.isTraceEnabled()) {
      log.trace(
          "Probe {} universe [{}] status={} elapsedMs={}",
          activeUniverse ? "active" : "standby",
          universeId,
          status,
          elapsed.toMillis());
    } else if (status == HealthStatus.UNHEALTHY && log.isDebugEnabled()) {
      log.debug(
          "Probe {} universe [{}] status=UNHEALTHY elapsedMs={}",
          activeUniverse ? "active" : "standby",
          universeId,
          elapsed.toMillis());
    }
  }

  static void logProbeCycleSummary(Logger log, FailoverContext context) {
    if (!log.isDebugEnabled()) {
      return;
    }
    log.debug(
        "Probe cycle summary: active=[{}] activeProbe={} activeProbeFailures={} "
            + "standbyProbe={} standbyProbeSuccesses={} recentConnFailures={} "
            + "sinceLastSwitch={}",
        context.activeUniverseId(),
        context.activeProbeStatus(),
        context.consecutiveActiveProbeFailures(),
        context.standbyProbeStatus(),
        context.consecutiveStandbyProbeSuccesses(),
        context.recentActiveConnectionFailures(),
        context.timeSinceLastSwitch());
  }

  static void logPolicyEvaluation(
      Logger log, FailoverDecision decision, String reason, FailoverContext context) {
    if (decision == FailoverDecision.FAILOVER_TO_STANDBY
        || decision == FailoverDecision.FAILBACK_TO_PRIMARY) {
      log.info("Failover policy decision={} reason={} {}", decision, reason, formatContext(context));
    } else if (decision == FailoverDecision.BLOCKED) {
      log.warn("Failover policy decision=BLOCKED reason={} {}", reason, formatContext(context));
    } else if (log.isDebugEnabled()) {
      if (context.consecutiveActiveProbeFailures() > 0
          || context.recentActiveConnectionFailures() > 0
          || context.activeProbeStatus() == HealthStatus.UNHEALTHY) {
        log.debug(
            "Failover policy decision=NO_ACTION reason={} {}", reason, formatContext(context));
      } else if (log.isTraceEnabled()) {
        log.trace("Failover policy decision=NO_ACTION reason={}", reason);
      }
    }
  }

  static void logConnectionFailure(Logger log, String universeId, SQLException ex) {
    log.warn(
        "getConnection failed for active universe [{}]: sqlState={} errorCode={} message={}",
        universeId,
        ex.getSQLState(),
        ex.getErrorCode(),
        ex.getMessage());
    if (log.isDebugEnabled()) {
      log.debug("getConnection failure details for universe [{}]", universeId, ex);
    }
  }

  static void logRoutedConnection(Logger log, String universeId) {
    if (log.isTraceEnabled()) {
      log.trace("Routing getConnection to universe [{}]", universeId);
    }
  }

  static void logManualSwitch(Logger log, String from, String to) {
    log.info("Manual universe switch requested: {} -> {}", from, to);
  }

  static void logSwitchApplied(Logger log, String from, String to, FailoverDecision decision) {
    log.info("Active universe changed: {} -> {} (decision={})", from, to, decision);
  }

  static void logClose(Logger log) {
    log.info("Universe failover DataSource closed");
  }

  private static String formatContext(FailoverContext context) {
    return String.format(
        "[active=%s activeProbe=%s activeFailures=%d standbyProbe=%s standbySuccesses=%d "
            + "connFailures=%d sinceSwitch=%s]",
        context.activeUniverseId(),
        context.activeProbeStatus(),
        context.consecutiveActiveProbeFailures(),
        context.standbyProbeStatus(),
        context.consecutiveStandbyProbeSuccesses(),
        context.recentActiveConnectionFailures(),
        context.timeSinceLastSwitch());
  }
}
