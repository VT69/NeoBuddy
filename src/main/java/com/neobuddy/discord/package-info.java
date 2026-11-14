/**
 * The Discord bot: slash commands to link and manage a student's Discord channel, alerts by direct
 * message, and admin alerts in the #ops channel. Switched off entirely when no bot token is
 * configured (local runs, tests). Nothing depends on this module.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Discord")
package com.neobuddy.discord;
