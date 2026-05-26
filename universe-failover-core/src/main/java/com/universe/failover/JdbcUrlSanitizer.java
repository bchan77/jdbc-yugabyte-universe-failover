package com.universe.jdbc.failover;

import java.util.regex.Pattern;

/** Redacts credentials from JDBC URLs before logging. */
final class JdbcUrlSanitizer {
  private static final Pattern PASSWORD_PARAM =
      Pattern.compile("(?i)([?&]password=)[^&]*");
  private static final Pattern USERINFO =
      Pattern.compile("://([^/@]+):([^@]+)@");

  private JdbcUrlSanitizer() {}

  static String sanitize(String jdbcUrl) {
    if (jdbcUrl == null || jdbcUrl.isBlank()) {
      return jdbcUrl;
    }
    String redacted = PASSWORD_PARAM.matcher(jdbcUrl).replaceAll("$1***");
    redacted = USERINFO.matcher(redacted).replaceAll("://$1:***@");
    return redacted;
  }
}
