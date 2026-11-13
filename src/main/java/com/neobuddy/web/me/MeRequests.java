package com.neobuddy.web.me;

import com.neobuddy.subscriber.IdentifierType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies of {@code /api/v1/me}. The same records bind JSON and htmx form posts. */
public final class MeRequests {

  private MeRequests() {}

  public record AddIdentifier(
      @NotNull(message = "Choose the type of identifier.") IdentifierType type,
      @NotBlank(message = "Enter a value.") @Size(max = 320, message = "That is too long.")
          String value) {}

  public record StartEmail(
      @NotBlank(message = "Enter an email address.")
          @Email(message = "Enter a valid email address.")
          @Size(max = 254, message = "That address is too long.")
          String address) {}

  public record VerifyEmail(
      @NotBlank(message = "Enter the 6-digit code.")
          @Pattern(regexp = "\\s*\\d{6}\\s*", message = "The code is 6 digits.")
          String code) {}

  public record PatchChannel(
      @NotNull(message = "Say whether the channel is on.") Boolean enabled) {}
}
