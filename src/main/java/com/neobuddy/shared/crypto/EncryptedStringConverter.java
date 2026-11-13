package com.neobuddy.shared.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * Stores a String attribute encrypted in a {@code bytea} column. Use it explicitly:
 *
 * <pre>
 * &#64;Convert(converter = EncryptedStringConverter.class)
 * &#64;Column(name = "address_enc", nullable = false)
 * private String address;
 * </pre>
 *
 * <p>Hibernate compares the decrypted values when checking for changes, so an unchanged attribute
 * is not rewritten even though encrypting it again would give different bytes.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, byte[]> {

  private final FieldEncryptor encryptor;

  public EncryptedStringConverter(FieldEncryptor encryptor) {
    this.encryptor = encryptor;
  }

  @Override
  public byte[] convertToDatabaseColumn(String attribute) {
    return encryptor.encrypt(attribute);
  }

  @Override
  public String convertToEntityAttribute(byte[] dbData) {
    return encryptor.decrypt(dbData);
  }
}
