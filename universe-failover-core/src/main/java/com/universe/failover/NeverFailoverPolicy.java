package com.universe.jdbc.failover;

/** Manual failover only; automatic evaluation always returns {@link FailoverDecision#NO_ACTION}. */
public final class NeverFailoverPolicy implements FailoverPolicy {

  public static final NeverFailoverPolicy INSTANCE = new NeverFailoverPolicy();

  private NeverFailoverPolicy() {}

  @Override
  public FailoverDecision evaluate(FailoverContext context) {
    return FailoverDecision.NO_ACTION;
  }
}
