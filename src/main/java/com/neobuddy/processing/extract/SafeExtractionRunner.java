package com.neobuddy.processing.extract;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Runs the parsing of one file on its own worker thread with a time limit, so a slow, huge or
 * malicious file fails alone and cannot stall or crash the pipeline.
 *
 * <p>At most {@code maxConcurrent} files are parsed at once. A file that exceeds the timeout is
 * interrupted and reported as failed. Java cannot kill a thread, so a parser that ignores the
 * interrupt keeps its slot until it returns; callers waiting for a slot give up after the same
 * timeout instead of queueing forever.
 */
@Component
public class SafeExtractionRunner {

  private final Duration timeout;
  private final Semaphore slots;
  private final ExecutorService workers =
      Executors.newThreadPerTaskExecutor(
          Thread.ofPlatform().daemon().name("extraction-", 1).factory());

  @Autowired
  public SafeExtractionRunner(ExtractionProperties properties) {
    this(properties.timeout(), properties.maxConcurrent());
  }

  SafeExtractionRunner(Duration timeout, int maxConcurrent) {
    this.timeout = timeout;
    this.slots = new Semaphore(maxConcurrent);
  }

  /**
   * Runs the task and returns its result.
   *
   * @throws ExtractionException if the task fails for any reason (including errors such as running
   *     out of memory or stack), takes longer than the timeout, or no worker slot frees up in time
   */
  public <T> T run(Callable<T> task) throws ExtractionException {
    acquireSlot();
    CompletableFuture<T> result = new CompletableFuture<>();
    AtomicReference<Thread> worker = new AtomicReference<>();
    try {
      workers.execute(() -> {
        worker.set(Thread.currentThread());
        try {
          result.complete(task.call());
        } catch (Throwable t) {
          result.completeExceptionally(t);
        } finally {
          slots.release();
        }
      });
    } catch (RuntimeException e) {
      slots.release();
      throw new ExtractionException("Extraction could not be started", e);
    }
    try {
      return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      interrupt(worker);
      throw new ExtractionException("Extraction timed out after " + timeout);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof ExtractionException extractionException) {
        throw extractionException;
      }
      throw new ExtractionException(
          "Extraction failed: " + cause.getClass().getSimpleName(), cause);
    } catch (InterruptedException e) {
      interrupt(worker);
      Thread.currentThread().interrupt();
      throw new ExtractionException("Extraction was interrupted");
    }
  }

  private void acquireSlot() throws ExtractionException {
    try {
      if (!slots.tryAcquire(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
        throw new ExtractionException("No extraction worker became free within " + timeout);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ExtractionException("Extraction was interrupted");
    }
  }

  private static void interrupt(AtomicReference<Thread> worker) {
    Thread thread = worker.get();
    if (thread != null) {
      thread.interrupt();
    }
  }

  @PreDestroy
  void shutdown() {
    workers.shutdownNow();
  }
}
