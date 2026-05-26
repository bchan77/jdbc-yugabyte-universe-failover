package com.universe.jdbc.failover;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;

/**
 * Routes {@link Connection} requests to the active Yugabyte universe {@link DataSource}. Fails over
 * between a primary and standby universe using a pluggable {@link FailoverPolicy}.
 */
public final class UniverseFailoverDataSource implements DataSource, AutoCloseable {

  private static final org.slf4j.Logger LOG =
      LoggerFactory.getLogger(UniverseFailoverDataSource.class);

  private final UniverseTarget primary;
  private final UniverseTarget standby;
  private final FailoverPolicy failoverPolicy;
  private final UniverseHealthProbe healthProbe;
  private final Duration probeInterval;
  private final UniverseFailoverMetrics metrics;
  private final List<FailoverListener> listeners = new CopyOnWriteArrayList<>();
  private final List<DataSource> ownedDataSources;
  private final AtomicReference<String> activeUniverseId;
  private final ScheduledExecutorService probeScheduler;

  private volatile boolean closed;
  private volatile Throwable lastConnectionFailure;

  private UniverseFailoverDataSource(Builder builder) {
    this.primary = builder.primary;
    this.standby = builder.standby;
    this.failoverPolicy = builder.failoverPolicy;
    this.healthProbe = new UniverseHealthProbe(builder.probeQueryTimeout);
    this.probeInterval = builder.probeInterval;
    this.metrics = new UniverseFailoverMetrics();
    this.ownedDataSources = List.copyOf(builder.ownedDataSources);
    this.activeUniverseId = new AtomicReference<>(primary.id());
    this.metrics.setActiveUniverseId(primary.id());
    this.listeners.addAll(builder.listeners);

    if (builder.enableBackgroundProbes) {
      this.probeScheduler =
          Executors.newSingleThreadScheduledExecutor(
              r -> {
                Thread t = new Thread(r, "universe-failover-probe");
                t.setDaemon(true);
                return t;
              });
      this.probeScheduler.scheduleAtFixedRate(
          this::runProbeCycleSafe,
          probeInterval.toMillis(),
          probeInterval.toMillis(),
          TimeUnit.MILLISECONDS);
    } else {
      this.probeScheduler = null;
    }

    FailoverLog.logStartup(
        LOG,
        primary,
        standby,
        probeInterval,
        builder.probeQueryTimeout,
        builder.enableBackgroundProbes,
        failoverPolicy);
    if (!builder.enableBackgroundProbes && LOG.isWarnEnabled()) {
      LOG.warn(
          "Background health probes are disabled; failover relies on getConnection() failures only");
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  /** Active universe id used for routing. */
  public String getActiveUniverseId() {
    return activeUniverseId.get();
  }

  public UniverseFailoverMetrics metrics() {
    return metrics;
  }

  public void addFailoverListener(FailoverListener listener) {
    listeners.add(Objects.requireNonNull(listener));
  }

  /**
   * Manually switch active universe. Useful for tests and operator-driven DR cutover.
   */
  public synchronized void switchTo(String universeId) {
    ensureOpen();
    FailoverLog.logManualSwitch(LOG, activeUniverseId.get(), universeId);
    if (universeId.equals(primary.id())) {
      applySwitch(primary.id(), FailoverDecision.FAILBACK_TO_PRIMARY);
    } else if (universeId.equals(standby.id())) {
      applySwitch(standby.id(), FailoverDecision.FAILOVER_TO_STANDBY);
    } else {
      throw new IllegalArgumentException(
          "Unknown universe id: " + universeId + " (known: " + primary.id() + ", " + standby.id() + ")");
    }
  }

  @Override
  public Connection getConnection() throws SQLException {
    ensureOpen();
    String activeId = activeUniverseId.get();
    FailoverLog.logRoutedConnection(LOG, activeId);
    UniverseTarget target = targetFor(activeId);
    try {
      Connection connection = target.dataSource().getConnection();
      metrics.resetRecentConnectionFailures();
      return connection;
    } catch (SQLException ex) {
      lastConnectionFailure = ex;
      metrics.recordActiveConnectionFailure();
      FailoverLog.logConnectionFailure(LOG, activeId, ex);
      failoverPolicy.onConnectionFailure(activeId, ex);
      maybeEvaluateFailover();
      throw ex;
    }
  }

  @Override
  public Connection getConnection(String username, String password) throws SQLException {
    ensureOpen();
    String activeId = activeUniverseId.get();
    FailoverLog.logRoutedConnection(LOG, activeId);
    UniverseTarget target = targetFor(activeId);
    try {
      Connection connection = target.dataSource().getConnection(username, password);
      metrics.resetRecentConnectionFailures();
      return connection;
    } catch (SQLException ex) {
      lastConnectionFailure = ex;
      metrics.recordActiveConnectionFailure();
      FailoverLog.logConnectionFailure(LOG, activeId, ex);
      failoverPolicy.onConnectionFailure(activeId, ex);
      maybeEvaluateFailover();
      throw ex;
    }
  }

  private void runProbeCycleSafe() {
    try {
      runProbeCycle();
    } catch (RuntimeException ex) {
      LOG.warn("Probe cycle failed: {}", ex.getMessage(), ex);
    }
  }

  private void runProbeCycle() {
    if (closed) {
      return;
    }
    String activeId = activeUniverseId.get();
    UniverseTarget active = targetFor(activeId);
    UniverseTarget inactive = inactiveTarget(activeId);

    metrics.recordActiveProbe(
        healthProbe.probeInCycle(active.id(), active.dataSource(), true));
    metrics.recordStandbyProbe(
        healthProbe.probeInCycle(inactive.id(), inactive.dataSource(), false));
    FailoverContext context = buildContext();
    FailoverLog.logProbeCycleSummary(LOG, context);
    maybeEvaluateFailover(context);
  }

  private void maybeEvaluateFailover() {
    maybeEvaluateFailover(buildContext());
  }

  private void maybeEvaluateFailover(FailoverContext context) {
    FailoverDecision decision = failoverPolicy.evaluate(context);
    switch (decision) {
      case NO_ACTION, BLOCKED -> {}
      case FAILOVER_TO_STANDBY -> applySwitch(standby.id(), decision);
      case FAILBACK_TO_PRIMARY -> applySwitch(primary.id(), decision);
    }
  }

  private FailoverContext buildContext() {
    Instant lastSwitch = metrics.lastSwitchAt();
    Duration sinceSwitch =
        lastSwitch == null
            ? Duration.ofDays(365)
            : Duration.between(lastSwitch, Instant.now());

    return new FailoverContext(
        activeUniverseId.get(),
        primary.id(),
        standby.id(),
        metrics.activeProbeStatus(),
        metrics.standbyProbeStatus(),
        metrics.consecutiveActiveProbeFailures(),
        metrics.consecutiveStandbyProbeSuccesses(),
        metrics.recentActiveConnectionFailures(),
        sinceSwitch,
        lastConnectionFailure);
  }

  private synchronized void applySwitch(String newActiveId, FailoverDecision decision) {
    String previous = activeUniverseId.get();
    if (previous.equals(newActiveId)) {
      return;
    }
    activeUniverseId.set(newActiveId);
    metrics.setActiveUniverseId(newActiveId);
    metrics.recordSwitch(Instant.now());
    FailoverLog.logSwitchApplied(LOG, previous, newActiveId, decision);
    Instant switchedAt = Instant.now();
    for (FailoverListener listener : listeners) {
      try {
        listener.onFailover(previous, newActiveId, decision, switchedAt);
      } catch (RuntimeException ex) {
        LOG.warn("FailoverListener failed: {}", ex.getMessage(), ex);
      }
    }
  }

  private UniverseTarget targetFor(String id) {
    if (primary.id().equals(id)) {
      return primary;
    }
    if (standby.id().equals(id)) {
      return standby;
    }
    throw new IllegalStateException("Active universe id not registered: " + id);
  }

  private UniverseTarget inactiveTarget(String activeId) {
    return activeId.equals(primary.id()) ? standby : primary;
  }

  private void ensureOpen() {
    if (closed) {
      throw new IllegalStateException("UniverseFailoverDataSource is closed");
    }
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    FailoverLog.logClose(LOG);
    if (probeScheduler != null) {
      probeScheduler.shutdownNow();
    }
    for (DataSource owned : ownedDataSources) {
      if (owned instanceof AutoCloseable closeable) {
        try {
          closeable.close();
        } catch (Exception ex) {
          LOG.warn("Failed to close owned DataSource: {}", ex.getMessage());
        }
      }
    }
  }

  @Override
  public PrintWriter getLogWriter() throws SQLException {
    return targetFor(activeUniverseId.get()).dataSource().getLogWriter();
  }

  @Override
  public void setLogWriter(PrintWriter out) throws SQLException {
    primary.dataSource().setLogWriter(out);
    standby.dataSource().setLogWriter(out);
  }

  @Override
  public void setLoginTimeout(int seconds) throws SQLException {
    primary.dataSource().setLoginTimeout(seconds);
    standby.dataSource().setLoginTimeout(seconds);
  }

  @Override
  public int getLoginTimeout() throws SQLException {
    return targetFor(activeUniverseId.get()).dataSource().getLoginTimeout();
  }

  @Override
  public Logger getParentLogger() throws SQLFeatureNotSupportedException {
    return targetFor(activeUniverseId.get()).dataSource().getParentLogger();
  }

  @Override
  public <T> T unwrap(Class<T> iface) throws SQLException {
    if (iface.isInstance(this)) {
      return iface.cast(this);
    }
    throw new SQLException("Not a wrapper for " + iface.getName());
  }

  @Override
  public boolean isWrapperFor(Class<?> iface) throws SQLException {
    return iface.isInstance(this);
  }

  public static final class Builder {
    private UniverseTarget primary;
    private UniverseTarget standby;
    private FailoverPolicy failoverPolicy = JdbcThresholdFailoverPolicy.builder().build();
    private Duration probeInterval = Duration.ofSeconds(5);
    private Duration probeQueryTimeout = Duration.ofSeconds(3);
    private boolean enableBackgroundProbes = true;
    private final List<DataSource> ownedDataSources = new ArrayList<>();
    private final List<FailoverListener> listeners = new ArrayList<>();

    public Builder primary(String id, DataSource dataSource) {
      this.primary = new UniverseTarget(id, dataSource, true);
      return this;
    }

    public Builder standby(String id, DataSource dataSource) {
      this.standby = new UniverseTarget(id, dataSource, false);
      return this;
    }

    public Builder failoverPolicy(FailoverPolicy policy) {
      this.failoverPolicy = Objects.requireNonNull(policy);
      return this;
    }

    public Builder probeInterval(Duration interval) {
      this.probeInterval = Objects.requireNonNull(interval);
      return this;
    }

    public Builder probeQueryTimeout(Duration timeout) {
      this.probeQueryTimeout = Objects.requireNonNull(timeout);
      return this;
    }

    public Builder enableBackgroundProbes(boolean enable) {
      this.enableBackgroundProbes = enable;
      return this;
    }

    public Builder addFailoverListener(FailoverListener listener) {
      this.listeners.add(Objects.requireNonNull(listener));
      return this;
    }

    /**
     * DataSources created by a helper (for example from {@code primaryUri}) that this routing layer
     * should close on {@link UniverseFailoverDataSource#close()}.
     */
    public Builder registerOwnedDataSource(DataSource dataSource) {
      ownedDataSources.add(Objects.requireNonNull(dataSource));
      return this;
    }

    public UniverseFailoverDataSource build() {
      if (primary == null || standby == null) {
        throw new IllegalStateException("Both primary and standby universes are required");
      }
      if (primary.id().equals(standby.id())) {
        throw new IllegalStateException("Primary and standby universe ids must differ");
      }
      return new UniverseFailoverDataSource(this);
    }
  }
}
