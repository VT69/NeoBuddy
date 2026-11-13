package com.neobuddy.ingestion.gmail;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Streams the raw characters of one string field out of a JSON object, without parsing the whole
 * document into memory. Built for Gmail's attachment response, {@code {"size": 123, "data":
 * "<base64url>"}}, whose data can be tens of megabytes.
 *
 * <p>JSON escape sequences in the value are decoded. The stream ends at the closing quote; an input
 * without the field yields an empty stream.
 */
final class JsonStringFieldInputStream extends InputStream {

  private static final int BACKSLASH = 0x5C;
  private static final int LINE_FEED = 0x0A;
  private static final int CARRIAGE_RETURN = 0x0D;
  private static final int TAB = 0x09;

  private final InputStream json;
  private final byte[] key;
  private boolean positioned;
  private boolean finished;

  JsonStringFieldInputStream(InputStream json, String field) {
    this.json = new BufferedInputStream(json, 64 * 1024);
    this.key = ("\"" + field + "\"").getBytes(StandardCharsets.US_ASCII);
  }

  @Override
  public int read() throws IOException {
    if (!positioned) {
      seekToValue();
    }
    if (finished) {
      return -1;
    }
    int next = json.read();
    if (next < 0 || next == '"') {
      finished = true;
      return -1;
    }
    return next == BACKSLASH ? unescape() : next;
  }

  /**
   * A JSON writer may escape characters it need not: some write the equals sign of base64 padding
   * as a unicode escape. Only single-byte results can occur in base64, which is all this reads.
   */
  private int unescape() throws IOException {
    int kind = json.read();
    if (kind < 0) {
      throw new IOException("Truncated JSON string");
    }
    if (kind == 'u') {
      int code = 0;
      for (int i = 0; i < 4; i++) {
        int digit = Character.digit(json.read(), 16);
        if (digit < 0) {
          throw new IOException("Malformed escape in JSON string");
        }
        code = code * 16 + digit;
      }
      if (code > 0xFF) {
        throw new IOException("Unexpected character in JSON string");
      }
      return code;
    }
    if (kind == 'n') {
      return LINE_FEED;
    }
    if (kind == 'r') {
      return CARRIAGE_RETURN;
    }
    if (kind == 't') {
      return TAB;
    }
    // An escaped quote, slash or backslash stands for itself.
    return kind;
  }

  @Override
  public int read(byte[] buffer, int offset, int length) throws IOException {
    if (length == 0) {
      return 0;
    }
    int count = 0;
    while (count < length) {
      int next = read();
      if (next < 0) {
        break;
      }
      buffer[offset + count++] = (byte) next;
    }
    return count == 0 ? -1 : count;
  }

  /** Skips to just after the opening quote of the field's value. */
  private void seekToValue() throws IOException {
    positioned = true;
    int matched = 0;
    int next;
    while ((next = json.read()) >= 0) {
      if (next == key[matched]) {
        matched++;
        if (matched == key.length) {
          if (skipToOpeningQuote()) {
            return;
          }
          matched = 0;
        }
      } else {
        matched = next == key[0] ? 1 : 0;
      }
    }
    finished = true;
  }

  /** After the key: optional whitespace, a colon, optional whitespace, then the opening quote. */
  private boolean skipToOpeningQuote() throws IOException {
    boolean colon = false;
    int next;
    while ((next = json.read()) >= 0) {
      if (Character.isWhitespace(next)) {
        continue;
      }
      if (next == ':' && !colon) {
        colon = true;
      } else {
        return next == '"' && colon;
      }
    }
    finished = true;
    return false;
  }

  @Override
  public void close() throws IOException {
    json.close();
  }
}
