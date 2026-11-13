package com.neobuddy.notification.delivery;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the outbox settings even when the scheduled dispatcher is switched off. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeliveryProperties.class)
class DeliveryConfig {}
