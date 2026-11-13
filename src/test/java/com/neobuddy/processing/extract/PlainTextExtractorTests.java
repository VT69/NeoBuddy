package com.neobuddy.processing.extract;

import static java.nio.charset.StandardCharsets.UTF_16LE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class PlainTextExtractorTests {

  private static final String FILE = "body";

  private final PlainTextExtractor extractor = new PlainTextExtractor();

  @ParameterizedTest
  @EnumSource(DetectedType.class)
  void supportsPlainTextOnly(DetectedType type) {
    assertThat(extractor.supports(type)).isEqualTo(type == DetectedType.PLAIN_TEXT);
  }

  @Test
  void oneChunkPerNonBlankLineWithRealLineNumbers() throws Exception {
    assertThat(extract(SyntheticFiles.plainTextEmail().getBytes(UTF_8)))
        .containsExactly(
            new TextChunk(FILE, "L1", "Dear students,"),
            new TextChunk(FILE, "L3", "The following are shortlisted for Example Corp:"),
            new TextChunk(FILE, "L4", "Z9X8C7V6 - Asha Example"),
            new TextChunk(FILE, "L5", "Q1W2E3R4 - Ravi Sample"),
            new TextChunk(FILE, "L7", "Regards,"),
            new TextChunk(FILE, "L8", "Placement Cell"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"\n", "\r\n", "\r"})
  void everyLineEndingSplitsLines(String eol) throws Exception {
    assertThat(extract(("Z9X8C7V6" + eol + "Q1W2E3R4").getBytes(UTF_8)))
        .containsExactly(
            new TextChunk(FILE, "L1", "Z9X8C7V6"), new TextChunk(FILE, "L2", "Q1W2E3R4"));
  }

  @Test
  void byteOrderMarksAreHonouredAndDropped() throws Exception {
    ByteArrayOutputStream utf8 = new ByteArrayOutputStream();
    utf8.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
    utf8.writeBytes("Z9X8C7V6 Zoë".getBytes(UTF_8));
    ByteArrayOutputStream utf16 = new ByteArrayOutputStream();
    utf16.writeBytes(new byte[] {(byte) 0xFF, (byte) 0xFE});
    utf16.writeBytes("Z9X8C7V6 Zoë".getBytes(UTF_16LE));

    assertThat(extract(utf8.toByteArray()))
        .containsExactly(new TextChunk(FILE, "L1", "Z9X8C7V6 Zoë"));
    assertThat(extract(utf16.toByteArray()))
        .containsExactly(new TextChunk(FILE, "L1", "Z9X8C7V6 Zoë"));
  }

  @Test
  void emptyOrBlankTextYieldsNoChunks() throws Exception {
    assertThat(extract(new byte[0])).isEmpty();
    assertThat(extract(" \n\t\r\n\n".getBytes(UTF_8))).isEmpty();
  }

  @Test
  void undecodableBytesAreReplacedNotFatal() throws Exception {
    byte[] bytes = {
      'Z', '9', 'X', '8', 'C', '7', 'V', '6', ' ', (byte) 0xC3, (byte) 0x28, '\n', 'o', 'k'
    };

    assertThat(extract(bytes)).extracting(TextChunk::text).containsExactly("Z9X8C7V6 �(", "ok");
  }

  private List<TextChunk> extract(byte[] content) throws ExtractionException {
    return TestExtraction.chunks(extractor, FILE, content);
  }
}
