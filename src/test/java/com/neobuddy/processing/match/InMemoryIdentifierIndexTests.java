package com.neobuddy.processing.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.neobuddy.processing.FakeIdentifierSource;
import com.neobuddy.processing.IdentifierSource;
import com.neobuddy.processing.IdentifierSource.IndexedIdentifier;
import com.neobuddy.processing.IdentifiersChanged;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class InMemoryIdentifierIndexTests {

  private static final UUID COLLEGE_A = UUID.randomUUID();
  private static final UUID COLLEGE_B = UUID.randomUUID();
  private static final UUID USER_1 = UUID.randomUUID();
  private static final UUID USER_2 = UUID.randomUUID();

  private final FakeIdentifierSource source = new FakeIdentifierSource();
  private final InMemoryIdentifierIndex index = new InMemoryIdentifierIndex(Optional.of(source));

  @Test
  void isEmptyUntilTheFirstRebuild() {
    source.add(COLLEGE_A, USER_1, "N1B7J5V6");

    assertThat(index.lookup(COLLEGE_A, "N1B7J5V6")).isEmpty();

    index.rebuild();

    assertThat(index.lookup(COLLEGE_A, "N1B7J5V6")).hasSize(1);
  }

  @Test
  void findsTheIdentifierAndItsOwner() {
    UUID identifierId = source.add(COLLEGE_A, USER_1, "N1B7J5V6");
    index.rebuild();

    assertThat(index.lookup(COLLEGE_A, "N1B7J5V6"))
        .containsExactly(new IdentifierRef(identifierId, USER_1));
    assertThat(index.lookup(COLLEGE_A, "N1B7J5V6X")).isEmpty();
    assertThat(index.lookup(COLLEGE_A, "N1B7")).isEmpty();
  }

  @Test
  void neverCrossesColleges() {
    UUID inA = source.add(COLLEGE_A, USER_1, "N1B7J5V6");
    UUID inB = source.add(COLLEGE_B, USER_2, "N1B7J5V6");
    source.add(COLLEGE_B, USER_2, "ONLYINB1");
    index.rebuild();

    assertThat(index.lookup(COLLEGE_A, "N1B7J5V6")).containsExactly(new IdentifierRef(inA, USER_1));
    assertThat(index.lookup(COLLEGE_B, "N1B7J5V6")).containsExactly(new IdentifierRef(inB, USER_2));
    assertThat(index.lookup(COLLEGE_A, "ONLYINB1")).isEmpty();
    assertThat(index.lookup(UUID.randomUUID(), "N1B7J5V6")).isEmpty();
    assertThat(index.size(COLLEGE_A)).isEqualTo(1);
    assertThat(index.size(COLLEGE_B)).isEqualTo(2);
  }

  @Test
  void keysTakeTheFormTheTokenizerEmits() {
    source.add(COLLEGE_A, USER_1, " n1b7j5v6 ");
    source.add(COLLEGE_A, USER_1, "Tara.Target@College.Example");
    index.rebuild();

    // Identifiers upper-case, email addresses lower-case, whatever case they were stored in.
    assertThat(index.lookup(COLLEGE_A, "N1B7J5V6")).hasSize(1);
    assertThat(index.lookup(COLLEGE_A, "n1b7j5v6")).isEmpty();
    assertThat(index.lookup(COLLEGE_A, "tara.target@college.example")).hasSize(1);
    assertThat(index.lookup(COLLEGE_A, "TARA.TARGET@COLLEGE.EXAMPLE")).isEmpty();
  }

  @Test
  void theSameValueClaimedTwiceReturnsBothOwners() {
    // The database forbids this within a college; the index must still not lose anyone.
    source.add(COLLEGE_A, USER_1, "N1B7J5V6");
    source.add(COLLEGE_A, USER_2, "N1B7J5V6");
    index.rebuild();

    assertThat(index.lookup(COLLEGE_A, "N1B7J5V6"))
        .extracting(IdentifierRef::userId)
        .containsExactlyInAnyOrder(USER_1, USER_2);
  }

  @Test
  void blankOrBrokenEntriesAreIgnored() {
    source.addAll(
        List.of(
            new IndexedIdentifier(COLLEGE_A, USER_1, UUID.randomUUID(), " "),
            new IndexedIdentifier(COLLEGE_A, USER_1, UUID.randomUUID(), null),
            new IndexedIdentifier(null, USER_1, UUID.randomUUID(), "N1B7J5V6")));
    index.rebuild();

    assertThat(index.size(COLLEGE_A)).isZero();
  }

  @Test
  void rebuildReplacesTheWholeIndex() {
    source.add(COLLEGE_A, USER_1, "N1B7J5V6");
    index.rebuild();
    source.clear();
    source.add(COLLEGE_A, USER_2, "Q1W2E3R4");

    index.rebuild();

    assertThat(index.lookup(COLLEGE_A, "N1B7J5V6")).isEmpty();
    assertThat(index.lookup(COLLEGE_A, "Q1W2E3R4")).hasSize(1);
  }

  @Test
  void returnedSetsCannotBeModified() {
    source.add(COLLEGE_A, USER_1, "N1B7J5V6");
    index.rebuild();
    Set<IdentifierRef> refs = index.lookup(COLLEGE_A, "N1B7J5V6");

    assertThatThrownBy(() -> refs.add(new IdentifierRef(UUID.randomUUID(), USER_2)))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void identifiersChangedTriggersARebuild() {
    index.rebuild();
    source.add(COLLEGE_A, USER_1, "N1B7J5V6");

    index.onIdentifiersChanged(new IdentifiersChanged(COLLEGE_A));

    assertThat(index.lookup(COLLEGE_A, "N1B7J5V6")).hasSize(1);
  }

  @Test
  void theScheduledRefreshRebuilds() {
    source.add(COLLEGE_A, USER_1, "N1B7J5V6");

    index.refreshOnSchedule();

    assertThat(index.lookup(COLLEGE_A, "N1B7J5V6")).hasSize(1);
  }

  @Test
  void aFailedRefreshKeepsThePreviousIndexAndLogsNoData(CapturedOutput output) {
    source.add(COLLEGE_A, USER_1, "N1B7J5V6");
    index.rebuild();
    source.failWith(new IllegalStateException("could not load N1B7J5V6"));

    index.refreshOnSchedule();
    index.onIdentifiersChanged(new IdentifiersChanged(COLLEGE_A));

    assertThat(index.lookup(COLLEGE_A, "N1B7J5V6")).hasSize(1);
    assertThat(output).contains("Identifier index rebuild failed");
    assertThat(output.toString()).doesNotContain("N1B7J5V6");
  }

  @Test
  void withoutASourceTheIndexStaysEmptyAndWarnsOnce(CapturedOutput output) {
    InMemoryIdentifierIndex orphan = new InMemoryIdentifierIndex(Optional.empty());

    orphan.rebuild();
    orphan.refreshOnSchedule();

    assertThat(orphan.lookup(COLLEGE_A, "N1B7J5V6")).isEmpty();
    assertThat(output.toString().split("No IdentifierSource is registered", -1)).hasSize(2);
  }

  /**
   * While a rebuild is still loading, readers keep seeing the complete previous index; the new one
   * appears all at once when the rebuild finishes. Nothing is ever visible half built.
   */
  @Test
  @Timeout(30)
  void aRebuildInProgressIsInvisibleUntilItIsSwappedIn() throws Exception {
    CountDownLatch loading = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<List<IndexedIdentifier>> generation = new AtomicReference<>(generation("OLD"));
    IdentifierSource slowSource =
        () -> {
          List<IndexedIdentifier> loaded = generation.get();
          if (loaded.get(0).normalizedValue().startsWith("NEW")) {
            loading.countDown();
            awaitQuietly(release);
          }
          return loaded;
        };
    InMemoryIdentifierIndex slowIndex = new InMemoryIdentifierIndex(Optional.of(slowSource));
    slowIndex.rebuild();

    generation.set(generation("NEW"));
    Thread rebuild = Thread.ofPlatform().start(slowIndex::rebuild);
    assertThat(loading.await(10, TimeUnit.SECONDS)).isTrue();

    // The rebuild is under way: every old token is still there and no new one is.
    assertThat(slowIndex.size(COLLEGE_A)).isEqualTo(GENERATION_SIZE);
    assertThat(slowIndex.lookup(COLLEGE_A, "OLD00000")).hasSize(1);
    assertThat(slowIndex.lookup(COLLEGE_A, "OLD01999")).hasSize(1);
    assertThat(slowIndex.lookup(COLLEGE_A, "NEW00000")).isEmpty();

    release.countDown();
    rebuild.join();

    assertThat(slowIndex.size(COLLEGE_A)).isEqualTo(GENERATION_SIZE);
    assertThat(slowIndex.lookup(COLLEGE_A, "NEW00000")).hasSize(1);
    assertThat(slowIndex.lookup(COLLEGE_A, "NEW01999")).hasSize(1);
    assertThat(slowIndex.lookup(COLLEGE_A, "OLD00000")).isEmpty();
  }

  /** Readers hammer the index while it is rebuilt repeatedly; no lookup may ever fail. */
  @Test
  @Timeout(30)
  void concurrentReadersAndRebuildsDoNotInterfere() throws Exception {
    source.addAll(generation("OLD"));
    index.rebuild();
    AtomicBoolean stop = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<Thread> readers = new ArrayList<>();
    for (int r = 0; r < 4; r++) {
      readers.add(
          Thread.ofPlatform()
              .start(() -> {
                try {
                  while (!stop.get()) {
                    // Whichever generation is current, a token maps to at most one owner.
                    assertThat(index.lookup(COLLEGE_A, "OLD00042").size()).isLessThanOrEqualTo(1);
                    assertThat(index.lookup(COLLEGE_A, "NEW00042").size()).isLessThanOrEqualTo(1);
                  }
                } catch (Throwable t) {
                  failure.compareAndSet(null, t);
                }
              }));
    }
    for (int swap = 0; swap < 100; swap++) {
      source.clear();
      source.addAll(generation(swap % 2 == 0 ? "NEW" : "OLD"));
      index.rebuild();
    }
    stop.set(true);
    for (Thread reader : readers) {
      reader.join();
    }

    assertThat(failure.get()).isNull();
    assertThat(index.size(COLLEGE_A)).isEqualTo(GENERATION_SIZE);
  }

  private static final int GENERATION_SIZE = 2_000;

  private static List<IndexedIdentifier> generation(String prefix) {
    List<IndexedIdentifier> identifiers = new ArrayList<>();
    for (int i = 0; i < GENERATION_SIZE; i++) {
      identifiers.add(
          new IndexedIdentifier(
              COLLEGE_A, USER_1, UUID.randomUUID(), String.format("%s%05d", prefix, i)));
    }
    return identifiers;
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await(20, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
