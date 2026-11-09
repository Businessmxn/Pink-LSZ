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
import net.runelite.api.Player;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.HitsplatApplied;
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
	private static final int MAX_SHOT_TRIES = 12;

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
	// "Wave 'The Maiden of Sugadinti' (Hard Mode) complete! Duration: 2:08.40": a Theatre of Blood room (the duration can come in the same message or the next)
	private static final Pattern WAVE = Pattern.compile("Wave '(.+?)'(?:\\s*\\((.*?)\\))?\\s*complete", Pattern.CASE_INSENSITIVE);
	private static final String[][] TOB_ROOMS = {{"maiden", "Maiden"}, {"bloat", "Bloat"}, {"nylocas", "Nylocas"}, {"sotetseg", "Sotetseg"}, {"xarpus", "Xarpus"}, {"verzik", "Verzik"}};
	private static final String[] TFA_ROOMS = {"archive guardian", "ezekiel", "bork", "moia", "dreadnoth", "dagon", "xarth", "ocellus"};
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
	private volatile int engagedTick = -100000; // the last time you hit something or got hit: spectators never do
	private static final int ENGAGED_TICKS = 500; // about 5 minutes

	private String boss;
	private int bossTick;
	private int lastTeam; // the last "Team size: N players" line the game printed, which can come in its own message
	private int lastTeamTick = -1000;
	private String waveBoss; // the Theatre of Blood room named in the last "Wave ... complete" line
	private int waveTick = -1000;
	private String lineBoss; // a name taken from the time's own line

	private String time;
	private int team;
	private boolean teamCounted; // the size was counted from nearby players rather than given by the game
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
		Matcher wv = WAVE.matcher(msg);
		if (wv.find())
		{
			String w = wv.group(1).toLowerCase();
			for (String[] r : TOB_ROOMS)
			{
				if (w.contains(r[0]))
				{
					boolean hard = wv.group(2) != null && wv.group(2).toLowerCase().contains("hard");
					waveBoss = "Theatre of Blood: " + (hard ? "Hard Mode: " : "") + r[1];
					waveTick = tick;
				}
			}
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
			teamCounted = false;
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
			if (waveBoss != null && tick - waveTick <= WINDOW_TICKS && !TOTAL_TIME.matcher(msg).find() && !CHALLENGE_TIME.matcher(msg).find())
			{
				lineBoss = waveBoss; // a Theatre of Blood room (the raid's own total is never a room)
				waveBoss = null;
			}
			else if (lineBoss != null)
			{
				String lb = lineBoss.toLowerCase();
				for (String r : TFA_ROOMS)
				{
					if (lb.contains(r))
					{
						lineBoss = "Fractured Archive: " + lineBoss; // a Fractured Archive boss
						break;
					}
				}
			}
			Matcher t = TEAM.matcher(msg);
			if (t.find())
			{
				team = t.group(1) == null ? 1 : Integer.parseInt(t.group(1));
				teamCounted = false;
			}
			else if (msg.toLowerCase().contains("colosseum") || (boss != null && boss.toLowerCase().contains("colosseum")) || (lineBoss != null && lineBoss.toLowerCase().contains("colosseum")))
			{
				team = 1; // the Colosseum is always solo
				teamCounted = false;
			}
			else if (GROUP.matcher(msg).find() || (boss != null && GROUP.matcher(boss).find()) || (lineBoss != null && GROUP.matcher(lineBoss).find()))
			{
				// not in the message: count the players in the instance (everyone there is on the team)
				// (this is a last resort, and the site is told the size was counted so staff can check it)
				int n = 0;
				for (Player ignored : client.getTopLevelWorldView().players())
				{
					n++; // the set has no size(), so count them
				}
				team = n >= 1 && n <= 100 ? n : 0; // a silly count is never sent
				teamCounted = team > 0;
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
				send(isRoom(lineBoss) ? lineBoss : (boss != null && tick - bossTick <= WINDOW_TICKS * 2 ? boss : (lineBoss != null ? lineBoss : "")));
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
		if (time != null && isRoom(lineBoss))
		{
			send(lineBoss); // a room's own time is never filed under the whole raid
		}
		else if (time != null && boss != null && Math.abs(timeTick - bossTick) <= WINDOW_TICKS)
		{
			send(boss);
		}
	}

	private static boolean isRoom(String n)
	{
		return n != null && (n.startsWith("Theatre of Blood: ") || n.startsWith("Fractured Archive: "));
	}

	@Subscribe
	public void onHitsplatApplied(HitsplatApplied e)
	{
		if (e.getHitsplat().isMine() || e.getActor() == client.getLocalPlayer())
		{
			engagedTick = tick;
		}
	}

	private void send(String name)
	{
		// raid and group times only count if you were fighting in them: someone spectating a run sees the same messages but did not take part
		boolean group = name != null && (GROUP.matcher(name).find() || isRoom(name));
		boolean tookPart = !group || tick - engagedTick <= ENGAGED_TICKS;
		if (session.settled() && tookPart)
		{
			JsonObject d = new JsonObject();
			d.addProperty("boss", name);
			d.addProperty("time", time);
			// the game's own "Team size" line (in this message or one next to it) beats counting players
			if (lastTeam > 0 && Math.abs(lastTeamTick - timeTick) <= 30)
			{
				team = lastTeam;
				teamCounted = false;
			}
			d.addProperty("team_size", team);
			if (teamCounted)
			{
				d.addProperty("team_counted", true);
			}
			d.addProperty("raw", raw == null ? "" : raw);
			d.addProperty("is_pb", newPb);
			{
				// every time, personal best or not, only needs a screenshot if it would take 1st or 2nd on the clan record (or beat a bingo speed tile): ask the site first.
				// Only if the site can't be reached is the screenshot taken anyway, so a record that does reach staff has one.
				final JsonObject check = new JsonObject();
				check.addProperty("boss", name);
				check.addProperty("time", d.get("time").getAsString());
				check.addProperty("team_size", team);
				dashboard.call("pl_pb_check", check, reply ->
				{
					if (reply != null && reply.has("top") && !reply.get("top").getAsBoolean())
					{
						dashboard.send(Kind.PERSONAL_BEST, d); // can't reach the top two: nothing to review
					}
					else
					{
						synchronized (PbCollector.this)
						{
							shotTries = 0;
							waitingUntil = 0; // the next game tick takes the screenshot
							waiting = d;
						}
					}
				});
			}
		}
		time = null;
		boss = null;
		lineBoss = null;
		raw = null;
		team = 0;
		teamCounted = false;
		newPb = false;
	}

	public void forget()
	{
		time = null;
		boss = null;
		lineBoss = null;
		raw = null;
		team = 0;
		teamCounted = false;
		newPb = false;
	}
}
