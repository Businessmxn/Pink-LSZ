package com.pinklsz.collect;

import com.google.gson.JsonObject;
import com.pinklsz.PinkLszConfig;
import com.pinklsz.net.Dashboard;
import com.pinklsz.net.Kind;
import com.pinklsz.net.Session;
import java.util.HashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.events.ActorDeath;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.InteractingChanged;
import java.lang.ref.WeakReference;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.util.Text;

/** Deaths at bosses and raids, with a screenshot. */
@Singleton
public class DeathCollector
{
	private static final int COLOSSEUM_REGION = 7216; // the Fortis Colosseum arena
	private static final int ENGAGED_TICKS = 50; // about 30 seconds

	// raid bosses count towards their raid
	private static final String[][] RAID_NPCS = {
		{"chambers_of_xeric", "tekton", "vanguard", "vespula", "ice demon", "muttadile", "vasa nistirio", "skeletal mystic", "lizardman shaman", "deathly ranger", "deathly mage", "great olm", "abyssal portal"},
		{"theatre_of_blood", "the maiden of sugadinti", "pestilent bloat", "nylocas vasilias", "sotetseg", "xarpus", "verzik vitur"},
		{"tombs_of_amascut", "ba-ba", "kephri", "akkha", "zebak", "tumeken's warden", "elidinis' warden", "elidinis' warden"},
		// the Colosseum's wave enemies count as Sol Heredit (only names that live in the Colosseum alone, so a death elsewhere isn't mistaken for one)
		{"sol_heredit", "shockwave colossus", "javelin colossus", "serpent shaman", "jaguar warrior", "minotaur", "manticore"},
		// the Ferox Enclave PvP tutor: dying to him in the training challenges is recorded as a death to Pete Kayer
		{"pete_kayer", "pete kayer"},
		// bosses that fight under other names than the one on the kill count
		{"grotesque_guardians", "grotesque guardians", "dusk", "dawn"},
		{"the_gauntlet", "crystalline hunllef"},
		{"the_corrupted_gauntlet", "corrupted hunllef"},
		{"the_royal_titans", "branda the fire queen", "eldric the ice king"},
		// names from guides, not checked in game
		{"the_fractured_archive", "archive guardian", "bork", "smorks", "ezekiel", "dreadnoth", "dreadnoth the jellied", "moia", "xarth", "dagonhai", "dagon'hai", "ocellus"},
	};

	@Inject
	private Client client;

	@Inject
	private Dashboard dashboard;

	@Inject
	private Session session;

	@Inject
	private PinkLszConfig config;

	@Inject
	private Shots shots;

	private final Map<String, String> bosses = new HashMap<>();
	private int tick;
	private String lastBoss;
	private int lastBossTick;
	private WeakReference<Actor> lastTarget = new WeakReference<>(null); // the last thing you attacked, same idea as Dink

	private static String norm(String s)
	{
		String t = Text.removeTags(s == null ? "" : s).toLowerCase().replace(' ', ' ').trim();
		if (t.startsWith("the "))
		{
			t = t.substring(4);
		}
		return t.replaceAll("[^a-z0-9]", "");
	}

	private void build()
	{
		if (!bosses.isEmpty())
		{
			return;
		}
		for (Map.Entry<Integer, String> e : BossVarps.all())
		{
			bosses.put(norm(e.getValue()), e.getValue());
		}
		for (String[] raid : RAID_NPCS)
		{
			for (int i = 1; i < raid.length; i++)
			{
				bosses.put(norm(raid[i]), raid[0]);
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		tick++;
		Player me = client.getLocalPlayer();
		if (me == null)
		{
			return;
		}
		Actor target = me.getInteracting();
		if (target instanceof NPC)
		{
			build();
			String key = bosses.get(norm(target.getName()));
			if (key != null)
			{
				lastBoss = key;
				lastBossTick = tick;
			}
		}
	}

	@Subscribe
	public void onInteractingChanged(InteractingChanged e)
	{
		if (e.getSource() == client.getLocalPlayer() && e.getTarget() instanceof NPC)
		{
			lastTarget = new WeakReference<>(e.getTarget());
		}
	}

	private String bossOf(Actor a)
	{
		if (a instanceof NPC && a.getName() != null)
		{
			build();
			return bosses.get(norm(a.getName()));
		}
		return null;
	}

	@Subscribe
	public void onActorDeath(ActorDeath e)
	{
		if (!config.trackDeaths() || !session.settled() || e.getActor() != client.getLocalPlayer())
		{
			return;
		}
		// last target, then anything attacking you (as Dink does), then the last boss you fought
		Player me = client.getLocalPlayer();
		String boss = bossOf(lastTarget.get());
		String seen = null;
		if (boss == null)
		{
			for (NPC n : client.getTopLevelWorldView().npcs())
			{
				// pets follow you around and "interact" with you too: they have no combat level and are never what killed you
				if (n.getInteracting() == me && n.getName() != null && n.getCombatLevel() > 0)
				{
					seen = n.getName();
					boss = bossOf(n);
					if (boss != null)
					{
						break;
					}
				}
			}
		}
		if (boss == null && lastBoss != null && tick - lastBossTick <= ENGAGED_TICKS)
		{
			boss = lastBoss;
		}
		// anything that kills you inside the Colosseum counts as Sol Heredit, whatever it is called
		if (boss == null && inColosseum(me))
		{
			boss = "sol_heredit";
		}
		lastBoss = null;
		lastTarget = new WeakReference<>(null);
		if (boss == null)
		{
			if (seen != null)
			{
				dashboard.note("Death not recorded: I don't know \"" + Text.removeTags(seen) + "\" as a boss. Tell the clan admins that name.");
			}
			return;
		}
		JsonObject d = new JsonObject();
		d.addProperty("boss", boss);
		shots.take(img ->
		{
			if (img != null)
			{
				d.addProperty("image", img);
			}
			dashboard.send(Kind.DEATH, d);
		});
	}

	private boolean inColosseum(Player me)
	{
		try
		{
			net.runelite.api.coords.WorldPoint wp = net.runelite.api.coords.WorldPoint.fromLocalInstance(client, me.getLocalLocation());
			return wp != null && wp.getRegionID() == COLOSSEUM_REGION;
		}
		catch (RuntimeException ex)
		{
			return false;
		}
	}

	public void forget()
	{
		lastBoss = null;
	}
}
