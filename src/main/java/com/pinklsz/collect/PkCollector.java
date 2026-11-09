package com.pinklsz.collect;

import com.google.gson.JsonObject;
import com.pinklsz.net.Dashboard;
import com.pinklsz.net.Session;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/**
 * Player kills during a clan event: when a player dies right after you were fighting them, or you die while a player was fighting you,
 * the killer and victim names are sent. Only runs while a tracked event is on, and the dashboard only keeps it if it falls inside one.
 */
@Singleton
public class PkCollector
{
	private static final int RECENT_TICKS = 8; // about 5 seconds

	@Inject
	private Client client;

	@Inject
	private Dashboard dashboard;

	@Inject
	private Session session;

	private final Map<String, Integer> attackers = new HashMap<>(); // players who were fighting you, and when
	private String targetName;
	private int targetTick;
	private int tick;
	private volatile boolean active;
	private volatile boolean wild;

	public void setActive(boolean on)
	{
		active = on;
	}

	public void setWild(boolean w)
	{
		wild = w;
	}

	public void forget()
	{
		attackers.clear();
		targetName = null;
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		tick++;
		Player me = client.getLocalPlayer();
		if (me == null || !active)
		{
			return;
		}
		Actor t = me.getInteracting();
		if (t instanceof Player && t.getName() != null)
		{
			targetName = Text.removeTags(t.getName());
			targetTick = tick;
		}
		for (Player p : client.getPlayers())
		{
			if (p != null && p != me && p.getName() != null && p.getInteracting() == me)
			{
				attackers.put(Text.removeTags(p.getName()), tick);
			}
		}
	}

	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		Player me = client.getLocalPlayer();
		if (!active || !session.settled() || me == null || !(e.getActor() instanceof Player) || e.getActor().getName() == null)
		{
			return;
		}
		String dead = Text.removeTags(e.getActor().getName());
		if (e.getActor() == me)
		{
			// who was fighting you most recently
			String killer = null;
			int best = -1;
			for (Map.Entry<String, Integer> a : attackers.entrySet())
			{
				if (tick - a.getValue() <= RECENT_TICKS && a.getValue() > best)
				{
					best = a.getValue();
					killer = a.getKey();
				}
			}
			if (killer != null)
			{
				report(killer, dead);
			}
			attackers.clear();
			return;
		}
		if (targetName != null && targetName.equals(dead) && tick - targetTick <= RECENT_TICKS)
		{
			report(Text.removeTags(me.getName()), dead);
			targetName = null;
		}
	}

	private void report(String killer, String victim)
	{
		JsonObject d = new JsonObject();
		d.addProperty("killer", killer);
		d.addProperty("victim", victim);
		d.addProperty("wild", wild);
		dashboard.call("pl_pk", d, r ->
		{
		});
	}
}
