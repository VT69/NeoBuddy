package com.neobuddy.processing.extract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(20)
class SafeExtractionRunnerTests {

  private final SafeExtractionRunner runner = new SafeExtractionRunner(Duration.ofMillis(300), 2);

  @AfterEach
  void stopWorkers() {
    runner.shutdown();
  }

  @Test
  void returnsTheResultFromAWorkerThread() throws Exception {
    String caller = Thread.currentThread().getName();

    assertThat(runner.run(() -> Thread.currentThread().getName()))
        .startsWith("extraction-")
        .isNotEqualTo(caller);
  }

  @Test
  void extractionExceptionsPassThroughUnchanged() {
    ExtractionException failure = new ExtractionException("Could not read the spreadsheet");

    assertThatThrownBy(() ->
            runner.run(() -> {
              throw failure;
            }))
        .isSameAs(failure);
  }

  @Test
  void otherExceptionsAreWrappedWithoutTheirMessage() {
    assertThatThrownBy(() ->
            runner.run(() -> {
              throw new IllegalStateException("cell value Z9X8C7V6");
            }))
        .isInstanceOf(ExtractionException.class)
        .hasMessage("Extraction failed: IllegalStateException")
        .hasCauseInstanceOf(IllegalStateException.class);
  }

  @Test
  void errorsSuchAsStackOverflowFailOnlyThatFile() throws Exception {
    assertThatThrownBy(() -> runner.run(SafeExtractionRunnerTests::recurseForever))
        .isInstanceOf(ExtractionException.class)
        .hasMessage("Extraction failed: StackOverflowError");

    assertThat(runner.run(() -> "next file")).isEqualTo("next file");
  }

  @Test
  void aSlowFileTimesOutIsInterruptedAndFreesItsSlot() throws Exception {
    AtomicBoolean interrupted = new AtomicBoolean();
    CountDownLatch finished = new CountDownLatch(1);

    long started = System.nanoTime();
    assertThatThrownBy(() ->
            runner.run(() -> {
              try {
                Thread.sleep(10_000);
              } catch (InterruptedException e) {
                interrupted.set(true);
              } finally {
                finished.countDown();
              }
              return "too late";
            }))
        .isInstanceOf(ExtractionException.class)
        .hasMessageContaining("timed out");
    long elapsedMillis = Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertThat(elapsedMillis).isBetween(250L, 5_000L);
    assertThat(finished.await(5, TimeUnit.SECONDS)).isTrue();
    assertThat(interrupted).isTrue();
    assertThat(runner.run(() -> "next file")).isEqualTo("next file");
  }

  @Test
  void neverRunsMoreThanTheConfiguredNumberAtOnce() throws Exception {
    SafeExtractionRunner bounded = new SafeExtractionRunner(Duration.ofSeconds(10), 2);
    AtomicInteger running = new AtomicInteger();
    AtomicInteger peak = new AtomicInteger();
    int callers = 8;
    CountDownLatch done = new CountDownLatch(callers);
    try {
      for (int i = 0; i < callers; i++) {
        Thread.ofVirtual()
            .start(() -> {
              try {
                bounded.run(() -> {
                  peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                  Thread.sleep(50);
                  running.decrementAndGet();
                  return null;
                });
              } catch (ExtractionException e) {
                throw new AssertionError(e);
              } finally {
                done.countDown();
              }
            });
      }
      assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
    } finally {
      bounded.shutdown();
    }

    assertThat(peak.get()).isEqualTo(2);
  }

  @Test
  void whenEverySlotIsStuckNewFilesFailInsteadOfQueueingForever() throws Exception {
    SafeExtractionRunner single = new SafeExtractionRunner(Duration.ofMillis(200), 1);
    CountDownLatch release = new CountDownLatch(1);
    try {
      // A parser that ignores the interrupt keeps its slot after the timeout.
      assertThatThrownBy(() ->
              single.run(() -> {
                while (!release.await(1, TimeUnit.SECONDS)) {
                  Thread.onSpinWait();
                }
                return null;
              }))
          .hasMessageContaining("timed out");
      // The stuck task swallowed nothing: await() threw, so it has ended. Occupy the slot again
      // with a task that truly ignores interrupts.
      Thread.ofVirtual()
          .start(() -> {
            try {
              single.run(() -> {
                boolean released = false;
                while (!released) {
                  try {
                    released = release.await(1, TimeUnit.SECONDS);
                  } catch (InterruptedException ignored) {
                    // keep going, like a parser stuck in a loop
                  }
                }
                return null;
              });
            } catch (ExtractionException expected) {
              // times out
            }
          });
      Thread.sleep(100);

      assertThatThrownBy(() -> single.run(() -> "starved"))
          .isInstanceOf(ExtractionException.class)
          .hasMessageContaining("No extraction worker became free");
    } finally {
      release.countDown();
      single.shutdown();
    }
  }

  private static String recurseForever() {
    return recurseForever() + "x";
  }
}
