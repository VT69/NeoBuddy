package com.neobuddy.processing;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.core.OutputStreamAppender;
import com.neobuddy.shared.observability.LogContext;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/**
 * Runs the T4 golden pipeline (every scenario of {@link ProcessingServiceGoldenTests}) with the
 * production JSON log encoder capturing everything logged, and checks that none of the fixture
 * NeoIDs, registration numbers or student names reach the logs. NeoBuddy's own loggers run at DEBUG
 * here, stricter than production's INFO; libraries run at production's INFO.
 */
class GoldenPipelineLogTests {

  /** Every identifier and name the golden fixtures put into emails and sheets. */
  private static final List<String> FIXTURE_PERSONAL_DATA = fixtureValues();

  private static List<String> fixtureValues() {
    List<String> values =
        new ArrayList<>(
            List.of(
                "N1B7J5V6",
                "N1B7 J5V6",
                "n1b7-j5v6",
                "21BCE1234",
                "A0B0C0D0",
                "Z9X8C7V6",
                "Z9X8 C7V6",
                "Q1W2E3R4",
                "N1B7J5V6X",
                "N1B7J5V",
                "Tara Target",
                "Asha Example",
                "Ravi Sample",
                "Not Tara",
                "Nearly Tara",
                "2021000317"));
    // The 500 generated rows of scenario a): spot-check the first, the one replaced and the last.
    for (int i : new int[] {1, 2, 317, 499, 500}) {
      values.add(ProcessingFixture.generatedNeoId(i));
      values.add("Student " + i + "\"");
      values.add("Student " + i + " ");
      values.add(String.valueOf(2021000000L + i));
    }
    return List.copyOf(values);
  }

  @Test
  void theGoldenPipelineLogsNoIdentifiersOrNames() {
    LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
    LogstashEncoder encoder = new LogstashEncoder();
    encoder.setContext(context);
    encoder.addIncludeMdcKeyName(LogContext.RFC_MESSAGE_ID);
    encoder.addIncludeMdcKeyName(LogContext.COLLEGE_SLUG);
    encoder.start();
    ByteArrayOutputStream captured = new ByteArrayOutputStream();
    OutputStreamAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
        new OutputStreamAppender<>();
    appender.setContext(context);
    appender.setEncoder(encoder);
    appender.setOutputStream(captured);
    appender.start();

    Logger root = context.getLogger(Logger.ROOT_LOGGER_NAME);
    Logger ours = context.getLogger("com.neobuddy");
    Level rootLevel = root.getLevel();
    Level ourLevel = ours.getLevel();
    root.addAppender(appender);
    root.setLevel(Level.INFO);
    ours.setLevel(Level.DEBUG);
    int scenarios;
    try {
      scenarios = runGoldenTests();
    } finally {
      root.detachAppender(appender);
      root.setLevel(rootLevel);
      ours.setLevel(ourLevel);
      appender.stop();
    }

    assertThat(scenarios).as("golden scenarios run").isGreaterThanOrEqualTo(8);
    String output = captured.toString(StandardCharsets.UTF_8);
    // The capture works: every processed email is logged, with its Message-ID, as JSON.
    assertThat(output).contains("\"message\":\"Processed email <").contains("@mail.example>");
    for (String value : FIXTURE_PERSONAL_DATA) {
      assertThat(output).as("log output contains fixture data").doesNotContain(value);
    }
  }

  /**
   * Runs every {@code @Test} of the golden tests the way JUnit would: a fresh instance per
   * scenario, its {@code @BeforeEach} first. A failing scenario fails this test.
   *
   * @return how many scenarios ran
   */
  private static int runGoldenTests() {
    int ran = 0;
    try {
      for (Method test : ProcessingServiceGoldenTests.class.getDeclaredMethods()) {
        if (!test.isAnnotationPresent(Test.class)) {
          continue;
        }
        var constructor = ProcessingServiceGoldenTests.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object instance = constructor.newInstance();
        for (Method setUp : ProcessingServiceGoldenTests.class.getDeclaredMethods()) {
          if (setUp.isAnnotationPresent(BeforeEach.class)) {
            setUp.setAccessible(true);
            setUp.invoke(instance);
          }
        }
        test.setAccessible(true);
        test.invoke(instance);
        ran++;
      }
    } catch (InvocationTargetException e) {
      throw new AssertionError("A golden scenario failed", e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(e);
    }
    return ran;
  }
}
