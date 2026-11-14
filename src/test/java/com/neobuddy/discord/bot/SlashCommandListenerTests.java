package com.neobuddy.discord.bot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collection;
import java.util.List;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData;
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction;
import net.dv8tion.jda.api.requests.restaction.WebhookMessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.interactions.ReplyCallbackAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

/** Each command reaches the right handler and is answered ephemerally; nothing else is answered. */
class SlashCommandListenerTests {

  private static final String GUILD = "111111111111111111";
  private static final String USER = "222222222222222222";

  private final SlashCommands commands = mock(SlashCommands.class);
  private final SlashCommandListener listener = new SlashCommandListener(commands, GUILD);

  private SlashCommandInteractionEvent event;
  private ReplyCallbackAction deferred;
  private WebhookMessageCreateAction<?> followUp;

  @BeforeEach
  @SuppressWarnings({"unchecked", "rawtypes"})
  void anInteraction() {
    event = mock(SlashCommandInteractionEvent.class);
    Guild guild = mock(Guild.class);
    when(guild.getId()).thenReturn(GUILD);
    when(event.getGuild()).thenReturn(guild);
    User user = mock(User.class);
    when(user.getId()).thenReturn(USER);
    when(event.getUser()).thenReturn(user);
    deferred = mock(ReplyCallbackAction.class, RETURNS_SELF);
    when(event.deferReply(anyBoolean())).thenReturn(deferred);
    InteractionHook hook = mock(InteractionHook.class);
    when(event.getHook()).thenReturn(hook);
    followUp = mock(WebhookMessageCreateAction.class, RETURNS_SELF);
    when(hook.sendMessage(anyString())).thenReturn((WebhookMessageCreateAction) followUp);
  }

  private String replied() {
    ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
    verify(event.getHook()).sendMessage(text.capture());
    verify(followUp).setEphemeral(true);
    verify(followUp).queue();
    return text.getValue();
  }

  @Test
  void linkPassesTheCodeAndAnswersEphemerally() {
    when(event.getName()).thenReturn("link");
    OptionMapping code = mock(OptionMapping.class);
    when(code.getAsString()).thenReturn("ABCD-EFGH");
    when(event.getOption("code")).thenReturn(code);
    when(commands.handle("link", USER, "ABCD-EFGH")).thenReturn("Linked.");

    listener.onSlashCommandInteraction(event);

    verify(event).deferReply(true);
    verify(deferred).queue();
    assertThat(replied()).isEqualTo("Linked.");
  }

  @ParameterizedTest
  @ValueSource(strings = {"status", "pause", "resume", "unlink"})
  void theOtherCommandsHaveNoOption(String command) {
    when(event.getName()).thenReturn(command);
    when(commands.handle(command, USER, null)).thenReturn("Done: " + command);

    listener.onSlashCommandInteraction(event);

    verify(event).deferReply(true);
    assertThat(replied()).isEqualTo("Done: " + command);
  }

  @Test
  void aFailureIsAnsweredWithoutDetails() {
    when(event.getName()).thenReturn("status");
    when(commands.handle(any(), any(), any()))
        .thenThrow(new IllegalStateException("db down: secret"));

    listener.onSlashCommandInteraction(event);

    assertThat(replied()).isEqualTo("Something went wrong on our side. Try again in a moment.");
  }

  @Test
  void anotherServerIsNotAnswered() {
    Guild other = mock(Guild.class);
    when(other.getId()).thenReturn("999999999999999999");
    when(event.getGuild()).thenReturn(other);
    when(event.getName()).thenReturn("link");
    ReplyCallbackAction refusal = mock(ReplyCallbackAction.class, RETURNS_SELF);
    when(event.reply(anyString())).thenReturn(refusal);

    listener.onSlashCommandInteraction(event);

    verify(event).reply("NeoBuddy only answers in its own server.");
    verify(refusal).setEphemeral(true);
    verify(commands, never()).handle(any(), any(), any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void whenReadyItRegistersTheFiveCommandsInTheServer() {
    ReadyEvent ready = mock(ReadyEvent.class);
    JDA jda = mock(JDA.class);
    Guild guild = mock(Guild.class);
    CommandListUpdateAction update = mock(CommandListUpdateAction.class, RETURNS_SELF);
    when(ready.getJDA()).thenReturn(jda);
    when(jda.getGuildById(GUILD)).thenReturn(guild);
    when(guild.updateCommands()).thenReturn(update);

    listener.onReady(ready);

    ArgumentCaptor<Collection<CommandData>> registered = ArgumentCaptor.forClass(Collection.class);
    verify(update).addCommands(registered.capture());
    assertThat(registered.getValue())
        .extracting(CommandData::getName)
        .containsExactly("link", "status", "pause", "resume", "unlink");
    SlashCommandData link = (SlashCommandData) List.copyOf(registered.getValue()).get(0);
    assertThat(link.getOptions())
        .singleElement()
        .satisfies(option -> {
          assertThat(option.getName()).isEqualTo("code");
          assertThat(option.isRequired()).isTrue();
        });
    verify(update).queue(any(), any());
  }

  @Test
  void whenTheBotIsNotInTheServerNothingIsRegistered() {
    ReadyEvent ready = mock(ReadyEvent.class);
    JDA jda = mock(JDA.class);
    when(ready.getJDA()).thenReturn(jda);

    listener.onReady(ready);

    verify(jda).getGuildById(eq(GUILD));
  }
}
