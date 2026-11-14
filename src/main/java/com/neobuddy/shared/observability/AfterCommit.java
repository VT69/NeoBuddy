package com.neobuddy.shared.observability;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Runs a side effect, such as counting a metric, only once the current transaction commits. */
public final class AfterCommit {

  private AfterCommit() {}

  /** Runs now when there is no transaction. */
  public static void run(Runnable action) {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              action.run();
            }
          });
    } else {
      action.run();
    }
  }
}
