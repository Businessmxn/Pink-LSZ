package com.pinklsz;

import com.google.inject.Provides;
import com.pinklsz.collect.AchievementCollector;
import com.pinklsz.collect.DeathCollector;
import com.pinklsz.collect.DropCollector;
import com.pinklsz.collect.GearCollector;
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
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.util.ImageUtil;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** Based on the Valiance Clan plugin by Encryptiron (BSD 2-Clause, see LICENSE). */
@PluginDescriptor(
	name = "Pink LSZ",
	description = "For Pink LSZ clan members: a TeamFinder panel for group content, and sends your drops, kill counts, collection log, combat achievements, personal bests and deaths to the clan dashboard.",
	tags = {"clan", "pink", "lsz", "collection log", "combat achievements", "kill count", "personal best", "deaths"}
)
public class PinkLszPlugin extends Plugin
{
	public static final String VERSION = "1.1.0";

	@Inject
	private Client client;

	@Inject
	private net.runelite.client.config.ConfigManager configManager;

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

	@Inject
	private GearCollector gear;

	@Inject
	private PinkLszConfig config;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private LobbyPanel lobbyPanel;

	@Inject
	private ScheduledExecutorService executor;

	private NavigationButton navButton;
	private ScheduledFuture<?> lobbyPoll;
	private ScheduledFuture<?> announcePoll;

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
		// the dashboard moved from dashboard.pinkosrs.com to pinkosrs.com: swap an address saved from an older version
		if (config.dashboardUrl().toLowerCase().contains("dashboard.pinkosrs.com"))
		{
			configManager.setConfiguration(PinkLszConfig.GROUP, "dashboardUrl", "https://pinkosrs.com");
		}
		eventBus.register(kills);
		eventBus.register(log);
		eventBus.register(achievements);
		eventBus.register(pbs);
		eventBus.register(shots);
		eventBus.register(deaths);
		eventBus.register(gear);
		// the TeamFinder side panel, with the clan's icon on the toolbar
		lobbyPanel.build();
		navButton = NavigationButton.builder()
			.tooltip("Pink LSZ TeamFinder")
			.icon(ImageUtil.loadImageResource(PinkLszPlugin.class, "icon.png"))
			.priority(7)
			.panel(lobbyPanel)
			.build();
		if (!config.hideSidePanel())
		{
			clientToolbar.addNavigation(navButton);
		}
		// check the lobbies while the panel is open, or while you are in one (the host's check-in keeps it alive)
		lobbyPoll = executor.scheduleWithFixedDelay(() ->
		{
			try
			{
				if (client.getGameState() == GameState.LOGGED_IN && lobbyPanel.wantsPolling())
				{
					lobbyPanel.refresh();
				}
			}
			catch (RuntimeException e)
			{
				// an exception would stop a repeating task for good
			}
		}, 5, 5, TimeUnit.SECONDS);
		// messages the clan leaders send from the dashboard: asked for once a minute while logged in
		announcePoll = executor.scheduleWithFixedDelay(this::checkAnnouncements, 15, 20, TimeUnit.SECONDS);
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			tryLoadPlayer();
		}
	}

	private void checkAnnouncements()
	{
		if (!config.showAnnouncements() || client.getGameState() != GameState.LOGGED_IN || !session.settled())
		{
			return;
		}
		dashboard.call("pl_announce", new JsonObject(), reply ->
		{
			if (!reply.has("messages") || !reply.get("messages").isJsonArray())
			{
				return;
			}
			for (com.google.gson.JsonElement e : reply.getAsJsonArray("messages"))
			{
				JsonObject m = e.getAsJsonObject();
				int id = m.get("id").getAsInt();
				// shown in the chat first; only then does the site count it as delivered (so one that failed to show comes again)
				dashboard.announce(m.get("text").getAsString(), () ->
				{
					com.google.gson.JsonArray ack = new com.google.gson.JsonArray();
					ack.add(id);
					JsonObject body = new JsonObject();
					body.add("ack", ack);
					dashboard.call("pl_announce", body, r -> { });
				});
			}
		});
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
		eventBus.unregister(gear);
		if (lobbyPoll != null)
		{
			lobbyPoll.cancel(false);
			lobbyPoll = null;
		}
		if (announcePoll != null)
		{
			announcePoll.cancel(false);
			announcePoll = null;
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}
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
	public void onConfigChanged(net.runelite.client.events.ConfigChanged e)
	{
		if (navButton == null || !PinkLszConfig.GROUP.equals(e.getGroup()) || !"hideSidePanel".equals(e.getKey()))
		{
			return;
		}
		clientToolbar.removeNavigation(navButton);
		if (!config.hideSidePanel())
		{
			clientToolbar.addNavigation(navButton);
		}
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
			gear.forget();
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
			gear.markDirty();
			// load the team list a few seconds later, once the hello above has linked this account
			executor.schedule(() -> lobbyPanel.refresh(), 6, TimeUnit.SECONDS);
			return true;
		});
	}
}
