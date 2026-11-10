package com.pinklsz;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(PinkLszConfig.GROUP)
public interface PinkLszConfig extends Config
{
	String GROUP = "pinklsz";

	@ConfigItem(
		keyName = "connect",
		name = "Connect to the clan site",
		description = "Needed for personal bests, drops, TeamFinder, events and bingo. Nothing is sent to pinkosrs.com until this is on.",
		warning = "Enabling this option submits your IP address and various other account data to a 3rd-party server not controlled or verified by RuneLite developers.",
		position = 0
	)
	default boolean connect()
	{
		return false;
	}

	@ConfigItem(
		keyName = "dashboardUrl",
		name = "Dashboard address",
		description = "Where the Pink LSZ dashboard lives. You shouldn't need to change this.",
		position = 1
	)
	default String dashboardUrl()
	{
		return "https://pinkosrs.com";
	}

	@ConfigItem(
		keyName = "trackDeaths",
		name = "Track deaths",
		description = "Send your deaths at bosses and raids (with a screenshot) so the dashboard can show kill/death ratios.",
		position = 2
	)
	default boolean trackDeaths()
	{
		return true;
	}

	@ConfigItem(
		keyName = "shareGear",
		name = "Share gear for the DPS calculator",
		description = "Send your combat stats, worn gear, inventory and rune pouch so the clan site's DPS calculator can load them.",
		position = 4
	)
	default boolean shareGear()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTeamAlerts",
		name = "Show TeamFinder alerts",
		description = "Show a chat line when a clan member starts a team. This is separate from clan announcements.",
		position = 7
	)
	default boolean showTeamAlerts()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyInvites",
		name = "Notify me of team invites",
		description = "Send a RuneLite notification when a host invites you to a team while you are marked available. It includes the host's world and its region.",
		position = 8
	)
	default boolean notifyInvites()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showAnnouncements",
		name = "Show clan announcements",
		description = "Show messages the clan leaders send from the dashboard in your chatbox.",
		position = 5
	)
	default boolean showAnnouncements()
	{
		return true;
	}

	@ConfigItem(
		keyName = "chatNotes",
		name = "Show messages in chat",
		description = "Print a line in your chatbox each time something is sent, or when it can't be.",
		position = 3
	)
	default boolean chatNotes()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideSidePanel",
		name = "Hide Side Panel",
		description = "Hide the Pink LSZ TeamFinder button from the RuneLite sidebar. Everything else keeps working.",
		position = 6
	)
	default boolean hideSidePanel()
	{
		return false;
	}
}
