package com.universe.jdbc.failover;

import java.util.Objects;
import javax.sql.DataSource;

/** One Yugabyte universe represented by its own {@link DataSource} (typically a connection pool). */
public final class UniverseTarget {
  private final String id;
  private final DataSource dataSource;
  private final boolean primary;
  private final String connectionSummary;

  public UniverseTarget(String id, DataSource dataSource, boolean primary) {
    this(id, dataSource, primary, DataSourceDiagnostics.describe(dataSource));
  }

  public UniverseTarget(
      String id, DataSource dataSource, boolean primary, String connectionSummary) {
    this.id = Objects.requireNonNull(id, "id");
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    this.primary = primary;
    this.connectionSummary =
        connectionSummary == null || connectionSummary.isBlank()
            ? DataSourceDiagnostics.describe(dataSource)
            : connectionSummary;
  }

  public String id() {
    return id;
  }

  public DataSource dataSource() {
    return dataSource;
  }

  public boolean primary() {
    return primary;
  }

  /** Sanitized pool / JDBC details for troubleshooting logs. */
  public String connectionSummary() {
    return connectionSummary;
  }
}
