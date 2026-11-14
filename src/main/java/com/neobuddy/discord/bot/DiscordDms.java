package com.neobuddy.discord.bot;

import com.neobuddy.notification.DeliveryResult;

/** Sends a plain direct message, e.g. the confirmation after /link. */
interface DiscordDms {

  DeliveryResult sendText(String discordUserId, String text);
}
