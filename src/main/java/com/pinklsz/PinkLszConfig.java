package com.pinklsz;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(PinkLszConfig.GROUP)
public interface PinkLszConfig extends Config
{
	String GROUP = "pinklsz";

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
