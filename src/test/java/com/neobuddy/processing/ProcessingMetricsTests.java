package com.neobuddy.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.processing.ProcessingFixture.Mail;
import com.neobuddy.shared.college.College;
import io.micrometer.core.instrument.Counter;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** {@code extraction_failures_total{type, college}} from the processing pipeline. */
class ProcessingMetricsTests {

  private final ProcessingFixture fixture = new ProcessingFixture();
  private final College college = ProcessingFixture.college("metrics-college");

  private double failures(String type) {
    Counter counter =
        fixture
            .meters
            .find("extraction.failures")
            .tag("college", "metrics-college")
            .tag("type", type)
            .counter();
    return counter == null ? 0 : counter.count();
  }

  @Test
  void aFileThatCannotBeParsedIsCountedByItsDetectedType() {
    byte[] brokenPdf = "%PDF-1.7\nthis is not really a pdf".getBytes(StandardCharsets.US_ASCII);
    Mail mail =
        Mail.subject("Example Corp - Shortlist")
            .attach("broken.pdf", brokenPdf)
            .attach("fine.csv", ProcessingFixture.utf8("NeoID\nA0B0C0D0\n"));

    ProcessingResult result = fixture.service.process(college, mail.email(), mail.content());

    assertThat(result.extraction().failed()).isEqualTo(1);
    assertThat(failures("pdf")).isEqualTo(1);
    assertThat(failures("csv")).isZero();
  }

  @Test
  void anAttachmentThatCannotBeFetchedIsCountedAsUnavailable() {
    Mail mail = Mail.subject("Example Corp - Shortlist").attachUnfetchable("shortlist.xlsx");

    fixture.service.process(college, mail.email(), mail.content());

    assertThat(failures("unavailable")).isEqualTo(1);
  }

  @Test
  void skippedFilesAreNotFailures() {
    Mail mail = Mail.subject("Photos").attach("photo.png", new byte[] {(byte) 0x89, 'P', 'N', 'G'});

    fixture.service.process(college, mail.email(), mail.content());

    assertThat(fixture.meters.find("extraction.failures").counters()).isEmpty();
  }
}
