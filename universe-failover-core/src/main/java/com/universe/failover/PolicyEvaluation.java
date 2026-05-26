package com.universe.jdbc.failover;

/** Policy outcome including a human-readable reason for logs. */
public record PolicyEvaluation(FailoverDecision decision, String reason) {}
