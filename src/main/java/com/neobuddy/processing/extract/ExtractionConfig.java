package com.neobuddy.processing.extract;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ExtractionProperties.class)
class ExtractionConfig {}
