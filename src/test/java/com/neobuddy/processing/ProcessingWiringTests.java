package com.neobuddy.processing;

import static org.assertj.core.api.Assertions.assertThat;

import com.neobuddy.processing.ProcessingFixture.Mail;
import com.neobuddy.processing.classify.ClassifierProperties;
import com.neobuddy.processing.match.IdentifierIndex;
import com.neobuddy.shared.college.College;
import com.neobuddy.shared.college.CollegeRepository;
import com.neobuddy.subscriber.IdentifierType;
import com.neobuddy.subscriber.OidcClaims;
import com.neobuddy.subscriber.SubscriberService;
import com.neobuddy.subscriber.SubscriberView;
import com.neobuddy.subscriber.SubscriberView.IdentifierView;
import com.neobuddy.subscriber.TestColleges;
import com.neobuddy.testsupport.DatabaseTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/**
 * The pipeline as Spring wires it in the real application context, with the subscriber module
 * behind the {@link IdentifierSource} port: a student registers an identifier, the index picks it
 * up, and the next email about them matches.
 */
@DatabaseTest
@RecordApplicationEvents
class ProcessingWiringTests {

  @Autowired private ProcessingService service;
  @Autowired private IdentifierIndex index;
  @Autowired private SubscriberService subscribers;
  @Autowired private CollegeRepository colleges;
  @Autowired private ClassifierProperties classifierProperties;
  @Autowired private ApplicationEvents events;

  @Test
  void theShippedClassifierRulesAreBound() {
    assertThat(classifierProperties.rules().offer().subject()).contains("offer", "final selects");
    assertThat(classifierProperties.rules().shortlist().body()).contains("shortlisted");
    assertThat(classifierProperties.knownRecruiters()).isEmpty();
  }

  @Test
  void aNewlyRegisteredIdentifierMatchesTheNextEmailAndStopsWhenRemoved() {
    College college = colleges.save(TestColleges.pilot());
    SubscriberView student =
        subscribers.registerFromOidc(
            new OidcClaims(
                "sub-" + UUID.randomUUID(), TestColleges.studentEmail(college), true, "W", null));
    String neoId = TestColleges.neoId();
    Mail mail = Mail.subject("Example Corp - Shortlist").text(neoId + " - Wired Student");

    assertThat(service.process(college, mail.email(), mail.content()).hits()).isEmpty();

    // Adding the identifier publishes IdentifiersChanged; the index rebuilds after the commit.
    IdentifierView identifier =
        subscribers.addIdentifier(student.id(), IdentifierType.NEO_ID, neoId);

    assertThat(index.lookup(college.getId(), neoId)).hasSize(1);
    ProcessingResult result = service.process(college, mail.email(), mail.content());
    assertThat(result.hits())
        .containsExactly(
            new MatchHit(
                student.id(), identifier.id(), EventType.SHORTLIST, neoId + " - Wired Student"));
    assertThat(events.stream(MatchesFound.class))
        .singleElement()
        .satisfies(found -> assertThat(found.hits()).isEqualTo(result.hits()));

    subscribers.pause(student.id());
    assertThat(service.process(college, mail.email(), mail.content()).hits()).isEmpty();

    subscribers.resume(student.id());
    assertThat(service.process(college, mail.email(), mail.content()).hits()).hasSize(1);

    subscribers.removeIdentifier(student.id(), identifier.id());
    assertThat(index.lookup(college.getId(), neoId)).isEmpty();
    assertThat(service.process(college, mail.email(), mail.content()).hits()).isEmpty();
  }
}
