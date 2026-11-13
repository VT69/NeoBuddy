package com.neobuddy.shared.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Field-encryption settings ({@code neobuddy.encryption.*}).
 *
 * @param key the AES-256 key, 32 random bytes in base64, from env {@code NEOBUDDY_ENCRYPTION_KEY}
 */
@ConfigurationProperties(prefix = "neobuddy.encryption")
public record EncryptionProperties(String key) {

  /** Never prints the key. */
  @Override
  public String toString() {
    return "EncryptionProperties[key=" + (key == null || key.isBlank() ? "unset" : "set") + "]";
  }
}
