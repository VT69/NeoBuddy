package com.neobuddy.notification.delivery;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs the {@link DeliveryDispatcher} as soon as new deliveries are committed, when {@code
 * neobuddy.delivery.dispatch-on-commit} is true; otherwise does nothing and the scheduled
 * dispatcher picks them up.
 *
 * <p>The run happens after the commit, so it sees the rows, and on its own virtual thread, so the
 * transaction that queued them (an email's processing, a test alert) never waits for any sending.
 * At most one triggered run is active per instance: a trigger that arrives while one is running
 * makes it go round once more instead of starting another. Each run dispatches until a batch comes
 * back less than full, which also sends anything else that was already due.
 *
 * <p>Running next to the scheduled dispatcher, or on another instance, is safe: claiming in the
 * outbox keeps two dispatchers off the same row (see {@link DeliveryStore#claimDue}). If a
 * triggered run fails, the deliveries stay due and the next scheduled sweep sends them.
 */
@Component
class DispatchTrigger {

  private static final Logger log = LoggerFactory.getLogger(DispatchTrigger.class);

  /** A safety stop: a run never claims more than this many batches. */
  static final int MAX_BATCHES_PER_RUN = 20;

  private final DeliveryDispatcher dispatcher;
  private final DeliveryProperties properties;
  private final Executor executor;
  private final AtomicBoolean requested = new AtomicBoolean();
  private final AtomicBoolean running = new AtomicBoolean();

  @Autowired
  DispatchTrigger(DeliveryDispatcher dispatcher, DeliveryProperties properties) {
    this(dispatcher, properties, task -> Thread.ofVirtual().name("delivery-trigger").start(task));
  }

  DispatchTrigger(DeliveryDispatcher dispatcher, DeliveryProperties properties, Executor executor) {
    this.dispatcher = dispatcher;
    this.properties = properties;
    this.executor = executor;
  }

  /**
   * Asks for a dispatch once the current transaction commits; nothing happens if it rolls back.
   * Outside a transaction the dispatch is requested at once.
   */
  void afterCommit() {
    if (!properties.dispatchOnCommit()) {
      return;
    }
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              request();
            }
          });
    } else {
      request();
    }
  }

  private void request() {
    requested.set(true);
    startIfIdle();
  }

  private void startIfIdle() {
    if (running.compareAndSet(false, true)) {
      try {
        executor.execute(this::drain);
      } catch (RuntimeException e) {
        running.set(false);
        log.warn("Could not start a delivery dispatch: {}", e.getClass().getSimpleName());
      }
    }
  }

  private void drain() {
    try {
      while (requested.getAndSet(false)) {
        dispatchUntilIdle();
      }
    } finally {
      running.set(false);
    }
    // A request that came in between the last check and clearing the flag still gets its run.
    if (requested.get()) {
      startIfIdle();
    }
  }

  private void dispatchUntilIdle() {
    try {
      for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
        if (dispatcher.dispatchDue() < properties.batchSize()) {
          return;
        }
      }
    } catch (RuntimeException e) {
      // The class only: a message could carry data. The scheduled sweep tries again.
      log.warn("Triggered delivery dispatch failed: {}", e.getClass().getSimpleName());
    }
  }
}
