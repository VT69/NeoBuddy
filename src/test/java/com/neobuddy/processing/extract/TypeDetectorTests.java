package com.neobuddy.processing.extract;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.ingestion.AttachmentRef;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class TypeDetectorTests {

  private static final byte[] CSV = "NeoID,Name\nZ9X8C7V6,Asha Example\n".getBytes(UTF_8);

  private final TypeDetector detector = new TypeDetector();

  static Stream<Arguments> honestFiles() {
    return Stream.of(
        Arguments.of("shortlist.xlsx", SyntheticFiles.xlsx(), DetectedType.XLSX),
        Arguments.of("shortlist.xls", SyntheticFiles.xls(), DetectedType.XLS),
        Arguments.of("shortlist.pdf", SyntheticFiles.pdfWithTable(), DetectedType.PDF),
        Arguments.of("shortlist.docx", SyntheticFiles.docxWithTable(), DetectedType.DOCX),
        Arguments.of("mail.html", SyntheticFiles.htmlEmail().getBytes(UTF_8), DetectedType.HTML),
        Arguments.of(
            "mail.txt", SyntheticFiles.plainTextEmail().getBytes(UTF_8), DetectedType.PLAIN_TEXT),
        Arguments.of("shortlist.csv", CSV, DetectedType.CSV));
  }

  static Stream<Arguments> binaryFiles() {
    return honestFiles().limit(4);
  }

  @ParameterizedTest(name = "{0} -> {2}")
  @MethodSource("honestFiles")
  void detectsEachSupportedType(String filename, byte[] content, DetectedType expected) {
    assertThat(detector.detect(content, SyntheticFiles.ref(filename, content))).isEqualTo(expected);
  }

  @ParameterizedTest(name = "{2} without a filename")
  @MethodSource("binaryFiles")
  void binaryTypesNeedNoFilename(String filename, byte[] content, DetectedType expected) {
    assertThat(detector.detect(content, new AttachmentRef(null, null, content.length)))
        .isEqualTo(expected);
    assertThat(detector.detect(content, new AttachmentRef("attachment", " ", content.length)))
        .isEqualTo(expected);
  }

  @ParameterizedTest(name = "{2} named .txt, .pdf, .xlsx, .docx")
  @MethodSource("binaryFiles")
  void aLyingExtensionOrMimeTypeIsIgnored(String filename, byte[] content, DetectedType expected) {
    for (String lie : new String[] {"list.txt", "list.pdf", "list.xlsx", "list.xls", "list.docx"}) {
      assertThat(detector.detect(content, SyntheticFiles.ref(lie, content)))
          .as("content of %s named %s", filename, lie)
          .isEqualTo(expected);
    }
    assertThat(
            detector.detect(content, new AttachmentRef("list.bin", "text/plain", content.length)))
        .isEqualTo(expected);
  }

  @Test
  void textNamedAsAnOfficeFileIsNotAnOfficeFile() {
    byte[] text = SyntheticFiles.plainTextEmail().getBytes(UTF_8);

    assertThat(detector.detect(text, SyntheticFiles.ref("shortlist.xlsx", text)))
        .isNotIn(DetectedType.XLSX, DetectedType.XLS, DetectedType.DOCX, DetectedType.PDF);
    assertThat(detector.detect(text, new AttachmentRef("list", "application/pdf", text.length)))
        .isNotEqualTo(DetectedType.PDF);
  }

  @Test
  void csvIsRecognisedByNameOrDeclaredTypeOnTopOfTextContent() {
    assertThat(detector.detect(CSV, new AttachmentRef("shortlist.csv", null, CSV.length)))
        .isEqualTo(DetectedType.CSV);
    assertThat(detector.detect(CSV, new AttachmentRef("shortlist", "text/csv", CSV.length)))
        .isEqualTo(DetectedType.CSV);
    assertThat(detector.detect(CSV, new AttachmentRef("shortlist", null, CSV.length)))
        .isEqualTo(DetectedType.PLAIN_TEXT);
  }

  @Test
  void anOrdinaryZipIsUnknownEvenWhenNamedXlsx() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      zip.putNextEntry(new ZipEntry("notes.txt"));
      zip.write("Z9X8C7V6".getBytes(UTF_8));
      zip.closeEntry();
    }
    byte[] content = out.toByteArray();

    assertThat(detector.detect(content, SyntheticFiles.ref("shortlist.xlsx", content)))
        .isEqualTo(DetectedType.UNKNOWN);
    assertThat(detector.detect(content, SyntheticFiles.ref("archive.zip", content)))
        .isEqualTo(DetectedType.UNKNOWN);
  }

  @Test
  void aTruncatedOfficeFileFallsBackToItsNameSoTheExtractorReportsTheFailure() {
    byte[] xlsx = SyntheticFiles.truncated(SyntheticFiles.xlsx());
    byte[] docx = SyntheticFiles.truncated(SyntheticFiles.docxWithTable());

    assertThat(detector.detect(xlsx, SyntheticFiles.ref("shortlist.xlsx", xlsx)))
        .isEqualTo(DetectedType.XLSX);
    assertThat(detector.detect(docx, SyntheticFiles.ref("shortlist.docx", docx)))
        .isEqualTo(DetectedType.DOCX);
    assertThat(detector.detect(xlsx, new AttachmentRef(null, null, xlsx.length)))
        .isEqualTo(DetectedType.UNKNOWN);
  }

  @Test
  void noiseExecutablesAndEmptyContentAreUnknown() {
    byte[] noise = SyntheticFiles.noise(4096);
    byte[] exe = {'M', 'Z', (byte) 0x90, 0, 3, 0, 0, 0, 4, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 0, 0};

    assertThat(detector.detect(noise, SyntheticFiles.ref("shortlist.xlsx", noise)))
        .isEqualTo(DetectedType.UNKNOWN);
    assertThat(detector.detect(exe, SyntheticFiles.ref("shortlist.pdf", exe)))
        .isEqualTo(DetectedType.UNKNOWN);
    assertThat(detector.detect(new byte[0], SyntheticFiles.ref("shortlist.xlsx", new byte[0])))
        .isEqualTo(DetectedType.UNKNOWN);
  }
}
