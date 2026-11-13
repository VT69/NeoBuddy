package com.neobuddy.shared.crypto;

/**
 * A value could not be encrypted or decrypted: wrong key, tampered or truncated data, or an unknown
 * key id. Messages never contain the value or the key.
 */
public class FieldEncryptionException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public FieldEncryptionException(String message) {
    super(message);
  }

  public FieldEncryptionException(String message, Throwable cause) {
    super(message, cause);
  }
}
