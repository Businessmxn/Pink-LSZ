package com.pinklsz.collect;

import com.google.gson.JsonObject;
import com.pinklsz.net.Dashboard;
import com.pinklsz.net.Kind;
import com.pinklsz.net.Session;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/** Boss kill counts: changed ones after a kill, everything once per login. */
@Singleton
public class KillCollector
{
	@Inject
	private Client client;

	@Inject
	private Dashboard dashboard;

	@Inject
	private Session session;

	// The Fractured Archive has no kill count in the game's saved values, so each finished raid is counted from the chat line:
	// "Your completed The Fractured Archive count is: 3."  /  "Your completion count for The Fractured Archive is: 3."
	private static final Pattern ARCHIVE = Pattern.compile("^Your (?:completed |completion count for )(?:the )?fractured archive(?: count)?(?: is)?: ?([\\d,]+)\\.?$", Pattern.CASE_INSENSITIVE);

	private final Set<String> changed = new HashSet<>();

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if ((e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM) || !session.settled())
		{
			return;
		}
		Matcher m = ARCHIVE.matcher(Text.removeTags(e.getMessage()).trim());
		if (!m.matches())
		{
			return;
		}
		int count;
		try
		{
			count = Integer.parseInt(m.group(1).replace(",", ""));
		}
		catch (NumberFormatException ex)
		{
			return;
		}
		JsonObject kills = new JsonObject();
		kills.addProperty("the_fractured_archive", count);
		JsonObject data = new JsonObject();
		data.add("kills", kills);
		data.addProperty("completion", true); // one raid just finished: the site counts it from the one before
		dashboard.send(Kind.KILL_COUNTS, data);
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (!session.settled())
		{
			return;
		}
		String boss = BossVarps.bossFor(e.getVarpId());
		if (boss != null)
		{
			changed.add(boss);
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (changed.isEmpty())
		{
			return;
		}
		// several varps can change in one tick
		JsonObject kills = new JsonObject();
		for (String boss : changed)
		{
			int kc = BossVarps.read(client, boss);
			if (kc > 0)
			{
				kills.addProperty(boss, kc);
			}
		}
		changed.clear();
		push(kills);
	}

	public void sendSnapshot()
	{
		JsonObject kills = new JsonObject();
		for (Map.Entry<Integer, String> e : BossVarps.all())
		{
			int kc = BossVarps.read(client, e.getValue());
			if (kc > 0)
			{
				kills.addProperty(e.getValue(), kc);
			}
		}
		push(kills);
	}

	public void forget()
	{
		changed.clear();
	}

	private void push(JsonObject kills)
	{
		if (kills.size() == 0)
		{
			return;
		}
		JsonObject data = new JsonObject();
		data.add("kills", kills);
		dashboard.send(Kind.KILL_COUNTS, data);
	}
}
