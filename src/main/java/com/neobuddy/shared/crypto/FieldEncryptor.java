package com.neobuddy.shared.crypto;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Encrypts single values (refresh tokens, channel addresses) with AES-256-GCM before they are
 * stored.
 *
 * <p>Stored layout: {@code [key id: 1 byte][IV: 12 bytes][ciphertext + 16-byte tag]}. Every value
 * gets a fresh random IV, so encrypting the same value twice gives different bytes. GCM
 * authenticates the data, and the key id is bound in as associated data, so any changed, truncated
 * or re-labelled value fails to decrypt instead of yielding garbage.
 *
 * <p>The key id is there for rotation: a later version can hold several keys, decrypt each value
 * with the key its first byte names, and encrypt new values with the newest. Today there is one
 * key, id {@value #CURRENT_KEY_ID}.
 */
@Component
public class FieldEncryptor {

  static final byte CURRENT_KEY_ID = 1;

  private static final String KEY_VARIABLE = "NEOBUDDY_ENCRYPTION_KEY";
  private static final String TRANSFORMATION = "AES/GCM/NoPadding";
  private static final int KEY_BYTES = 32;
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;
  private static final int HEADER_BYTES = 1 + IV_BYTES;
  private static final int MIN_BYTES = HEADER_BYTES + TAG_BITS / 8;

  private final SecretKey key;
  private final SecureRandom random = new SecureRandom();

  @Autowired
  public FieldEncryptor(EncryptionProperties properties) {
    this(properties.key());
  }

  /**
   * @param base64Key 32 random bytes in base64
   * @throws FieldEncryptionException if the key is missing or not a valid AES-256 key
   */
  public FieldEncryptor(String base64Key) {
    if (base64Key == null || base64Key.isBlank()) {
      throw new FieldEncryptionException(
          KEY_VARIABLE + " is not set. Generate one with: openssl rand -base64 32");
    }
    byte[] bytes;
    try {
      bytes = Base64.getDecoder().decode(base64Key.strip());
    } catch (IllegalArgumentException e) {
      throw new FieldEncryptionException(KEY_VARIABLE + " is not valid base64");
    }
    if (bytes.length != KEY_BYTES) {
      throw new FieldEncryptionException(
          KEY_VARIABLE + " must decode to " + KEY_BYTES + " bytes, but has " + bytes.length);
    }
    this.key = new SecretKeySpec(bytes, "AES");
  }

  /**
   * @return the encrypted value in the stored layout, or null for null
   */
  public byte[] encrypt(String plaintext) {
    if (plaintext == null) {
      return null;
    }
    byte[] iv = new byte[IV_BYTES];
    random.nextBytes(iv);
    try {
      Cipher cipher = cipher(Cipher.ENCRYPT_MODE, CURRENT_KEY_ID, iv);
      byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return ByteBuffer.allocate(HEADER_BYTES + ciphertext.length)
          .put(CURRENT_KEY_ID)
          .put(iv)
          .put(ciphertext)
          .array();
    } catch (GeneralSecurityException e) {
      throw new FieldEncryptionException("Could not encrypt a value", e);
    }
  }

  /**
   * @return the original value, or null for null
   * @throws FieldEncryptionException if the data was not produced by this key, or was changed
   */
  public String decrypt(byte[] stored) {
    if (stored == null) {
      return null;
    }
    if (stored.length < MIN_BYTES) {
      throw new FieldEncryptionException("Encrypted value is too short to be valid");
    }
    byte keyId = stored[0];
    if (keyId != CURRENT_KEY_ID) {
      throw new FieldEncryptionException("Encrypted value names an unknown key id");
    }
    try {
      Cipher cipher = cipher(Cipher.DECRYPT_MODE, keyId, slice(stored, 1, IV_BYTES));
      byte[] plaintext = cipher.doFinal(stored, HEADER_BYTES, stored.length - HEADER_BYTES);
      return new String(plaintext, StandardCharsets.UTF_8);
    } catch (GeneralSecurityException e) {
      // The cause is kept out on purpose: the message must not hint at how the check failed.
      throw new FieldEncryptionException("Could not decrypt a value: wrong key or altered data");
    }
  }

  private Cipher cipher(int mode, byte keyId, byte[] iv) throws GeneralSecurityException {
    Cipher cipher = Cipher.getInstance(TRANSFORMATION);
    cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, iv));
    cipher.updateAAD(new byte[] {keyId});
    return cipher;
  }

  private static byte[] slice(byte[] source, int from, int length) {
    byte[] out = new byte[length];
    System.arraycopy(source, from, out, 0, length);
    return out;
  }
}
