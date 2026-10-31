package com.pinklsz.collect;

import com.google.gson.JsonObject;
import com.pinklsz.net.Dashboard;
import com.pinklsz.net.Kind;
import com.pinklsz.net.Session;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.eventbus.Subscribe;

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

	private final Set<String> changed = new HashSet<>();

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
