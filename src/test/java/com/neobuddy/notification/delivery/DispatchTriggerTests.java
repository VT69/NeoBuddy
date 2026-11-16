package com.neobuddy.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** When a triggered dispatch runs, and how often, without a database. */
class DispatchTriggerTests {

  private static final int BATCH = 50;

  private final DeliveryDispatcher dispatcher = mock(DeliveryDispatcher.class);

  /** Runs nothing by itself; tests decide when the queued run happens. */
  private final List<Runnable> queued = new ArrayList<>();

  private final Executor manual = queued::add;

  private static DeliveryProperties properties(boolean dispatchOnCommit) {
    return new DeliveryProperties(
        true,
        Duration.ofMinutes(2),
        BATCH,
        6,
        Duration.ofMinutes(1),
        Duration.ofMinutes(5),
        dispatchOnCommit);
  }

  private DispatchTrigger trigger(boolean dispatchOnCommit) {
    return new DispatchTrigger(dispatcher, properties(dispatchOnCommit), manual);
  }

  private void runQueued() {
    while (!queued.isEmpty()) {
      queued.removeFirst().run();
    }
  }

  @AfterEach
  void endTransaction() {
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.clearSynchronization();
    }
  }

  /** Pretends to be inside a transaction and returns what a commit or rollback would call. */
  private static List<TransactionSynchronization> inTransaction(Runnable work) {
    TransactionSynchronizationManager.initSynchronization();
    work.run();
    List<TransactionSynchronization> registered =
        TransactionSynchronizationManager.getSynchronizations();
    TransactionSynchronizationManager.clearSynchronization();
    return registered;
  }

  @Test
  void switchedOffItDoesNothing() {
    DispatchTrigger trigger = trigger(false);

    List<TransactionSynchronization> registered = inTransaction(trigger::afterCommit);
    trigger.afterCommit();

    assertThat(registered).isEmpty();
    assertThat(queued).isEmpty();
  }

  @Test
  void dispatchesOnlyAfterTheTransactionCommits() {
    DispatchTrigger trigger = trigger(true);

    List<TransactionSynchronization> registered = inTransaction(trigger::afterCommit);

    assertThat(queued).as("nothing runs inside the transaction").isEmpty();
    assertThat(registered).hasSize(1);
    registered.getFirst().afterCommit();
    runQueued();
    verify(dispatcher).dispatchDue();
  }

  @Test
  void aRolledBackTransactionDispatchesNothing() {
    DispatchTrigger trigger = trigger(true);

    List<TransactionSynchronization> registered = inTransaction(trigger::afterCommit);
    registered.getFirst().afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
    runQueued();

    verify(dispatcher, never()).dispatchDue();
  }

  @Test
  void outsideATransactionItDispatchesAtOnce() {
    trigger(true).afterCommit();
    runQueued();

    verify(dispatcher).dispatchDue();
  }

  @Test
  void keepsDispatchingWhileBatchesComeBackFull() {
    when(dispatcher.dispatchDue()).thenReturn(BATCH, BATCH, 3);

    trigger(true).afterCommit();
    runQueued();

    verify(dispatcher, times(3)).dispatchDue();
  }

  @Test
  void stopsAfterTheMaximumNumberOfBatches() {
    when(dispatcher.dispatchDue()).thenReturn(BATCH);

    trigger(true).afterCommit();
    runQueued();

    verify(dispatcher, times(DispatchTrigger.MAX_BATCHES_PER_RUN)).dispatchDue();
  }

  @Test
  void triggersWhileARunIsPendingShareThatRun() {
    DispatchTrigger trigger = trigger(true);

    trigger.afterCommit();
    trigger.afterCommit();
    trigger.afterCommit();

    assertThat(queued).hasSize(1);
    runQueued();
    verify(dispatcher, times(1)).dispatchDue();
  }

  @Test
  void aTriggerDuringARunMakesItGoRoundOnceMore() {
    DispatchTrigger trigger = trigger(true);
    when(dispatcher.dispatchDue())
        .thenAnswer(invocation -> {
          trigger.afterCommit();
          return 0;
        })
        .thenReturn(0);

    trigger.afterCommit();
    runQueued();

    verify(dispatcher, times(2)).dispatchDue();
    assertThat(queued).isEmpty();
  }

  @Test
  void aFailedRunIsLeftToTheSweepAndLaterTriggersStillRun() {
    DispatchTrigger trigger = trigger(true);
    when(dispatcher.dispatchDue()).thenThrow(new IllegalStateException("boom")).thenReturn(0);

    trigger.afterCommit();
    runQueued();
    trigger.afterCommit();
    runQueued();

    verify(dispatcher, times(2)).dispatchDue();
  }

  @Test
  void anExecutorThatRefusesDoesNotBlockLaterTriggers() {
    List<Runnable> accepted = new ArrayList<>();
    boolean[] refuse = {true};
    DispatchTrigger trigger =
        new DispatchTrigger(
            dispatcher,
            properties(true),
            task -> {
              if (refuse[0]) {
                throw new IllegalStateException("shutting down");
              }
              accepted.add(task);
            });

    trigger.afterCommit();
    refuse[0] = false;
    trigger.afterCommit();

    assertThat(accepted).hasSize(1);
  }
}
