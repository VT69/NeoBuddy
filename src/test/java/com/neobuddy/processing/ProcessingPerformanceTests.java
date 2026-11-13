package com.neobuddy.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.processing.IdentifierSource.IndexedIdentifier;
import com.neobuddy.processing.ProcessingFixture.Mail;
import com.neobuddy.processing.extract.TextChunk;
import com.neobuddy.shared.college.College;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * 50,000 rows against 10,000 indexed identifiers. The bound is generous on purpose so a slow CI
 * runner does not make it flaky; it exists to catch an accidental quadratic, not to benchmark.
 *
 * <p>What is bounded here is NeoBuddy's own work: tokenizing every row and looking every token up,
 * alone and end to end through a CSV attachment. Reading a 50,000-row .xlsx is deliberately not
 * under this bound: Apache POI alone takes several seconds for that many cells, which says nothing
 * about matching. See docs/PROGRESS.md (T4) for the measured figures.
 */
@Tag("performance")
class ProcessingPerformanceTests {

  private static final int ROWS = 50_000;
  private static final int IDENTIFIERS = 10_000;
  private static final int PRESENT_IN_SHEET = 1_000;
  private static final Duration LIMIT = Duration.ofSeconds(5);

  private final ProcessingFixture fixture = new ProcessingFixture();
  private final College college = ProcessingFixture.college("big-college");

  @BeforeEach
  void indexTenThousandIdentifiers() {
    // 1,000 subscribers are in the sheet (every 50th row); 9,000 are not.
    List<IndexedIdentifier> identifiers = new ArrayList<>(IDENTIFIERS);
    for (int i = 1; i <= PRESENT_IN_SHEET; i++) {
      identifiers.add(identifier(ProcessingFixture.generatedNeoId(i * 50)));
    }
    for (int i = 1; i <= IDENTIFIERS - PRESENT_IN_SHEET; i++) {
      identifiers.add(identifier(String.format("X%07d", i)));
    }
    fixture.identifiers.addAll(identifiers);
    fixture.index.rebuild();
    assertThat(fixture.index.size(college.getId())).isEqualTo(IDENTIFIERS);
  }

  @Test
  void matchingFiftyThousandRowsAgainstTenThousandIdentifiers() {
    List<TextChunk> chunks = new ArrayList<>(ROWS);
    for (int i = 1; i <= ROWS; i++) {
      chunks.add(new TextChunk("big.xlsx", "Shortlist!R" + (i + 1), rowText(i)));
    }

    long started = System.nanoTime();
    List<MatchHit> hits = fixture.matcher.match(college.getId(), EventType.SHORTLIST, chunks);
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
    System.out.println("PERF matching 50k rows x 10k identifiers: " + elapsed.toMillis() + " ms");

    assertThat(hits).hasSize(PRESENT_IN_SHEET);
    assertThat(hits.get(0).contextSnippet()).isEqualTo(rowText(50));
    assertThat(elapsed).isLessThan(LIMIT);
  }

  @Test
  void endToEndWithAFiftyThousandRowCsvAttachment() {
    StringBuilder csv = new StringBuilder("NeoID,Name,Reg No,Slot\n");
    for (int i = 1; i <= ROWS; i++) {
      csv.append(ProcessingFixture.generatedNeoId(i))
          .append(",Student ")
          .append(i)
          .append(',')
          .append(2021000000L + i)
          .append(",Slot ")
          .append(i % 8 + 1)
          .append('\n');
    }
    Mail mail =
        Mail.subject("Example Corp - Shortlist")
            .attach("big.csv", ProcessingFixture.utf8(csv.toString()));
    var email = mail.email();

    long started = System.nanoTime();
    ProcessingResult result = fixture.service.process(college, email, mail.content());
    Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
    System.out.println(
        "PERF end to end, 50k-row CSV x 10k identifiers: " + elapsed.toMillis() + " ms");

    assertThat(result.extraction().extracted()).isEqualTo(1);
    assertThat(result.hits()).hasSize(PRESENT_IN_SHEET);
    assertThat(result.hits().get(0).contextSnippet()).isEqualTo(rowText(50));
    assertThat(elapsed).isLessThan(LIMIT);
  }

  private static String rowText(int i) {
    return ProcessingFixture.generatedNeoId(i)
        + " | Student "
        + i
        + " | "
        + (2021000000L + i)
        + " | Slot "
        + (i % 8 + 1);
  }

  private IndexedIdentifier identifier(String value) {
    return new IndexedIdentifier(college.getId(), UUID.randomUUID(), UUID.randomUUID(), value);
  }
}
