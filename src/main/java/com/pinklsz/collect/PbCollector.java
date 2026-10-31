package com.pinklsz.collect;

import com.google.gson.JsonObject;
import com.pinklsz.net.Dashboard;
import com.pinklsz.net.Kind;
import com.pinklsz.net.Session;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/** New personal bests. The boss and the time come in two chat lines, in either order, so each waits briefly for the other. */
@Singleton
public class PbCollector
{
	private static final int WINDOW_TICKS = 6;
	private static final int RETRY_TICKS = 8;
	private static final int MAX_SHOT_TRIES = 6;

	// "Your Vorkath kill count is: 12."  /  "Your completed Theatre of Blood count is: 3."  /  "Your Prifddinas Agility Course lap count is: 4."
	private static final Pattern COUNT = Pattern.compile("^Your (?:completed )?(.+?)(?: (?:kill|harvest|lap|completion|success))? count is: ?[\\d,]+\\.?$", Pattern.CASE_INSENSITIVE);
	// "Your completion count for TzHaar-Ket-Rak's Fourth Challenge is: 3."
	private static final Pattern COUNT_FOR = Pattern.compile("^Your (?:completion|kill) count for (.+?) is: ?[\\d,]+\\.?$", Pattern.CASE_INSENSITIVE);
	// "Fight duration: 1:23.40 (new personal best)"  /  "Team size: 3 players Duration: 24:11.40 (new personal best)"  /  "... total completion time: 20:11.40 (new personal best)"
	private static final Pattern NEW_PB = Pattern.compile("(?:duration|completion time|subdued in|time)[: ]+(\\d+(?::\\d+)*(?:\\.\\d+)?).{0,40}\\(new personal best\\)", Pattern.CASE_INSENSITIVE);
	private static final Pattern TEAM = Pattern.compile("Team size: (?:(\\d+) players?|Solo)", Pattern.CASE_INSENSITIVE);

	@Inject
	private Dashboard dashboard;

	@Inject
	private Session session;

	@Inject
	private Shots shots;

	private volatile int tick;

	private String boss;
	private int bossTick;

	private String time;
	private int team;
	private String raw;
	private int timeTick;

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String msg = Text.removeTags(e.getMessage());
		Matcher c = COUNT.matcher(msg);
		if (!c.matches())
		{
			c = COUNT_FOR.matcher(msg);
		}
		if (c.matches())
		{
			boss = c.group(1).trim();
			bossTick = tick;
			trySend();
			return;
		}
		Matcher p = NEW_PB.matcher(msg);
		if (p.find())
		{
			time = p.group(1);
			raw = msg;
			timeTick = tick;
			team = 0;
			Matcher t = TEAM.matcher(msg);
			if (t.find())
			{
				team = t.group(1) == null ? 1 : Integer.parseInt(t.group(1));
			}
			trySend();
		}
	}

	// waiting for a screenshot that couldn't be taken yet
	private volatile JsonObject waiting;
	private volatile int waitingUntil;
	private int shotTries;

	@Subscribe
	public void onGameTick(GameTick e)
	{
		tick = tick + 1;
		if (waiting != null && tick >= waitingUntil)
		{
			capture();
		}
		// the boss line never came, send it anyway
		if (time != null && tick - timeTick > WINDOW_TICKS)
		{
			send(boss != null && tick - bossTick <= WINDOW_TICKS * 2 ? boss : "");
		}
	}

	private void capture()
	{
		final JsonObject d = waiting;
		waiting = null;
		shots.take(img ->
		{
			if (img != null)
			{
				d.addProperty("image", img);
				dashboard.send(Kind.PERSONAL_BEST, d);
			}
			else
			{
				retry(d);
			}
		});
	}

	/** Try the screenshot again shortly; send without one only as a last resort. */
	private void retry(JsonObject d)
	{
		synchronized (this)
		{
			if (++shotTries > MAX_SHOT_TRIES)
			{
				dashboard.send(Kind.PERSONAL_BEST, d);
				return;
			}
			waitingUntil = tick + RETRY_TICKS;
			waiting = d;
		}
	}

	private void trySend()
	{
		if (time != null && boss != null && Math.abs(timeTick - bossTick) <= WINDOW_TICKS)
		{
			send(boss);
		}
	}

	private void send(String name)
	{
		if (session.settled())
		{
			JsonObject d = new JsonObject();
			d.addProperty("boss", name);
			d.addProperty("time", time);
			d.addProperty("team_size", team);
			d.addProperty("raw", raw == null ? "" : raw);
			waiting = d;
			shotTries = 0;
			capture();
		}
		time = null;
		boss = null;
		raw = null;
		team = 0;
	}

	public void forget()
	{
		time = null;
		boss = null;
		raw = null;
		team = 0;
	}
}
