package com.neobuddy.ingestion.source;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Applies the source part of the bootstrap at startup, right after the college and admin have been
 * seeded (that runner has the highest precedence; this one comes next).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class SourceBootstrapRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(SourceBootstrapRunner.class);

  private final SourceBootstrap bootstrap;
  private final SourceBootstrapProperties properties;

  SourceBootstrapRunner(SourceBootstrap bootstrap, SourceBootstrapProperties properties) {
    this.bootstrap = bootstrap;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    // Neither the address nor the token is logged.
    switch (bootstrap.apply(properties)) {
      case NOT_CONFIGURED ->
          log.info("Bootstrap: no source inbox configured (neobuddy.bootstrap.source is empty)");
      case CREATED -> log.info("Bootstrap: source inbox created; it starts from its first poll");
      case RECONNECTED -> log.info("Bootstrap: source inbox reconnected with a new refresh token");
      case UNCHANGED -> log.info("Bootstrap: source inbox already present");
    }
  }
}
