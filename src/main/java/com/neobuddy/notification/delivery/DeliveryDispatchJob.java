package com.neobuddy.notification.delivery;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the {@link DeliveryDispatcher} every 10 seconds. ShedLock lets one instance at a time do it;
 * if two ever ran together anyway (a lock that timed out), the claim in the outbox still keeps them
 * off each other's rows.
 */
@Component
@ConditionalOnProperty(
    prefix = "neobuddy.delivery",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class DeliveryDispatchJob {

  private final DeliveryDispatcher dispatcher;

  DeliveryDispatchJob(DeliveryDispatcher dispatcher) {
    this.dispatcher = dispatcher;
  }

  @Scheduled(
      initialDelayString = "${neobuddy.delivery.initial-delay:PT10S}",
      fixedDelayString = "${neobuddy.delivery.poll-interval:PT10S}")
  @SchedulerLock(name = "delivery-dispatcher", lockAtMostFor = "PT5M", lockAtLeastFor = "PT1S")
  void run() {
    dispatcher.dispatchDue();
  }
}
