package com.pinklsz.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pinklsz.net.Dashboard;
import com.pinklsz.net.Kind;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import net.runelite.api.events.WidgetClosed;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;

/** Combat achievements: read when you open any combat achievements screen. */
@Singleton
public class AchievementCollector
{
	// 32 achievements per varp, as bits
	private static final int[] TASK_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3, VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7, VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19,
		5673 // the 21st one has no named constant yet
	};

	// one enum per tier, easy to grandmaster
	private static final int[] TIER_ENUMS = {3981, 3982, 3983, 3984, 3985, 3986};
	private static final int TASK_ID_PARAM = 1306;
	private static final long RESEND_MS = 60 * 1000L;

	private static final Set<Integer> CA_SCREENS = new HashSet<>(Arrays.asList(
		InterfaceID.CA_TASKS,
		InterfaceID.CA_BOSS,
		InterfaceID.CA_BOSSES,
		InterfaceID.CA_OVERVIEW,
		InterfaceID.CA_REWARDS
	));

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Dashboard dashboard;

	private final Set<Integer> open = new HashSet<>();
	private long lastSentAt;
	private int lastSentCount = -1;

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded e)
	{
		if (!CA_SCREENS.contains(e.getGroupId()))
		{
			return;
		}
		// switching screens closes and opens them in turn, so only the first one reads
		boolean first = open.isEmpty();
		open.add(e.getGroupId());
		if (first)
		{
			readAndSend();
		}
	}

	@Subscribe
	public void onWidgetClosed(WidgetClosed e)
	{
		clientThread.invokeLater(() -> { open.remove(e.getGroupId()); });
	}

	public void sendSnapshot()
	{
		readAndSend();
	}

	private void readAndSend()
	{
		TreeSet<Integer> done = new TreeSet<>();
		JsonArray all = new JsonArray(); // every task the game has, as [task id, tier (0 = Easy … 5 = Grandmaster)], so the site counts the real total
		for (int ti = 0; ti < TIER_ENUMS.length; ti++)
		{
			int enumId = TIER_ENUMS[ti];
			EnumComposition tier = client.getEnum(enumId);
			if (tier == null)
			{
				continue;
			}
			for (int structId : tier.getIntVals())
			{
				StructComposition s = client.getStructComposition(structId);
				int id = s.getIntValue(TASK_ID_PARAM);
				JsonArray one = new JsonArray();
				one.add(id);
				one.add(ti);
				all.add(one);
				int slot = id / 32;
				if (slot >= TASK_VARPS.length)
				{
					continue;
				}
				if ((client.getVarpValue(TASK_VARPS[slot]) & (1 << (id % 32))) != 0)
				{
					done.add(id);
				}
			}
		}
		long now = System.currentTimeMillis();
		if (done.size() == lastSentCount && now - lastSentAt < RESEND_MS)
		{
			return;
		}
		lastSentCount = done.size();
		lastSentAt = now;
		JsonArray ids = new JsonArray();
		for (int id : done)
		{
			ids.add(id);
		}
		JsonObject d = new JsonObject();
		d.add("ids", ids);
		d.add("all", all);
		dashboard.send(Kind.COMBAT_ACHIEVEMENTS, d);
	}

	public void forget()
	{
		open.clear();
		lastSentCount = -1;
	}
}
