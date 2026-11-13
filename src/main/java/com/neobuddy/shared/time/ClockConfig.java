package com.neobuddy.shared.time;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The one clock every module injects, so time can be controlled in tests. */
@Configuration(proxyBeanMethods = false)
class ClockConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
