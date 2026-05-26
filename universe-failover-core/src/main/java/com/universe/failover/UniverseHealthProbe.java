package com.universe.jdbc.failover;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runs {@code SELECT 1} against a universe {@link DataSource} with a query timeout. */
public final class UniverseHealthProbe {
  private static final Logger LOG = LoggerFactory.getLogger(UniverseHealthProbe.class);

  private final Duration queryTimeout;

  public UniverseHealthProbe(Duration queryTimeout) {
    this.queryTimeout = queryTimeout;
    if (LOG.isDebugEnabled()) {
      LOG.debug("UniverseHealthProbe configured: queryTimeout={}", queryTimeout);
    }
  }

  public HealthStatus probe(String universeId, DataSource dataSource) {
    Instant start = Instant.now();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      int timeoutSeconds = Math.max(1, (int) queryTimeout.getSeconds());
      statement.setQueryTimeout(timeoutSeconds);
      try (ResultSet rs = statement.executeQuery("SELECT 1")) {
        HealthStatus status = rs.next() ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY;
        FailoverLog.logProbeResult(
            LOG, universeId, status, Duration.between(start, Instant.now()), false);
        return status;
      }
    } catch (SQLException ex) {
      Duration elapsed = Duration.between(start, Instant.now());
      FailoverLog.logProbeResult(LOG, universeId, HealthStatus.UNHEALTHY, elapsed, false);
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "Health probe SQL failure for universe [{}]: sqlState={} message={}",
            universeId,
            ex.getSQLState(),
            ex.getMessage());
      }
      if (LOG.isTraceEnabled()) {
        LOG.trace("Health probe exception for universe [{}]", universeId, ex);
      }
      return HealthStatus.UNHEALTHY;
    }
  }

  /**
   * Same as {@link #probe(String, DataSource)} but tags logs as active vs standby in the probe
   * cycle.
   */
  HealthStatus probeInCycle(String universeId, DataSource dataSource, boolean activeUniverse) {
    Instant start = Instant.now();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      int timeoutSeconds = Math.max(1, (int) queryTimeout.getSeconds());
      statement.setQueryTimeout(timeoutSeconds);
      try (ResultSet rs = statement.executeQuery("SELECT 1")) {
        HealthStatus status = rs.next() ? HealthStatus.HEALTHY : HealthStatus.UNHEALTHY;
        FailoverLog.logProbeResult(
            LOG, universeId, status, Duration.between(start, Instant.now()), activeUniverse);
        return status;
      }
    } catch (SQLException ex) {
      Duration elapsed = Duration.between(start, Instant.now());
      FailoverLog.logProbeResult(LOG, universeId, HealthStatus.UNHEALTHY, elapsed, activeUniverse);
      if (LOG.isDebugEnabled()) {
        LOG.debug(
            "Health probe SQL failure for {} universe [{}]: sqlState={} message={}",
            activeUniverse ? "active" : "standby",
            universeId,
            ex.getSQLState(),
            ex.getMessage());
      }
      if (LOG.isTraceEnabled()) {
        LOG.trace(
            "Health probe exception for {} universe [{}]",
            activeUniverse ? "active" : "standby",
            universeId,
            ex);
      }
      return HealthStatus.UNHEALTHY;
    }
  }
}
