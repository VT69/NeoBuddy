package com.neobuddy.shared.crypto;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class FieldEncryptorTests {

  private static final String KEY = key(1);
  private static final String OTHER_KEY = key(2);
  private static final String SECRET = "ntfy-topic-3f9a1c77-fictional";

  private final FieldEncryptor encryptor = new FieldEncryptor(KEY);

  @ParameterizedTest
  @ValueSource(strings = {"", "a", SECRET, "1//refresh-token-with/slashes+and=padding", "Zoë ☃"})
  void decryptReturnsWhatWasEncrypted(String value) {
    assertThat(encryptor.decrypt(encryptor.encrypt(value))).isEqualTo(value);
  }

  @Test
  void aLongValueRoundTrips() {
    String value = "x".repeat(10_000);

    assertThat(encryptor.decrypt(encryptor.encrypt(value))).isEqualTo(value);
  }

  @Test
  void nullStaysNull() {
    assertThat(encryptor.encrypt(null)).isNull();
    assertThat(encryptor.decrypt(null)).isNull();
  }

  @Test
  void theStoredLayoutIsKeyIdThenIvThenCiphertextAndTag() {
    byte[] stored = encryptor.encrypt(SECRET);

    assertThat(stored[0]).isEqualTo(FieldEncryptor.CURRENT_KEY_ID).isEqualTo((byte) 1);
    assertThat(stored).hasSize(1 + 12 + SECRET.getBytes(UTF_8).length + 16);
  }

  @Test
  void thePlaintextIsNotVisibleInTheStoredBytes() {
    byte[] stored = encryptor.encrypt(SECRET);

    assertThat(new String(stored, java.nio.charset.StandardCharsets.ISO_8859_1))
        .doesNotContain(SECRET)
        .doesNotContain("ntfy");
  }

  @Test
  void everyEncryptionUsesAFreshIvSoEqualValuesLookDifferent() {
    Set<String> ivs = new HashSet<>();
    Set<String> outputs = new HashSet<>();
    for (int i = 0; i < 200; i++) {
      byte[] stored = encryptor.encrypt(SECRET);
      ivs.add(Base64.getEncoder().encodeToString(Arrays.copyOfRange(stored, 1, 13)));
      outputs.add(Base64.getEncoder().encodeToString(stored));
    }

    assertThat(ivs).hasSize(200);
    assertThat(outputs).hasSize(200);
  }

  @Test
  void everySingleFlippedBitIsDetected() {
    byte[] stored = encryptor.encrypt(SECRET);

    // Key id, IV, ciphertext and tag: a change anywhere must fail, never return altered text.
    for (int position = 0; position < stored.length; position++) {
      byte[] tampered = stored.clone();
      tampered[position] ^= 0x01;
      assertThatThrownBy(() -> encryptor.decrypt(tampered))
          .as("bit flipped at byte %d", position)
          .isInstanceOf(FieldEncryptionException.class);
    }
  }

  @Test
  void truncatedOrExtendedDataIsRejected() {
    byte[] stored = encryptor.encrypt(SECRET);

    assertThatThrownBy(() -> encryptor.decrypt(Arrays.copyOf(stored, stored.length - 1)))
        .isInstanceOf(FieldEncryptionException.class);
    assertThatThrownBy(() -> encryptor.decrypt(Arrays.copyOf(stored, stored.length + 1)))
        .isInstanceOf(FieldEncryptionException.class);
    assertThatThrownBy(() -> encryptor.decrypt(Arrays.copyOf(stored, 20)))
        .isInstanceOf(FieldEncryptionException.class)
        .hasMessageContaining("too short");
    assertThatThrownBy(() -> encryptor.decrypt(new byte[0]))
        .isInstanceOf(FieldEncryptionException.class);
  }

  @Test
  void aValueEncryptedWithAnotherKeyCannotBeRead() {
    byte[] stored = new FieldEncryptor(OTHER_KEY).encrypt(SECRET);

    assertThatThrownBy(() -> encryptor.decrypt(stored))
        .isInstanceOf(FieldEncryptionException.class)
        .hasMessage("Could not decrypt a value: wrong key or altered data")
        .hasNoCause();
  }

  @Test
  void anUnknownKeyIdIsRejectedBeforeAnyDecryption() {
    byte[] stored = encryptor.encrypt(SECRET);
    stored[0] = 2;

    assertThatThrownBy(() -> encryptor.decrypt(stored))
        .isInstanceOf(FieldEncryptionException.class)
        .hasMessageContaining("unknown key id");
  }

  @Test
  void failuresNeverMentionTheValueOrTheKey() {
    byte[] stored = encryptor.encrypt(SECRET);
    stored[stored.length - 1] ^= 0x01;

    assertThatThrownBy(() -> encryptor.decrypt(stored))
        .hasMessageNotContaining(SECRET)
        .hasMessageNotContaining(KEY);
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"   "})
  void aMissingKeyFailsWithInstructions(String key) {
    assertThatThrownBy(() -> new FieldEncryptor(key))
        .isInstanceOf(FieldEncryptionException.class)
        .hasMessageContaining("NEOBUDDY_ENCRYPTION_KEY is not set")
        .hasMessageContaining("openssl rand -base64 32");
  }

  @Test
  void aKeyOfTheWrongSizeOrShapeIsRejectedWithoutEchoingIt() {
    String sixteenBytes = Base64.getEncoder().encodeToString(new byte[16]);

    assertThatThrownBy(() -> new FieldEncryptor(sixteenBytes))
        .isInstanceOf(FieldEncryptionException.class)
        .hasMessageContaining("must decode to 32 bytes, but has 16")
        .hasMessageNotContaining(sixteenBytes);
    assertThatThrownBy(() -> new FieldEncryptor("not base64 !!"))
        .isInstanceOf(FieldEncryptionException.class)
        .hasMessageContaining("not valid base64")
        .hasMessageNotContaining("not base64 !!");
  }

  @Test
  void theKeyMayCarrySurroundingWhitespace() {
    byte[] stored = new FieldEncryptor("  " + KEY + "\n").encrypt(SECRET);

    assertThat(encryptor.decrypt(stored)).isEqualTo(SECRET);
  }

  @Test
  void propertiesNeverPrintTheKey() {
    assertThat(new EncryptionProperties(KEY).toString()).doesNotContain(KEY).contains("set");
    assertThat(new EncryptionProperties(null).toString()).contains("unset");
  }

  @Test
  void theConverterEncryptsOnTheWayInAndDecryptsOnTheWayOut() {
    EncryptedStringConverter converter = new EncryptedStringConverter(encryptor);

    byte[] column = converter.convertToDatabaseColumn(SECRET);

    assertThat(column).isNotEqualTo(SECRET.getBytes(UTF_8));
    assertThat(converter.convertToEntityAttribute(column)).isEqualTo(SECRET);
    assertThat(converter.convertToDatabaseColumn(null)).isNull();
    assertThat(converter.convertToEntityAttribute(null)).isNull();
  }

  private static String key(int fill) {
    byte[] bytes = new byte[32];
    Arrays.fill(bytes, (byte) fill);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
