package com.neobuddy.subscriber.bootstrap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Applies the bootstrap configuration once at every startup. Runs first among the runners, so
 * anything else that seeds data (the mail source) finds the college in place.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@EnableConfigurationProperties(BootstrapProperties.class)
class BootstrapRunner implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

  private final Bootstrap bootstrap;
  private final BootstrapProperties properties;

  BootstrapRunner(Bootstrap bootstrap, BootstrapProperties properties) {
    this.bootstrap = bootstrap;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    Bootstrap.Report report = bootstrap.apply(properties);
    if (!report.configured()) {
      log.info("Bootstrap: no college configured (neobuddy.bootstrap.college.slug is empty)");
      return;
    }
    // The slug is configuration, not personal data. Nothing about the admin is logged.
    log.info(
        "Bootstrap: college '{}' {}, admin {}",
        properties.college().slug().strip(),
        report.collegeCreated() ? "created" : "already present",
        report.adminCreated()
            ? "created"
            : (properties.admin().email() == null || properties.admin().email().isBlank()
                ? "not configured"
                : "already present"));
  }
}
