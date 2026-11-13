package com.neobuddy.processing.classify;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ClassifierProperties.class)
class ClassifyConfig {}
