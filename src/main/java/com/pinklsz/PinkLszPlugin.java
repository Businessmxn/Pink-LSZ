package com.pinklsz;

import com.google.inject.Provides;
import com.pinklsz.collect.AchievementCollector;
import com.pinklsz.collect.DeathCollector;
import com.pinklsz.collect.DropCollector;
import com.pinklsz.collect.KillCollector;
import com.pinklsz.collect.LogCollector;
import com.pinklsz.collect.PbCollector;
import com.pinklsz.collect.Shots;
import com.pinklsz.net.Dashboard;
import com.pinklsz.net.Kind;
import com.pinklsz.net.Session;
import com.google.gson.JsonObject;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.Player;
import net.runelite.api.events.PlayerChanged;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

/** Based on the Valiance Clan plugin by Encryptiron (BSD 2-Clause, see LICENSE). */
@PluginDescriptor(
	name = "Pink LSZ",
	description = "For Pink LSZ clan members: sends your drops, kill counts, collection log, combat achievements, personal bests and deaths to the clan dashboard.",
	tags = {"clan", "pink", "lsz", "collection log", "combat achievements", "kill count", "personal best", "deaths"}
)
public class PinkLszPlugin extends Plugin
{
	public static final String VERSION = "1.0.0";

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private ClientThread clientThread;

	@Inject
	private Dashboard dashboard;

	@Inject
	private Session session;

	@Inject
	private DropCollector drops;

	@Inject
	private KillCollector kills;

	@Inject
	private LogCollector log;

	@Inject
	private AchievementCollector achievements;

	@Inject
	private PbCollector pbs;

	@Inject
	private Shots shots;

	@Inject
	private DeathCollector deaths;

	private boolean loading;

	@Provides
	PinkLszConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(PinkLszConfig.class);
	}

	@Override
	protected void startUp()
	{
		eventBus.register(drops);
		eventBus.register(kills);
		eventBus.register(log);
		eventBus.register(achievements);
		eventBus.register(pbs);
		eventBus.register(shots);
		eventBus.register(deaths);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			tryLoadPlayer();
		}
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(drops);
		eventBus.unregister(kills);
		eventBus.unregister(log);
		eventBus.unregister(achievements);
		eventBus.unregister(pbs);
		eventBus.unregister(shots);
		eventBus.unregister(deaths);
		session.loggedOut();
		loading = false;
		dashboard.reset();
		kills.forget();
		log.forget();
		achievements.forget();
		pbs.forget();
		deaths.forget();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		GameState s = e.getGameState();
		if (s == GameState.LOGGED_IN)
		{
			tryLoadPlayer();
		}
		else if (s == GameState.LOGIN_SCREEN || s == GameState.HOPPING)
		{
			session.loggedOut();
			kills.forget();
			log.forget();
			achievements.forget();
			pbs.forget();
			deaths.forget();
			loading = false;
		}
	}

	@Subscribe
	public void onPlayerChanged(PlayerChanged e)
	{
		Player me = client.getLocalPlayer();
		if (me != null && e.getPlayer() == me)
		{
			tryLoadPlayer();
		}
	}

	/** Waits for the player's name, then sends the hello and everything the account already has. Once per login. */
	private void tryLoadPlayer()
	{
		if (session.settled() || loading)
		{
			return;
		}
		loading = true;
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() != GameState.LOGGED_IN)
			{
				loading = false;
				return true;
			}
			Player me = client.getLocalPlayer();
			if (me == null || me.getName() == null)
			{
				return false;
			}
			loading = false;
			session.loaded();
			log.primeLastItem();
			dashboard.send(Kind.HELLO, new JsonObject());
			kills.sendSnapshot();
			drops.sendHistory();
			achievements.sendSnapshot();
			pbs.sendStored();
			return true;
		});
	}
}
