package com.universe.jdbc.failover.hikari;

import java.time.Duration;
import java.util.Optional;

/** HikariCP settings applied when building a pool from a JDBC URI. */
public final class PoolConfig {
  private final int maximumPoolSize;
  private final Duration connectionTimeout;
  private final Duration maxLifetime;
  private final String username;
  private final String password;
  private final String connectionInitSql;
  private final String driverClassName;

  private PoolConfig(Builder builder) {
    this.maximumPoolSize = builder.maximumPoolSize;
    this.connectionTimeout = builder.connectionTimeout;
    this.maxLifetime = builder.maxLifetime;
    this.username = builder.username;
    this.password = builder.password;
    this.connectionInitSql = builder.connectionInitSql;
    this.driverClassName = builder.driverClassName;
  }

  public static Builder builder() {
    return new Builder();
  }

  public int maximumPoolSize() {
    return maximumPoolSize;
  }

  public Duration connectionTimeout() {
    return connectionTimeout;
  }

  public Duration maxLifetime() {
    return maxLifetime;
  }

  public Optional<String> username() {
    return Optional.ofNullable(username);
  }

  public Optional<String> password() {
    return Optional.ofNullable(password);
  }

  public Optional<String> connectionInitSql() {
    return Optional.ofNullable(connectionInitSql);
  }

  public Optional<String> driverClassName() {
    return Optional.ofNullable(driverClassName);
  }

  public static final class Builder {
    private int maximumPoolSize = 10;
    private Duration connectionTimeout = Duration.ofSeconds(10);
    private Duration maxLifetime = Duration.ofMinutes(30);
    private String username;
    private String password;
    private String connectionInitSql;
    private String driverClassName;

    public Builder maximumPoolSize(int value) {
      this.maximumPoolSize = value;
      return this;
    }

    public Builder connectionTimeout(Duration value) {
      this.connectionTimeout = value;
      return this;
    }

    public Builder maxLifetime(Duration value) {
      this.maxLifetime = value;
      return this;
    }

    public Builder username(String value) {
      this.username = value;
      return this;
    }

    public Builder password(String value) {
      this.password = value;
      return this;
    }

    public Builder connectionInitSql(String value) {
      this.connectionInitSql = value;
      return this;
    }

    public Builder driverClassName(String value) {
      this.driverClassName = value;
      return this;
    }

    public PoolConfig build() {
      return new PoolConfig(this);
    }
  }
}
