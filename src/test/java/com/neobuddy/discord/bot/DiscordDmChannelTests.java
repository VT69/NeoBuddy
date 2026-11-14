package com.neobuddy.discord.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.neobuddy.notification.Alert;
import com.neobuddy.notification.DeliveryResult;
import com.neobuddy.processing.EventType;
import com.neobuddy.subscriber.ChannelAddress;
import com.neobuddy.subscriber.ChannelType;
import java.time.Instant;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * The DM channel against mocked JDA objects: the embed, and how Discord's errors are classified.
 */
class DiscordDmChannelTests {

  private static final String USER_ID = "222222222222222222";
  private static final ChannelAddress TO = new ChannelAddress(USER_ID);

  private final JDA jda = mock(JDA.class);
  private final DiscordDmChannel channel = new DiscordDmChannel(jda);
  private CacheRestAction<User> retrieveUser;
  private PrivateChannel dm;
  private MessageCreateAction send;

  private static Alert alert(EventType type, String company) {
    return new Alert(
        type,
        company,
        "Example Corp - Offer letters",
        "N1B7J5V6 | Tara `Target` | 12 LPA",
        Instant.parse("2026-11-13T10:35:00Z"),
        "<abc@mail.example>");
  }

  @BeforeEach
  @SuppressWarnings("unchecked")
  void aConnectedBot() {
    when(jda.getStatus()).thenReturn(JDA.Status.CONNECTED);
    retrieveUser = mock(CacheRestAction.class);
    when(jda.retrieveUserById(USER_ID)).thenReturn(retrieveUser);
    User user = mock(User.class);
    when(retrieveUser.complete()).thenReturn(user);
    CacheRestAction<PrivateChannel> open = mock(CacheRestAction.class);
    when(user.openPrivateChannel()).thenReturn(open);
    dm = mock(PrivateChannel.class);
    when(open.complete()).thenReturn(dm);
    send = mock(MessageCreateAction.class);
    when(dm.sendMessageEmbeds(any(MessageEmbed.class))).thenReturn(send);
    when(dm.sendMessage(anyString())).thenReturn(send);
  }

  private static ErrorResponseException discordError(
      ErrorResponse response, boolean serverError, int code) {
    ErrorResponseException error = mock(ErrorResponseException.class);
    when(error.getErrorResponse()).thenReturn(response);
    when(error.isServerError()).thenReturn(serverError);
    when(error.getErrorCode()).thenReturn(code);
    return error;
  }

  @Test
  void isTheDiscordChannel() {
    assertThat(channel.type()).isEqualTo(ChannelType.DISCORD);
  }

  @Test
  void sendsAnEmbedWithHeadlineCompanyRowAndTime() {
    assertThat(channel.send(TO, alert(EventType.OFFER, "Example Corp")))
        .isEqualTo(DeliveryResult.success());

    ArgumentCaptor<MessageEmbed> sent = ArgumentCaptor.forClass(MessageEmbed.class);
    verify(dm).sendMessageEmbeds(sent.capture());
    verify(send).complete();
    MessageEmbed embed = sent.getValue();
    assertThat(embed.getTitle()).isEqualTo("Offer: Example Corp");
    assertThat(embed.getUrl())
        .isEqualTo("https://mail.google.com/mail/u/0/#search/rfc822msgid:abc%40mail.example");
    // The row in a code block; backticks in it cannot break out of the block.
    assertThat(embed.getDescription()).isEqualTo("```\nN1B7J5V6 | Tara 'Target' | 12 LPA\n```");
    assertThat(embed.getFields())
        .extracting(MessageEmbed.Field::getName, MessageEmbed.Field::getValue)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("Company", "Example Corp"),
            org.assertj.core.groups.Tuple.tuple("Received", "13 Nov 2026, 4:05 PM IST"));
    assertThat(embed.getColorRaw()).isEqualTo(0x1D7A46);
  }

  @Test
  void aTestAlertHasNoLinkAndNoCompany() {
    Alert test =
        new Alert(
            EventType.OTHER,
            null,
            "NeoBuddy test alert",
            "This is a test.",
            Instant.parse("2026-11-13T10:35:00Z"),
            "<test-1@neobuddy.invalid>",
            true);

    channel.send(TO, test);

    ArgumentCaptor<MessageEmbed> sent = ArgumentCaptor.forClass(MessageEmbed.class);
    verify(dm).sendMessageEmbeds(sent.capture());
    assertThat(sent.getValue().getTitle()).isEqualTo("Test alert: NeoBuddy can reach you here");
    assertThat(sent.getValue().getUrl()).isNull();
    assertThat(sent.getValue().getFields())
        .extracting(MessageEmbed.Field::getName)
        .containsExactly("Received");
  }

  @Test
  void dmsClosedIsPermanentWithTheCodeTheDashboardExplains() {
    ErrorResponseException error = discordError(ErrorResponse.CANNOT_SEND_TO_USER, false, 50007);
    when(send.complete()).thenThrow(error);

    assertThat(channel.send(TO, alert(EventType.SHORTLIST, "X")))
        .isEqualTo(DeliveryResult.permanent("DISCORD_DMS_CLOSED"));
  }

  @Test
  void anUnknownUserIsPermanent() {
    ErrorResponseException error = discordError(ErrorResponse.UNKNOWN_USER, false, 10013);
    when(retrieveUser.complete()).thenThrow(error);

    assertThat(channel.send(TO, alert(EventType.SHORTLIST, "X")))
        .isEqualTo(DeliveryResult.permanent("DISCORD_UNKNOWN_USER"));
  }

  @Test
  void aDiscordServerErrorIsRetryable() {
    ErrorResponseException error = discordError(ErrorResponse.SERVER_ERROR, true, 0);
    when(send.complete()).thenThrow(error);

    assertThat(channel.send(TO, alert(EventType.SHORTLIST, "X")))
        .isEqualTo(DeliveryResult.retryable("DISCORD_SERVER_ERROR"));
  }

  @Test
  void anyOtherRefusalIsPermanentWithItsCodeOnly() {
    ErrorResponseException error = discordError(ErrorResponse.MISSING_ACCESS, false, 50001);
    when(send.complete()).thenThrow(error);

    DeliveryResult result = channel.send(TO, alert(EventType.SHORTLIST, "X"));

    assertThat(result).isEqualTo(DeliveryResult.permanent("DISCORD 50001"));
    assertThat(result.reason()).doesNotContain(USER_ID);
  }

  @Test
  void aNetworkFailureIsRetryable() {
    when(send.complete())
        .thenThrow(
            new java.io.UncheckedIOException(new java.net.SocketTimeoutException("timeout")));

    assertThat(channel.send(TO, alert(EventType.SHORTLIST, "X")))
        .isEqualTo(DeliveryResult.retryable("DISCORD UncheckedIOException"));
  }

  @Test
  void whileTheBotIsNotConnectedNothingIsTriedAndItIsRetryable() {
    when(jda.getStatus()).thenReturn(JDA.Status.CONNECTING_TO_WEBSOCKET);

    assertThat(channel.send(TO, alert(EventType.SHORTLIST, "X")))
        .isEqualTo(DeliveryResult.retryable("DISCORD_NOT_CONNECTED"));
    verify(jda, never()).retrieveUserById(anyString());
  }

  @Test
  void aPlainTextDmUsesTheSamePath() {
    assertThat(channel.sendText(USER_ID, "You're linked!")).isEqualTo(DeliveryResult.success());
    verify(dm).sendMessage("You're linked!");
  }
}
