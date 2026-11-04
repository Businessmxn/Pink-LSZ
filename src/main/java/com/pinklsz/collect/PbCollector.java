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
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Completion times. Every finished boss or raid time the game prints is sent: new personal bests go to the PB records (with a screenshot),
 * other times only count for things like bingo speed tiles. The boss and the time come in two chat lines, in either order, so each waits briefly for the other.
 */
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
	// any other finished time: "Fight duration: 1:31.20. Personal best: 1:23.40"  /  "Challenge time: 24:11. Total completion time: 30:02. Personal best: 22:40"
	private static final Pattern CHALLENGE_TIME = Pattern.compile("challenge time[: ]+(\\d+(?::\\d+)*(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
	private static final Pattern TOTAL_TIME = Pattern.compile("(?:completion time|subdued in)[: ]+(\\d+(?::\\d+)*(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
	private static final Pattern ANY_TIME = Pattern.compile("(?:fight duration|duration)[: ]+(\\d+(?::\\d+)*(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
	// the name in the time's own line, for the times that have no kill-count line after them: "Challenge complete: Bork. Duration: 2:12" / "The Fractured Archive: Total completion time: 21:40" / "Theatre of Blood total completion time: 27:39"
	private static final Pattern LINE_ROOM = Pattern.compile("^Challenge complete: (.+?)\\.", Pattern.CASE_INSENSITIVE);
	private static final Pattern LINE_TOTAL = Pattern.compile("^(.+?):? (?:total )?(?:completion time|duration)", Pattern.CASE_INSENSITIVE);
	// group content where the game doesn't print the team size next to the time: the players in the instance are the team
	private static final Pattern GROUP = Pattern.compile("theatre of blood|tombs of amascut|fractured archive|chambers of xeric|colosseum", Pattern.CASE_INSENSITIVE);
	private static final Pattern TEAM = Pattern.compile("Team size: (?:(\\d+) players?|Solo)", Pattern.CASE_INSENSITIVE);

	@Inject
	private Client client;

	@Inject
	private Dashboard dashboard;

	@Inject
	private Session session;

	@Inject
	private Shots shots;

	private volatile int tick;

	private String boss;
	private int bossTick;
	private int lastTeam; // the last "Team size: N players" line the game printed, which can come in its own message
	private int lastTeamTick = -1000;
	private String lineBoss; // a name taken from the time's own line

	private String time;
	private int team;
	private String raw;
	private int timeTick;
	private boolean newPb;

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		if (e.getType() != ChatMessageType.GAMEMESSAGE && e.getType() != ChatMessageType.SPAM)
		{
			return;
		}
		String msg = Text.removeTags(e.getMessage());
		Matcher ts = TEAM.matcher(msg);
		if (ts.find())
		{
			lastTeam = ts.group(1) == null ? 1 : Integer.parseInt(ts.group(1));
			lastTeamTick = tick;
		}
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
		boolean pb = p.find();
		if (!pb)
		{
			// a raid's challenge or total time wins over a room's own duration in the same line
			p = CHALLENGE_TIME.matcher(msg);
			if (!p.find())
			{
				p = TOTAL_TIME.matcher(msg);
				if (!p.find())
				{
					p = ANY_TIME.matcher(msg);
					if (!p.find())
					{
						return;
					}
				}
			}
		}
		if (!pb && newPb && time != null && tick - timeTick <= WINDOW_TICKS)
		{
			return; // a new personal best is already waiting to be sent: an ordinary time can't replace it
		}
		{
			newPb = pb;
			time = p.group(1);
			raw = msg;
			timeTick = tick;
			team = 0;
			lineBoss = null;
			Matcher ln = LINE_ROOM.matcher(msg);
			if (ln.find())
			{
				lineBoss = ln.group(1).trim();
			}
			else
			{
				ln = LINE_TOTAL.matcher(msg);
				if (ln.find() && ln.group(1).length() < 50 && !ln.group(1).toLowerCase().startsWith("challenge"))
				{
					lineBoss = ln.group(1).trim();
				}
			}
			Matcher t = TEAM.matcher(msg);
			if (t.find())
			{
				team = t.group(1) == null ? 1 : Integer.parseInt(t.group(1));
			}
			else if (GROUP.matcher(msg).find() || (boss != null && GROUP.matcher(boss).find()))
			{
				// not in the message: count the players in the instance (everyone there is on the team)
				int n = client.getPlayers().size();
				team = Math.max(1, Math.min(n, 100));
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
			if (newPb || lineBoss != null || (boss != null && tick - bossTick <= WINDOW_TICKS * 2))
			{
				send(boss != null && tick - bossTick <= WINDOW_TICKS * 2 ? boss : (lineBoss != null ? lineBoss : ""));
			}
			else
			{
				forget(); // an ordinary time with no name at all: nothing to file it under
			}
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
			// the game's own "Team size" line (in this message or one next to it) beats counting players
			if (lastTeam > 0 && Math.abs(lastTeamTick - timeTick) <= 30)
			{
				team = lastTeam;
			}
			d.addProperty("team_size", team);
			d.addProperty("raw", raw == null ? "" : raw);
			d.addProperty("is_pb", newPb);
			if (newPb)
			{
				waiting = d;
				shotTries = 0;
				capture();
			}
			else
			{
				dashboard.send(Kind.PERSONAL_BEST, d); // an ordinary time: no screenshot needed
			}
		}
		time = null;
		boss = null;
		lineBoss = null;
		raw = null;
		team = 0;
		newPb = false;
	}

	public void forget()
	{
		time = null;
		boss = null;
		lineBoss = null;
		raw = null;
		team = 0;
		newPb = false;
	}
}
