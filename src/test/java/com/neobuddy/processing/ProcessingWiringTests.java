package com.neobuddy.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.processing.ProcessingFixture.Mail;
import com.neobuddy.processing.classify.ClassifierProperties;
import com.neobuddy.processing.match.IdentifierIndex;
import com.neobuddy.shared.college.College;
import com.neobuddy.testsupport.DatabaseTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * The pipeline as Spring wires it in the real application context, with a fake standing in for the
 * subscriber module's {@link IdentifierSource}.
 */
@DatabaseTest
@Import(ProcessingWiringTests.FakeSubscribers.class)
@RecordApplicationEvents
class ProcessingWiringTests {

  @TestConfiguration(proxyBeanMethods = false)
  static class FakeSubscribers {

    @Bean
    FakeIdentifierSource fakeIdentifierSource() {
      return new FakeIdentifierSource();
    }
  }

  @Autowired private ProcessingService service;
  @Autowired private IdentifierIndex index;
  @Autowired private FakeIdentifierSource identifiers;
  @Autowired private ApplicationEventPublisher publisher;
  @Autowired private ClassifierProperties classifierProperties;
  @Autowired private ApplicationEvents events;

  @Test
  void theShippedClassifierRulesAreBound() {
    assertThat(classifierProperties.rules().offer().subject()).contains("offer", "final selects");
    assertThat(classifierProperties.rules().shortlist().body()).contains("shortlisted");
    assertThat(classifierProperties.knownRecruiters()).isEmpty();
  }

  @Test
  void identifiersChangedRebuildsTheIndexAndTheNextEmailMatches() {
    College college = ProcessingFixture.college("wired-college");
    UUID user = UUID.randomUUID();
    String neoId =
        "W" + UUID.randomUUID().toString().replace("-", "").substring(0, 7).toUpperCase();
    Mail mail = Mail.subject("Example Corp - Shortlist").text(neoId + " - Wired Student");

    assertThat(service.process(college, mail.email(), mail.content()).hits()).isEmpty();

    UUID identifierId = identifiers.add(college.getId(), user, neoId);
    publisher.publishEvent(new IdentifiersChanged(college.getId()));

    assertThat(index.lookup(college.getId(), neoId)).hasSize(1);
    ProcessingResult result = service.process(college, mail.email(), mail.content());
    assertThat(result.hits())
        .containsExactly(
            new MatchHit(user, identifierId, EventType.SHORTLIST, neoId + " - Wired Student"));
    assertThat(events.stream(MatchesFound.class))
        .singleElement()
        .satisfies(found -> assertThat(found.hits()).isEqualTo(result.hits()));
  }
}
