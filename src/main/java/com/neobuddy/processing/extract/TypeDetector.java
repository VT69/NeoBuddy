package com.neobuddy.processing.extract;

import com.neobuddy.ingestion.AttachmentRef;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.apache.poi.poifs.filesystem.DirectoryNode;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.mime.MediaType;
import org.apache.tika.mime.MimeTypes;
import org.apache.tika.parser.ParseContext;
import org.springframework.stereotype.Component;

/**
 * Finds the real type of a file from its content.
 *
 * <p>Apache Tika (core) reads the magic bytes and uses the filename and declared MIME type only as
 * hints to narrow what the bytes already say, so a PDF named {@code list.xlsx} is a PDF. Office
 * files are containers (ZIP or OLE2) that look alike from their first bytes, so those are told
 * apart by what the container holds, not by the extension.
 */
@Component
public class TypeDetector {

  private static final MediaType ZIP = MediaType.application("zip");
  private static final MediaType OLE2 = MediaType.application("x-tika-msoffice");
  private static final MediaType OOXML = MediaType.application("x-tika-ooxml");

  private static final Set<DetectedType> TEXT_TYPES =
      EnumSet.of(DetectedType.PLAIN_TEXT, DetectedType.CSV, DetectedType.HTML);
  private static final Set<String> CSV_MIME_TYPES =
      Set.of("text/csv", "application/csv", "text/comma-separated-values");

  private final MimeTypes mimeTypes = MimeTypes.getDefaultMimeTypes();

  public DetectedType detect(byte[] content, AttachmentRef attachment) {
    if (content.length == 0) {
      return DetectedType.UNKNOWN;
    }
    MediaType byMagic = tika(content, null);
    MediaType withHints = tika(content, attachment);

    if (isOrSpecialises(byMagic, ZIP) || isOrSpecialises(byMagic, OOXML)) {
      return ooxmlKind(content).orElseGet(() -> fromMediaType(withHints, false));
    }
    if (isOrSpecialises(byMagic, OLE2)) {
      return ole2Kind(content).orElseGet(() -> fromMediaType(withHints, false));
    }
    // Not a container. The name and declared type may only choose between kinds of text
    // (plain, CSV, HTML); they can never turn the content into a binary format.
    DetectedType magic = fromMediaType(byMagic, true);
    DetectedType hinted = fromMediaType(withHints, true);
    if (!TEXT_TYPES.contains(magic) || !TEXT_TYPES.contains(hinted)) {
      return magic;
    }
    return hinted == DetectedType.PLAIN_TEXT && declaresCsv(attachment) ? DetectedType.CSV : hinted;
  }

  private static boolean declaresCsv(AttachmentRef attachment) {
    String declared = attachment.declaredMimeType();
    if (declared == null) {
      return false;
    }
    String base = declared.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
    return CSV_MIME_TYPES.contains(base);
  }

  private MediaType tika(byte[] content, AttachmentRef attachment) {
    Metadata metadata = new Metadata();
    if (attachment != null) {
      if (attachment.filename() != null && !attachment.filename().isBlank()) {
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, attachment.filename());
      }
      if (attachment.declaredMimeType() != null && !attachment.declaredMimeType().isBlank()) {
        metadata.set(TikaCoreProperties.CONTENT_TYPE_HINT, attachment.declaredMimeType());
      }
    }
    try (TikaInputStream in = TikaInputStream.get(content)) {
      return mimeTypes.detect(in, metadata, new ParseContext());
    } catch (IOException | RuntimeException e) {
      return MediaType.OCTET_STREAM;
    }
  }

  private boolean isOrSpecialises(MediaType type, MediaType ancestor) {
    return type.equals(ancestor)
        || mimeTypes.getMediaTypeRegistry().isSpecializationOf(type, ancestor);
  }

  private DetectedType fromMediaType(MediaType type, boolean textAllowed) {
    String name = type.getBaseType().toString();
    return switch (name) {
      case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
          "application/vnd.ms-excel.sheet.macroenabled.12" ->
          DetectedType.XLSX;
      case "application/vnd.ms-excel" -> DetectedType.XLS;
      case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
          DetectedType.DOCX;
      case "application/pdf" -> DetectedType.PDF;
      case "text/html", "application/xhtml+xml" ->
          textAllowed ? DetectedType.HTML : DetectedType.UNKNOWN;
      case "text/csv" -> textAllowed ? DetectedType.CSV : DetectedType.UNKNOWN;
      case "text/plain" -> textAllowed ? DetectedType.PLAIN_TEXT : DetectedType.UNKNOWN;
      default -> DetectedType.UNKNOWN;
    };
  }

  /** XLS when an OLE2 container holds a workbook stream. Empty when it cannot be read. */
  private static Optional<DetectedType> ole2Kind(byte[] content) {
    try (POIFSFileSystem fs = new POIFSFileSystem(new ByteArrayInputStream(content))) {
      DirectoryNode root = fs.getRoot();
      boolean workbook = root.hasEntry("Workbook") || root.hasEntry("Book");
      return Optional.of(workbook ? DetectedType.XLS : DetectedType.UNKNOWN);
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }

  /**
   * XLSX or DOCX from the entry names in the ZIP central directory. Only names are read; nothing is
   * decompressed, so a zip bomb costs nothing here. Empty when the directory cannot be read.
   */
  private static Optional<DetectedType> ooxmlKind(byte[] zip) {
    int end = endOfCentralDirectory(zip);
    if (end < 0) {
      return Optional.empty();
    }
    int entries = u16(zip, end + 10);
    long offset = u32(zip, end + 16);
    boolean workbook = false;
    boolean document = false;
    int pos = (int) Math.min(offset, zip.length);
    for (int i = 0; i < entries && pos + 46 <= zip.length; i++) {
      if (u32(zip, pos) != 0x02014b50L) {
        return Optional.empty();
      }
      int nameLength = u16(zip, pos + 28);
      int next = pos + 46 + nameLength + u16(zip, pos + 30) + u16(zip, pos + 32);
      if (pos + 46 + nameLength > zip.length) {
        return Optional.empty();
      }
      String name = new String(zip, pos + 46, nameLength, StandardCharsets.UTF_8);
      workbook |= name.equals("xl/workbook.xml");
      document |= name.equals("word/document.xml");
      pos = next;
    }
    if (workbook == document) {
      return Optional.of(DetectedType.UNKNOWN);
    }
    return Optional.of(workbook ? DetectedType.XLSX : DetectedType.DOCX);
  }

  private static int endOfCentralDirectory(byte[] zip) {
    int lowest = Math.max(0, zip.length - 22 - 0xFFFF);
    for (int pos = zip.length - 22; pos >= lowest; pos--) {
      if (u32(zip, pos) == 0x06054b50L) {
        return pos;
      }
    }
    return -1;
  }

  private static int u16(byte[] b, int pos) {
    return (b[pos] & 0xFF) | (b[pos + 1] & 0xFF) << 8;
  }

  private static long u32(byte[] b, int pos) {
    return (u16(b, pos) | (long) u16(b, pos + 2) << 16) & 0xFFFFFFFFL;
  }
}
