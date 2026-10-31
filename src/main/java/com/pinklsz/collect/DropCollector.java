package com.pinklsz.collect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pinklsz.net.Dashboard;
import com.pinklsz.net.Kind;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.game.ItemManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.loottracker.LootReceived;

/** Drops from the RuneLite loot tracker. */
@Singleton
public class DropCollector
{
	@Inject
	private Dashboard dashboard;

	@Inject
	private ConfigManager configManager;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Shots shots;

	private static final String LOOT_GROUP = "loottracker";
	private static final String LOOT_PREFIX = "drops_";
	private static final int PER_REQUEST = 20;

	/** The loot tracker's saved totals per boss, sent once per login. */
	public void sendHistory()
	{
		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return;
		}
		JsonArray batch = new JsonArray();
		for (String key : configManager.getRSProfileConfigurationKeys(LOOT_GROUP, profile, LOOT_PREFIX))
		{
			String full = key.startsWith(LOOT_PREFIX) ? key : LOOT_PREFIX + key;
			String json = configManager.getConfiguration(LOOT_GROUP, profile, full);
			if (json == null || json.isEmpty())
			{
				continue;
			}
			try
			{
				JsonObject o = new JsonParser().parse(json).getAsJsonObject();
				JsonElement drops = o.get("drops");
				if (drops == null || !drops.isJsonArray() || drops.getAsJsonArray().size() == 0)
				{
					continue;
				}
				String[] parts = full.substring(LOOT_PREFIX.length()).split("_", 2);
				JsonObject r = new JsonObject();
				r.addProperty("type", parts.length > 1 ? parts[0] : "");
				r.addProperty("name", parts.length > 1 ? parts[1] : parts[0]);
				r.addProperty("kills", o.has("kills") ? o.get("kills").getAsInt() : 0);
				r.add("drops", drops);
				batch.add(r);
			}
			catch (RuntimeException ignored)
			{
				continue;
			}
			if (batch.size() >= PER_REQUEST)
			{
				flush(batch);
				batch = new JsonArray();
			}
		}
		flush(batch);
	}

	private void flush(JsonArray batch)
	{
		if (batch.size() == 0)
		{
			return;
		}
		JsonObject d = new JsonObject();
		d.add("records", batch);
		dashboard.send(Kind.DROP_TOTALS, d);
	}

	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (event.getItems() == null || event.getItems().isEmpty())
		{
			return;
		}
		JsonArray items = new JsonArray();
		for (ItemStack s : event.getItems())
		{
			JsonObject o = new JsonObject();
			o.addProperty("id", s.getId());
			o.addProperty("qty", s.getQuantity());
			items.add(o);
		}
		long value = 0;
		for (ItemStack s : event.getItems())
		{
			value += (long) itemManager.getItemPrice(s.getId()) * s.getQuantity();
		}
		JsonObject data = new JsonObject();
		data.addProperty("value", value);
		data.addProperty("npc", event.getName());
		data.addProperty("qty", event.getAmount());
		data.addProperty("type", event.getType() == null ? "" : event.getType().name());
		data.add("items", items);
		boolean wanted = false;
		for (ItemStack s : event.getItems())
		{
			wanted |= dashboard.isWatched(s.getId());
		}
		if (value >= dashboard.dropMin() || wanted)
		{
			// valuable, or on the clan's watch list
			shots.take(img ->
			{
				if (img != null)
				{
					data.addProperty("image", img);
				}
				dashboard.send(Kind.DROP, data);
			});
		}
		else
		{
			dashboard.send(Kind.DROP, data);
		}
	}
}
