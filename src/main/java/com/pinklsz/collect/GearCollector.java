package com.pinklsz.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.pinklsz.PinkLszConfig;
import com.pinklsz.net.Dashboard;
import com.pinklsz.net.Kind;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Skill;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.client.eventbus.Subscribe;

/**
 * Combat stats, worn gear, inventory, rune pouch and spellbook, so the clan site's DPS calculator
 * can load them ("Load from the Pink LSZ plugin"). Sent quietly, only when something changed,
 * after things have settled for a few seconds, and never more than once a minute.
 */
@Singleton
public class GearCollector
{
	private static final int INVENTORY = 93;
	private static final int WORN = 94;
	private static final int SETTLE_TICKS = 8; // about 5 seconds with nothing changing
	private static final long MIN_GAP_MS = 60 * 1000L;

	// rune pouch: which rune (an index into the rune enum) and how many, for each of the 4 slots
	private static final int[] POUCH_TYPE = {29, 1622, 1623, 14285};
	private static final int[] POUCH_QTY = {1624, 1625, 1626, 14286};
	private static final int RUNEPOUCH_RUNE_ENUM = 982;
	private static final int SPELLBOOK_VARBIT = 4070;

	// equipment slot index -> the calculator's slot name
	private static final String[] SLOT_NAMES = {
		"head", "cape", "neck", "weapon", "body", "shield", null, "legs", null, "hands", "feet", null, "ring", "ammo"
	};

	@Inject
	private Client client;

	@Inject
	private Dashboard dashboard;

	@Inject
	private PinkLszConfig config;

	private boolean dirty;
	private int quietTicks;
	private long lastSentAt;
	private String lastSent = "";
	private final java.util.EnumMap<Skill, Integer> levels = new java.util.EnumMap<>(Skill.class);

	public void markDirty()
	{
		dirty = true;
		quietTicks = 0;
	}

	public void forget()
	{
		dirty = false;
		quietTicks = 0;
		lastSent = "";
		lastSentAt = 0;
		levels.clear();
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (e.getContainerId() == INVENTORY || e.getContainerId() == WORN)
		{
			markDirty();
		}
	}

	@Subscribe
	public void onStatChanged(StatChanged e)
	{
		// only a real level change matters (not boosts going up and down)
		Integer was = levels.put(e.getSkill(), e.getLevel());
		if (was != null && was != e.getLevel())
		{
			dirty = true;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged e)
	{
		int id = e.getVarbitId();
		if (id == SPELLBOOK_VARBIT)
		{
			markDirty();
			return;
		}
		for (int i = 0; i < 4; i++)
		{
			if (id == POUCH_TYPE[i] || id == POUCH_QTY[i])
			{
				markDirty();
				return;
			}
		}
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (!dirty || client.getGameState() != GameState.LOGGED_IN || !config.shareGear())
		{
			return;
		}
		quietTicks++;
		if (quietTicks < SETTLE_TICKS || System.currentTimeMillis() - lastSentAt < MIN_GAP_MS)
		{
			return;
		}
		JsonObject data = read();
		if (data == null)
		{
			return;
		}
		dirty = false;
		String sig = data.toString();
		if (sig.equals(lastSent))
		{
			return; // nothing new since the last one
		}
		lastSent = sig;
		lastSentAt = System.currentTimeMillis();
		dashboard.send(Kind.GEAR, data);
	}

	private JsonObject read()
	{
		JsonObject d = new JsonObject();

		JsonObject skills = new JsonObject();
		skills.addProperty("atk", client.getRealSkillLevel(Skill.ATTACK));
		skills.addProperty("str", client.getRealSkillLevel(Skill.STRENGTH));
		skills.addProperty("def", client.getRealSkillLevel(Skill.DEFENCE));
		skills.addProperty("ranged", client.getRealSkillLevel(Skill.RANGED));
		skills.addProperty("magic", client.getRealSkillLevel(Skill.MAGIC));
		skills.addProperty("prayer", client.getRealSkillLevel(Skill.PRAYER));
		skills.addProperty("hp", client.getRealSkillLevel(Skill.HITPOINTS));
		skills.addProperty("mining", client.getRealSkillLevel(Skill.MINING));
		skills.addProperty("herblore", client.getRealSkillLevel(Skill.HERBLORE));
		skills.addProperty("farming", client.getRealSkillLevel(Skill.FARMING));
		if (client.getRealSkillLevel(Skill.HITPOINTS) <= 0)
		{
			return null; // stats haven't loaded yet
		}
		d.add("skills", skills);

		JsonObject names = new JsonObject();
		JsonObject worn = new JsonObject();
		ItemContainer eq = client.getItemContainer(WORN);
		if (eq != null)
		{
			Item[] items = eq.getItems();
			for (int i = 0; i < items.length && i < SLOT_NAMES.length; i++)
			{
				if (SLOT_NAMES[i] == null || items[i].getId() <= 0)
				{
					continue;
				}
				JsonArray it = new JsonArray();
				it.add(items[i].getId());
				it.add(items[i].getQuantity());
				worn.add(SLOT_NAMES[i], it);
				name(names, items[i].getId());
			}
		}
		d.add("equipment", worn);

		JsonArray inv = new JsonArray();
		ItemContainer bag = client.getItemContainer(INVENTORY);
		for (int i = 0; i < 28; i++)
		{
			Item it = bag != null && i < bag.getItems().length ? bag.getItems()[i] : null;
			if (it == null || it.getId() <= 0)
			{
				inv.add((JsonObject) null);
				continue;
			}
			JsonArray p = new JsonArray();
			p.add(it.getId());
			p.add(it.getQuantity());
			inv.add(p);
			name(names, it.getId());
		}
		d.add("inv", inv);

		JsonArray pouch = new JsonArray();
		EnumComposition runes = client.getEnum(RUNEPOUCH_RUNE_ENUM);
		for (int i = 0; i < 4; i++)
		{
			int type = client.getVarbitValue(POUCH_TYPE[i]);
			int qty = client.getVarbitValue(POUCH_QTY[i]);
			int id = type > 0 && runes != null ? runes.getIntValue(type) : -1;
			if (id <= 0 || qty <= 0)
			{
				pouch.add((JsonObject) null);
				continue;
			}
			JsonArray p = new JsonArray();
			p.add(id);
			p.add(qty);
			pouch.add(p);
			name(names, id);
		}
		d.add("pouch", pouch);
		d.addProperty("book", client.getVarbitValue(SPELLBOOK_VARBIT));
		d.add("names", names);
		return d;
	}

	private void name(JsonObject names, int id)
	{
		String key = String.valueOf(id);
		if (!names.has(key))
		{
			names.addProperty(key, client.getItemDefinition(id).getName());
		}
	}
}
