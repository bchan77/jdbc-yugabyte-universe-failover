package com.universe.jdbc.failover.hikari;

import com.universe.jdbc.failover.DataSourceDiagnostics;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Creates a HikariCP pool for one Yugabyte SmartDriver JDBC URL (single universe). */
public final class HikariUniversePoolFactory {

  private static final Logger LOG = LoggerFactory.getLogger(HikariUniversePoolFactory.class);

  private HikariUniversePoolFactory() {}

  public static HikariDataSource create(String poolName, String jdbcUrl, PoolConfig poolConfig) {
    Objects.requireNonNull(jdbcUrl, "jdbcUrl");
    Objects.requireNonNull(poolConfig, "poolConfig");

    HikariConfig config = new HikariConfig();
    config.setPoolName(poolName);
    config.setJdbcUrl(jdbcUrl);
    config.setMaximumPoolSize(poolConfig.maximumPoolSize());
    config.setConnectionTimeout(poolConfig.connectionTimeout().toMillis());
    config.setMaxLifetime(poolConfig.maxLifetime().toMillis());
    poolConfig
        .username()
        .ifPresent(
            user -> {
              config.setUsername(user);
              poolConfig.password().ifPresent(config::setPassword);
            });
    poolConfig.connectionInitSql().ifPresent(config::setConnectionInitSql);
    poolConfig.driverClassName().ifPresent(config::setDriverClassName);

    if (LOG.isInfoEnabled()) {
      LOG.info(
          "Creating Hikari pool name={} maxPool={} connTimeoutMs={} maxLifetimeMs={} driver={}",
          poolName,
          poolConfig.maximumPoolSize(),
          poolConfig.connectionTimeout().toMillis(),
          poolConfig.maxLifetime().toMillis(),
          poolConfig.driverClassName().orElse("(from URL)"));
    }
    if (LOG.isDebugEnabled()) {
      LOG.debug(
          "Hikari pool name={} jdbcUrl={} connectionInitSql={}",
          poolName,
          DataSourceDiagnostics.sanitizeJdbcUrl(jdbcUrl),
          poolConfig.connectionInitSql().orElse("(none)"));
    }

    HikariDataSource dataSource = new HikariDataSource(config);
    if (LOG.isDebugEnabled()) {
      LOG.debug("Hikari pool ready: {}", DataSourceDiagnostics.describe(dataSource));
    }
    return dataSource;
  }

  public static DataSource createDataSource(String poolName, String jdbcUrl, PoolConfig poolConfig) {
    return create(poolName, jdbcUrl, poolConfig);
  }
}
