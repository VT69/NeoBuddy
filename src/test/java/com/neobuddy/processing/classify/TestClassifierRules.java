package com.neobuddy.processing.classify;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

/**
 * Binds {@link ClassifierProperties} the way Spring Boot does, without starting an application
 * context, so pure tests run against the real rules shipped in {@code application.yml}.
 */
public final class TestClassifierRules {

  private TestClassifierRules() {}

  /** The rules the application actually ships with. */
  public static ClassifierProperties shipped() {
    return bind(new ClassPathResource("application.yml"));
  }

  /** Rules from a YAML snippet rooted at {@code neobuddy.classifier}. */
  public static ClassifierProperties fromYaml(String yaml) {
    return bind(new ByteArrayResource(yaml.getBytes(StandardCharsets.UTF_8)));
  }

  public static KeywordEmailClassifier classifier(ClassifierProperties properties) {
    return new KeywordEmailClassifier(properties, new CompanyNameResolver(properties));
  }

  private static ClassifierProperties bind(Resource resource) {
    try {
      List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("test", resource);
      return new Binder(ConfigurationPropertySources.from(sources))
          .bind("neobuddy.classifier", ClassifierProperties.class)
          .orElseGet(() -> new ClassifierProperties(null, null, null));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
