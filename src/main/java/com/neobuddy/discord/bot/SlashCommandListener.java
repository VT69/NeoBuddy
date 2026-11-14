package com.neobuddy.discord.bot;

import java.util.List;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the slash commands in the NeoBuddy server once the bot is connected, and answers them.
 * Every reply is ephemeral: deferred at once (Discord allows 3 seconds), then sent when the
 * database work is done. Commands from any other server are refused. Logs never carry user ids.
 */
class SlashCommandListener extends ListenerAdapter {

  private static final Logger log = LoggerFactory.getLogger(SlashCommandListener.class);

  static final List<CommandData> COMMANDS =
      List.of(
          Commands.slash(SlashCommands.LINK, "Link your Discord account to NeoBuddy")
              .addOption(
                  OptionType.STRING, "code", "The code shown on the NeoBuddy dashboard", true),
          Commands.slash(SlashCommands.STATUS, "Your identifiers and your last 3 alerts"),
          Commands.slash(SlashCommands.PAUSE, "Stop Discord alerts for now"),
          Commands.slash(SlashCommands.RESUME, "Restart Discord alerts"),
          Commands.slash(SlashCommands.UNLINK, "Stop alerts on Discord and forget this account"));

  private final SlashCommands commands;
  private final String guildId;

  SlashCommandListener(SlashCommands commands, String guildId) {
    this.commands = commands;
    this.guildId = guildId;
  }

  @Override
  public void onReady(ReadyEvent event) {
    Guild guild = event.getJDA().getGuildById(guildId);
    if (guild == null) {
      log.error(
          "Discord: the bot is not in the configured server {}; commands not registered", guildId);
      return;
    }
    guild
        .updateCommands()
        .addCommands(COMMANDS)
        .queue(
            done -> log.info("Discord: {} slash commands registered", done.size()),
            failure ->
                log.error(
                    "Discord: registering commands failed: {}",
                    failure.getClass().getSimpleName()));
  }

  @Override
  public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
    if (event.getGuild() == null || !guildId.equals(event.getGuild().getId())) {
      event.reply("NeoBuddy only answers in its own server.").setEphemeral(true).queue();
      return;
    }
    event.deferReply(true).queue();
    String reply;
    try {
      OptionMapping code = event.getOption("code");
      reply =
          commands.handle(
              event.getName(), event.getUser().getId(), code == null ? null : code.getAsString());
    } catch (RuntimeException e) {
      log.error("Discord: /{} failed: {}", event.getName(), e.getClass().getSimpleName());
      reply = "Something went wrong on our side. Try again in a moment.";
    }
    event.getHook().sendMessage(reply).setEphemeral(true).queue();
  }
}
