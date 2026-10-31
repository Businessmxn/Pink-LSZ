package com.pinklsz.collect;

import com.google.gson.JsonObject;
import com.pinklsz.net.Dashboard;
import com.pinklsz.net.Kind;
import com.pinklsz.net.Session;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/** The whole collection log (read when you open it) and each new item as you get it. */
@Singleton
public class LogCollector
{
	// fires once per owned item while the log's search runs
	private static final int ITEM_SCRIPT = 4100;
	private static final int CLOSE_SEARCH_SCRIPT = 2240;
	private static final int SEARCH_BUTTON = 40697932;
	private static final int LAST_ITEM_VARP = 4623;
	private static final int GIVE_UP_TICKS = 30;
	private static final long RESEND_MS = 10 * 60 * 1000L;
	private static final Pattern NEW_ITEM = Pattern.compile("New item added to your collection log: (.+)");

	// listed twice under different ids, counted once
	private static final Map<Integer, Integer> TWIN_ITEMS = Map.of(
		12013, 29472,
		12014, 29474,
		12015, 29476,
		12016, 29478
	);

	@Inject
	private Client client;

	@Inject
	private Dashboard dashboard;

	@Inject
	private Session session;

	private enum Phase
	{
		CLOSED, OPENED, READING
	}

	private Phase phase = Phase.CLOSED;
	private int readingTicks;
	private final Map<Integer, Integer> seen = new HashMap<>();
	private int reportedTotal = -1;

	private long lastSentAt;
	private int lastSentCount = -1;
	private long lastSentSum = -1;

	private int lastItemId;
	private boolean primed;
	private int pendingItemId;
	private int pendingWait;
	private String pendingName = "";

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (e.getGroupId() == InterfaceID.COLLECTION)
		{
			phase = Phase.OPENED;
			readingTicks = 0;
			reportedTotal = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		if (e.getGroupId() == InterfaceID.COLLECTION)
		{
			phase = Phase.CLOSED;
		}
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired e)
	{
		if (phase != Phase.CLOSED && e.getScriptId() == ITEM_SCRIPT)
		{
			Object[] a = e.getScriptEvent().getArguments();
			seen.put((int) a[1], (int) a[2]);
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		if (e.getVarpId() == VarPlayerID.COLLECTION_COUNT)
		{
			reportedTotal = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
		}
		if (e.getVarpId() != LAST_ITEM_VARP)
		{
			return;
		}
		int id = client.getVarpValue(LAST_ITEM_VARP);
		if (id <= 0)
		{
			return;
		}
		// the login replay of the last item isn't a new one
		boolean fresh = session.settled() && primed && id != lastItemId;
		lastItemId = id;
		if (fresh)
		{
			pendingItemId = id;
			pendingWait = 2; // the chat line with the item's name arrives a moment either side of this
		}
	}

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		Matcher m = NEW_ITEM.matcher(Text.removeTags(e.getMessage()));
		if (m.matches())
		{
			pendingName = m.group(1).trim();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		if (pendingItemId > 0 && --pendingWait <= 0)
		{
			sendNewItem();
		}
		if (phase == Phase.OPENED)
		{
			startReading();
		}
		else if (phase == Phase.READING)
		{
			continueReading();
		}
	}

	private void sendNewItem()
	{
		JsonObject d = new JsonObject();
		d.addProperty("item_id", pendingItemId);
		d.addProperty("name", pendingName);
		dashboard.send(Kind.COLLECTION_LOG_ITEM, d);
		pendingItemId = 0;
		pendingName = "";
	}

	private void startReading()
	{
		if (!session.settled())
		{
			phase = Phase.CLOSED;
			return;
		}
		seen.clear();
		// start listening before the search runs
		phase = Phase.READING;
		readingTicks = 0;
		// the game lists every owned item between opening and closing the search
		client.menuAction(-1, SEARCH_BUTTON, MenuAction.CC_OP, 1, -1, "Search", null);
		client.runScript(CLOSE_SEARCH_SCRIPT);
	}

	private void continueReading()
	{
		readingTicks++;
		if (reportedTotal < 0 || readingTicks > GIVE_UP_TICKS)
		{
			phase = Phase.CLOSED;
			dashboard.note("Couldn't read your collection log (" + countOwned() + " of " + Math.max(reportedTotal, 0) + " items). Close it and open it again.");
			return;
		}
		if (countOwned() != reportedTotal)
		{
			return;
		}
		phase = Phase.CLOSED;
		dashboard.info("Read your collection log (" + seen.size() + " items).");
		long sum = 0;
		for (Map.Entry<Integer, Integer> e : seen.entrySet())
		{
			sum += e.getKey() * 31L + e.getValue();
		}
		long now = System.currentTimeMillis();
		if (seen.size() == lastSentCount && sum == lastSentSum && now - lastSentAt < RESEND_MS)
		{
			return;
		}
		lastSentCount = seen.size();
		lastSentSum = sum;
		lastSentAt = now;
		JsonObject items = new JsonObject();
		for (Map.Entry<Integer, Integer> e : seen.entrySet())
		{
			items.addProperty(Integer.toString(e.getKey()), e.getValue());
		}
		JsonObject d = new JsonObject();
		d.add("items", items);
		dashboard.send(Kind.COLLECTION_LOG, d);
	}

	private int countOwned()
	{
		int n = seen.size();
		for (Map.Entry<Integer, Integer> t : TWIN_ITEMS.entrySet())
		{
			if (seen.containsKey(t.getKey()) && seen.containsKey(t.getValue()))
			{
				n--;
			}
		}
		return n;
	}

	public void forget()
	{
		phase = Phase.CLOSED;
		seen.clear();
		reportedTotal = -1;
		lastItemId = 0;
		primed = false;
		pendingItemId = 0;
		pendingName = "";
	}

	/** The varp is replayed at login, so remember it. */
	public void primeLastItem()
	{
		lastItemId = client.getVarpValue(LAST_ITEM_VARP);
		primed = true;
		reportedTotal = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
	}
}
