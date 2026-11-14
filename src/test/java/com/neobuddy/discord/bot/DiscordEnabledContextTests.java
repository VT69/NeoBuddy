package com.neobuddy.discord.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.neobuddy.ingestion.SourceAuthExpired;
import com.neobuddy.notification.NotificationChannel;
import com.neobuddy.shared.college.College;
import com.neobuddy.subscriber.ChannelType;
import com.neobuddy.web.WebDatabaseTest;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The application with the Discord bot switched on and only the JDA client mocked: the module wires
 * up, the dashboard offers Discord, a code from the website links the account through /link, test
 * alerts can go to Discord, and admin alerts reach #ops.
 */
@TestPropertySource(
    properties = {
      "neobuddy.discord.token=test-token-not-real",
      "neobuddy.discord.guild-id=111111111111111111",
      "neobuddy.discord.ops-channel-id=333333333333333333",
      "neobuddy.discord.invite-url=https://discord.gg/neobuddy-test"
    })
class DiscordEnabledContextTests extends WebDatabaseTest {

  @MockitoBean private JDA jda;

  @Autowired private SlashCommands commands;
  @Autowired private List<NotificationChannel> channels;
  @Autowired private TransactionTemplate transactions;
  @Autowired private ApplicationEventPublisher publisher;

  private College college;
  private Student student;
  private String discordUser;

  @BeforeEach
  void aStudent() {
    college = newCollege();
    student = newStudent(college);
    discordUser =
        Long.toString(
            ThreadLocalRandom.current()
                .nextLong(100_000_000_000_000_000L, 999_999_999_999_999_999L));
  }

  private String linkCode() throws Exception {
    String json =
        mvc.perform(post("/api/v1/me/channels/discord/link-code").with(as(student)).with(csrf()))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return JsonPath.read(json, "$.code");
  }

  @Test
  void theDiscordChannelIsAvailableForDelivery() {
    assertThat(channels).extracting(NotificationChannel::type).contains(ChannelType.DISCORD);
  }

  @Test
  void theLinkCodeEndpointReturnsTheCodeCommandAndInvite() throws Exception {
    mvc.perform(
            post("/api/v1/me/channels/discord/link-code")
                .with(as(student))
                .with(csrf())
                .accept(MediaType.APPLICATION_JSON))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.code", matchesPattern("[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}")))
        .andExpect(
            jsonPath("$.command", matchesPattern("/link code:[A-HJ-NP-Z2-9]{4}-[A-HJ-NP-Z2-9]{4}")))
        .andExpect(jsonPath("$.inviteUrl").value("https://discord.gg/neobuddy-test"))
        .andExpect(jsonPath("$.expiresAt").isNotEmpty());
  }

  @Test
  void theDashboardOffersDiscordAndHtmxShowsTheCode() throws Exception {
    mvc.perform(get("/dashboard").with(as(student)))
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("Connect Discord"),
                        containsString("/channels/discord/link-code"))));

    mvc.perform(
            post("/api/v1/me/channels/discord/link-code")
                .header("HX-Request", "true")
                .with(as(student))
                .with(csrf()))
        .andExpect(status().isCreated())
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("/link code:"),
                        containsString("https://discord.gg/neobuddy-test"),
                        containsString("works once, for 10 minutes"))));
  }

  @Test
  void atMostFiveLinkCodesAnHour() throws Exception {
    for (int i = 0; i < 5; i++) {
      linkCode();
    }

    mvc.perform(post("/api/v1/me/channels/discord/link-code").with(as(student)).with(csrf()))
        .andExpect(status().isTooManyRequests())
        .andExpect(jsonPath("$.type").value("/problems/rate-limited"));
  }

  @Test
  void aCodeFromTheWebsiteLinksTheAccountAndATestAlertCanGoToDiscord() throws Exception {
    String reply = commands.handle("link", discordUser, linkCode());

    // The mocked bot is not connected, so the confirmation DM could not go out.
    assertThat(reply).contains("Linked, but we could not DM you");
    mvc.perform(get("/api/v1/me").with(as(student)))
        .andExpect(jsonPath("$.channels[0].type").value("DISCORD"))
        .andExpect(jsonPath("$.channels[0].verified").value(true));
    mvc.perform(get("/dashboard").with(as(student)))
        .andExpect(content().string(allOf(containsString("/unlink"), containsString("Send test"))));

    UUID channel = subscribers.get(student.id()).channels().get(0).id();
    mvc.perform(post("/api/v1/me/channels/" + channel + "/test").with(as(student)).with(csrf()))
        .andExpect(status().isAccepted());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from delivery where channel_id = ?", Integer.class, channel))
        .isEqualTo(1);
  }

  @Test
  void whenDiscordRefusedTheDmTheDashboardSaysHowToFixIt() throws Exception {
    commands.handle("link", discordUser, linkCode());
    UUID channel = subscribers.get(student.id()).channels().get(0).id();
    mvc.perform(post("/api/v1/me/channels/" + channel + "/test").with(as(student)).with(csrf()));
    jdbc.update(
        "update delivery set status = 'DEAD', attempts = 1, last_error = 'DISCORD_DMS_CLOSED' where"
            + " channel_id = ?",
        channel);

    mvc.perform(get("/dashboard").with(as(student)))
        .andExpect(
            content()
                .string(
                    allOf(
                        containsString("Privacy Settings"),
                        containsString("allow DMs from server members"),
                        containsString("Discord: DMs closed"))));
  }

  @Test
  void aSourceThatLostAccessIsPostedInOps() {
    TextChannel ops = mock(TextChannel.class);
    MessageCreateAction message = mock(MessageCreateAction.class);
    when(jda.getTextChannelById("333333333333333333")).thenReturn(ops);
    when(ops.sendMessage(anyString())).thenReturn(message);
    UUID source = UUID.randomUUID();

    transactions.executeWithoutResult(
        tx -> publisher.publishEvent(new SourceAuthExpired(source, college.getId())));

    await()
        .atMost(Duration.ofSeconds(30))
        .untilAsserted(() ->
            verify(ops).sendMessage(org.mockito.ArgumentMatchers.contains(source.toString())));
  }
}
