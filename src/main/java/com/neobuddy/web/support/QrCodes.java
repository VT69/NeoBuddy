package com.neobuddy.web.support;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.util.Map;

/** QR codes as inline SVG, so a page needs no image request and no inline style. */
public final class QrCodes {

  private QrCodes() {}

  /**
   * @param text what the code encodes; only ever text NeoBuddy itself generated
   * @return an {@code <svg>} element, safe to insert unescaped
   */
  public static String svg(String text, String label) {
    BitMatrix matrix;
    try {
      matrix =
          new QRCodeWriter()
              .encode(
                  text,
                  BarcodeFormat.QR_CODE,
                  0,
                  0,
                  Map.of(
                      EncodeHintType.MARGIN,
                      2,
                      EncodeHintType.ERROR_CORRECTION,
                      ErrorCorrectionLevel.M));
    } catch (WriterException e) {
      throw new IllegalArgumentException("Cannot encode a QR code", e);
    }
    int size = matrix.getWidth();
    StringBuilder path = new StringBuilder();
    for (int y = 0; y < matrix.getHeight(); y++) {
      for (int x = 0; x < size; x++) {
        if (matrix.get(x, y)) {
          path.append('M').append(x).append(',').append(y).append("h1v1h-1z");
        }
      }
    }
    return "<svg xmlns=\"http://www.w3.org/2000/svg\" class=\"qr\" role=\"img\" viewBox=\"0 0 "
        + size
        + " "
        + size
        + "\" shape-rendering=\"crispEdges\"><title>"
        + escape(label)
        + "</title><rect width=\""
        + size
        + "\" height=\""
        + size
        + "\" fill=\"#fff\"/><path fill=\"#000\" d=\""
        + path
        + "\"/></svg>";
  }

  private static String escape(String value) {
    return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
  }
}
