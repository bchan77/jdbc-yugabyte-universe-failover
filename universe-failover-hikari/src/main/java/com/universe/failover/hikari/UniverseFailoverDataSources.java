package com.universe.jdbc.failover.hikari;

import com.universe.jdbc.failover.FailoverPolicy;
import com.universe.jdbc.failover.UniverseFailoverDataSource;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Convenience builder that supports {@link UniverseFailoverDataSource} with either pre-built
 * pools or per-universe JDBC URIs.
 */
public final class UniverseFailoverDataSources {
  private final UniverseFailoverDataSource.Builder delegate =
      UniverseFailoverDataSource.builder();
  private PoolConfig defaultPoolConfig = PoolConfig.builder().build();

  private UniverseFailoverDataSources() {}

  public static UniverseFailoverDataSources builder() {
    return new UniverseFailoverDataSources();
  }

  public UniverseFailoverDataSources defaultPoolConfig(PoolConfig config) {
    this.defaultPoolConfig = Objects.requireNonNull(config);
    return this;
  }

  public UniverseFailoverDataSources primary(String id, DataSource dataSource) {
    delegate.primary(id, dataSource);
    return this;
  }

  public UniverseFailoverDataSources standby(String id, DataSource dataSource) {
    delegate.standby(id, dataSource);
    return this;
  }

  public UniverseFailoverDataSources primaryUri(String id, String jdbcUrl) {
    return primaryUri(id, jdbcUrl, defaultPoolConfig);
  }

  public UniverseFailoverDataSources primaryUri(String id, String jdbcUrl, PoolConfig poolConfig) {
    HikariDataSource pool = HikariUniversePoolFactory.create("universe-" + id + "-primary", jdbcUrl, poolConfig);
    delegate.registerOwnedDataSource(pool);
    return primary(id, pool);
  }

  public UniverseFailoverDataSources standbyUri(String id, String jdbcUrl) {
    return standbyUri(id, jdbcUrl, defaultPoolConfig);
  }

  public UniverseFailoverDataSources standbyUri(String id, String jdbcUrl, PoolConfig poolConfig) {
    HikariDataSource pool = HikariUniversePoolFactory.create("universe-" + id + "-standby", jdbcUrl, poolConfig);
    delegate.registerOwnedDataSource(pool);
    return standby(id, pool);
  }

  public UniverseFailoverDataSources failoverPolicy(FailoverPolicy policy) {
    delegate.failoverPolicy(policy);
    return this;
  }

  public UniverseFailoverDataSources probeInterval(Duration interval) {
    delegate.probeInterval(interval);
    return this;
  }

  public UniverseFailoverDataSources probeQueryTimeout(Duration timeout) {
    delegate.probeQueryTimeout(timeout);
    return this;
  }

  public UniverseFailoverDataSources enableBackgroundProbes(boolean enable) {
    delegate.enableBackgroundProbes(enable);
    return this;
  }

  public UniverseFailoverDataSource build() {
    return delegate.build();
  }
}
