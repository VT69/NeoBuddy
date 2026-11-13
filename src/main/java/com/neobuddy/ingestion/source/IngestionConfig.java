package com.neobuddy.ingestion.source;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the ingestion settings even when the scheduled poller is switched off. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({IngestionProperties.class, SourceBootstrapProperties.class})
class IngestionConfig {}
