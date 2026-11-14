package com.neobuddy.web.support;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class QrCodesTests {

  @Test
  void rendersASquareSvgWithAnEscapedTitle() {
    String svg = QrCodes.svg("https://ntfy.sh/nb-abcdefghijklmnopqrstuvwxyz", "Topic <a & b>");

    assertThat(svg)
        .startsWith("<svg xmlns=\"http://www.w3.org/2000/svg\"")
        .endsWith("</svg>")
        .contains("<title>Topic &lt;a &amp; b&gt;</title>")
        .containsPattern("viewBox=\"0 0 (\\d+) \\1\"")
        .contains("<path fill=\"#000\" d=\"M")
        .doesNotContain("style=");
  }

  @Test
  void differentTextGivesADifferentCode() {
    assertThat(QrCodes.svg("https://ntfy.sh/a", "x"))
        .isNotEqualTo(QrCodes.svg("https://ntfy.sh/b", "x"));
  }
}
