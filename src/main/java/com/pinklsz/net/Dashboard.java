package com.pinklsz.net;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.pinklsz.PinkLszConfig;
import com.pinklsz.PinkLszPlugin;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Player;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.RuneScapeProfileType;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Everything sent to the dashboard goes through here, one request at a time. A failed request is retried
 * after a pause, and anything still failing after a few tries is dropped.
 */
@Singleton
public class Dashboard
{
	private static final Logger log = LoggerFactory.getLogger(Dashboard.class);
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final int MAX_TRIES = 4;
	private static final int RETRY_DELAY_SECONDS = 15;
	private static final int MAX_QUEUED = 300;
	private static final long MAX_QUEUED_CHARS = 24_000_000L; // screenshots are big
	private static final long INFO_GAP_MS = 20_000L;

	@Inject
	private OkHttpClient http;

	@Inject
	private PinkLszConfig config;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ScheduledExecutorService executor;

	private static final class Upload
	{
		final Kind kind;
		final String json;
		final int generation;
		int tries;

		Upload(Kind kind, String json, int generation)
		{
			this.kind = kind;
			this.json = json;
			this.generation = generation;
		}
	}

	private final Deque<Upload> queue = new ArrayDeque<>();
	private long queuedChars;
	private boolean busy;
	private int generation; // bumped on logout, so uploads from the last login are never sent

	private final Map<Kind, Long> lastInfo = new ConcurrentHashMap<>();
	private boolean warnedUrl;
	private boolean warnedRefused;
	private boolean connected;
	private static final long REFUSED_PAUSE_MS = 3 * 60 * 60 * 1000L; // a character the clan site refuses is left alone for a few hours
	private volatile String refusedName;
	private volatile long refusedAt;

	private volatile long dropMin = 1_000_000L;
	private volatile Set<Integer> watched = Collections.emptySet();

	/** Drops worth at least this much get a screenshot. */
	public long dropMin()
	{
		return dropMin;
	}

	/** Items the clan is looking for right now (e.g. a bingo board); these get a screenshot too. */
	public boolean isWatched(int itemId)
	{
		return watched.contains(itemId);
	}

	/** Nothing goes to the clan site until the player has turned this on (it has a third-party warning in the settings). */
	public boolean enabled()
	{
		return config.connect();
	}

	/** Can be called from any thread. */
	public void send(Kind kind, JsonObject data)
	{
		if (!enabled())
		{
			return;
		}
		if (client.isClientThread())
		{
			enqueue(kind, data);
		}
		else
		{
			clientThread.invoke(() -> enqueue(kind, data));
		}
	}

	private void enqueue(Kind kind, JsonObject data)
	{
		Player me = client.getLocalPlayer();
		if (me == null || me.getName() == null)
		{
			return;
		}
		if (refusedName != null && refusedName.equalsIgnoreCase(me.getName()) && System.currentTimeMillis() - refusedAt < REFUSED_PAUSE_MS)
		{
			return; // not a clan member: nothing is sent for this character
		}
		JsonObject body = new JsonObject();
		body.addProperty("kind", kind.wire);
		body.addProperty("rsn", me.getName());
		body.addProperty("account_hash", client.getAccountHash());
		body.addProperty("account_type", client.getVarbitValue(VarbitID.IRONMAN));
		body.addProperty("profile", RuneScapeProfileType.getCurrent(client).name());
		body.addProperty("version", PinkLszPlugin.VERSION);
		body.addProperty("sent_at", System.currentTimeMillis());
		body.add("data", data);

		Upload u;
		synchronized (queue)
		{
			u = new Upload(kind, body.toString(), generation);
			queue.add(u);
			queuedChars += u.json.length();
			while (queue.size() > 1 && (queue.size() > MAX_QUEUED || queuedChars > MAX_QUEUED_CHARS))
			{
				queuedChars -= queue.poll().json.length();
			}
		}
		next();
	}

	private void next()
	{
		Upload u;
		synchronized (queue)
		{
			if (busy || queue.isEmpty())
			{
				return;
			}
			busy = true;
			u = queue.poll();
			queuedChars -= u.json.length();
		}
		upload(u);
	}

	private void finished()
	{
		synchronized (queue)
		{
			busy = false;
		}
		next();
	}

	/** Put it back at the front and wait before trying again. */
	private void retryLater(Upload u)
	{
		synchronized (queue)
		{
			if (++u.tries < MAX_TRIES && u.generation == generation)
			{
				queue.addFirst(u);
				queuedChars += u.json.length();
			}
		}
		executor.schedule(this::finished, RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
	}

	private HttpUrl endpoint()
	{
		return endpoint("pl_ingest");
	}

	/** A page on the website (e.g. "/bingo/3"), built from the dashboard address in the settings; null if that address isn't valid. */
	public String siteUrl(String path)
	{
		HttpUrl u = endpoint("x");
		return u == null ? null : u.newBuilder().encodedPath(path).query(null).build().toString();
	}

	private HttpUrl endpoint(String action)
	{
		String base = config.dashboardUrl() == null ? "" : config.dashboardUrl().trim();
		if (!base.contains("://"))
		{
			base = "https://" + base;
		}
		HttpUrl url = HttpUrl.parse(base);
		if (url == null)
		{
			return null;
		}
		boolean local = "localhost".equals(url.host()) || "127.0.0.1".equals(url.host());
		if (!url.isHttps() && !local)
		{
			return null;
		}
		return url.newBuilder().encodedPath("/api/app.php").setQueryParameter("a", action).build();
	}

	private void upload(Upload u)
	{
		if (!enabled())
		{
			finished(); // switched off while waiting: the upload is dropped
			return;
		}
		HttpUrl url = endpoint();
		if (url == null)
		{
			if (!warnedUrl)
			{
				warnedUrl = true;
				note("The dashboard address in the Pink LSZ settings isn't valid (it must start with https://).");
			}
			finished();
			return;
		}
		Request request = new Request.Builder()
			.url(url)
			.header("User-Agent", "PinkLSZ-RuneLite/" + PinkLszPlugin.VERSION)
			.post(RequestBody.create(JSON, u.json))
			.build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.debug("{} failed: {}", u.kind, e.getMessage());
				retryLater(u);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				boolean retry = false;
				try (Response r = response)
				{
					String text = r.body() == null ? "" : r.body().string();
					retry = handle(u, r.code(), text);
				}
				catch (IOException e)
				{
					retry = true;
				}
				if (retry)
				{
					retryLater(u);
				}
				else
				{
					finished();
				}
			}
		});
	}

	/** Returns true if the upload should be tried again later. */
	private boolean handle(Upload u, int code, String text)
	{
		if (code == 401 || code == 403)
		{
			String who = null;
			try
			{
				who = parse(u.json).get("rsn").getAsString();
			}
			catch (RuntimeException e)
			{
				// no name in the upload
			}
			if (code == 403 && who != null)
			{
				refusedName = who;
				refusedAt = System.currentTimeMillis();
				final String mark = "\"rsn\":" + new com.google.gson.JsonPrimitive(who).toString();
				synchronized (queue)
				{
					queue.removeIf(q ->
					{
						boolean same = q.json.contains(mark);
						if (same)
						{
							queuedChars -= q.json.length();
						}
						return same;
					});
				}
			}
			if (!warnedRefused)
			{
				warnedRefused = true;
				note(parse(text).has("error") ? parse(text).get("error").getAsString() : "The dashboard didn't accept this character.");
			}
			return false;
		}
		if (code == 429 || code >= 500)
		{
			return true;
		}
		if (code >= 400)
		{
			log.debug("{} rejected ({}): {}", u.kind, code, text);
			return false;
		}
		warnedRefused = false;
		JsonObject reply = parse(text);
		learn(reply);
		report(u.kind, reply);
		return false;
	}

	/** The dashboard only answers with JSON, but a proxy error page shouldn't break anything. */
	private static JsonObject parse(String text)
	{
		try
		{
			return new JsonParser().parse(text).getAsJsonObject();
		}
		catch (RuntimeException e)
		{
			return new JsonObject();
		}
	}

	private void learn(JsonObject reply)
	{
		if (reply.has("drop_min"))
		{
			dropMin = reply.get("drop_min").getAsLong();
		}
		if (reply.has("watch") && reply.get("watch").isJsonArray())
		{
			JsonArray ids = reply.get("watch").getAsJsonArray();
			Set<Integer> w = new HashSet<>();
			for (int i = 0; i < ids.size(); i++)
			{
				w.add(ids.get(i).getAsInt());
			}
			watched = w;
		}
	}

	private void report(Kind kind, JsonObject reply)
	{
		switch (kind)
		{
			case HELLO:
				if (!connected)
				{
					connected = true;
					boolean linked = !reply.has("linked") || reply.get("linked").getAsBoolean();
					if (!linked)
					{
						note("Add this character's name to your dashboard profile so your data shows up there.");
					}
				}
				if (reply.has("need_clog") && reply.get("need_clog").getAsBoolean())
				{
					note("Open your collection log once and it will sync to the dashboard.");
				}
				break;
			case PERSONAL_BEST:
				// only a personal best that went to staff is worth a message
				if (reply.has("pb") && "queued".equals(reply.get("pb").getAsString()) && reply.has("message"))
				{
					note(reply.get("message").getAsString());
				}
				break;
			case GEAR:
			case DROP:
				break; // happens on its own in the background (a drop is sent every time you get one): no chat line
			default:
				break; // a normal send says nothing: chat only speaks up when something is wrong
		}
	}

	/**
	 * One request that wants an answer (the TeamFinder panel), sent straight away and never retried.
	 * Can be called from any thread; the callback comes from a background thread. On a failure the reply has an "error".
	 */
	private volatile String callName; // the character from the last time the player was logged in

	/** The character name from the last time the player was logged in (null before the first login). */
	public String playerName()
	{
		return callName;
	}

	private volatile long callHash;

	public void call(String action, JsonObject data, java.util.function.Consumer<JsonObject> done)
	{
		if (!enabled())
		{
			done.accept(error("Turn on \"Connect to the clan site\" in the Pink LSZ plugin settings to use this."));
			return;
		}
		clientThread.invoke(() ->
		{
			Player me = client.getLocalPlayer();
			GameState gs = client.getGameState();
			if (gs == GameState.LOGGED_IN && me != null && me.getName() != null)
			{
				callName = me.getName();
				callHash = client.getAccountHash();
			}
			else if (!((gs == GameState.HOPPING || gs == GameState.LOADING || gs == GameState.CONNECTION_LOST) && callName != null))
			{
				// only a real log out stops the check-ins: a world hop or loading screen keeps the team alive using the name from before
				done.accept(error("Log in to the game first."));
				return;
			}
			HttpUrl url = endpoint(action);
			if (url == null)
			{
				done.accept(error("The dashboard address in the Pink LSZ settings isn't valid."));
				return;
			}
			JsonObject body = data == null ? new JsonObject() : data;
			body.addProperty("rsn", callName);
			body.addProperty("account_hash", callHash);
			if (!body.has("world")) // a world the caller chose (hosting a team elsewhere) is kept; otherwise it is the world you are on
			{
				body.addProperty("world", client.getWorld());
			}
			body.addProperty("version", PinkLszPlugin.VERSION);
			Request request = new Request.Builder()
				.url(url)
				.header("User-Agent", "PinkLSZ-RuneLite/" + PinkLszPlugin.VERSION)
				.post(RequestBody.create(JSON, body.toString()))
				.build();
			http.newCall(request).enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					done.accept(error("Couldn't reach the dashboard."));
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					JsonObject reply;
					try (Response r = response)
					{
						reply = parse(r.body() == null ? "" : r.body().string());
						if (r.code() >= 400 && !reply.has("error"))
						{
							reply = error("The dashboard answered with an error (" + r.code() + ").");
						}
					}
					catch (IOException e)
					{
						reply = error("Couldn't read the dashboard's answer.");
					}
					done.accept(reply);
				}
			});
		});
	}

	private final Map<String, java.awt.image.BufferedImage> roleIcons = new ConcurrentHashMap<>();
	private final Set<String> roleIconAsked = ConcurrentHashMap.newKeySet();

	/** The clan rank icon (the same picture the dashboard shows), or null while it is still being fetched; whenLoaded runs once it arrives. */
	public java.awt.image.BufferedImage roleIcon(String role, Runnable whenLoaded)
	{
		if (!enabled())
		{
			return null;
		}
		if (role == null || !role.matches("[a-z0-9_\\-]{1,40}"))
		{
			return null;
		}
		java.awt.image.BufferedImage have = roleIcons.get(role);
		if (have != null || !roleIconAsked.add(role))
		{
			return have;
		}
		HttpUrl base = endpoint("x");
		if (base == null)
		{
			return null;
		}
		HttpUrl url = base.newBuilder().encodedPath("/icons/roles/" + role + ".png").query(null).build();
		Request request = new Request.Builder().url(url).header("User-Agent", "PinkLSZ-RuneLite/" + PinkLszPlugin.VERSION).build();
		http.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				roleIconAsked.remove(role);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.isSuccessful() && r.body() != null)
					{
						java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(r.body().byteStream());
						if (img != null)
						{
							roleIcons.put(role, img);
							whenLoaded.run();
						}
					}
				}
				catch (IOException e)
				{
					roleIconAsked.remove(role);
				}
			}
		});
		return null;
	}

	private static JsonObject error(String text)
	{
		JsonObject o = new JsonObject();
		o.addProperty("error", text);
		return o;
	}

	/** Forget everything queued and per-login state. */
	public void reset()
	{
		synchronized (queue)
		{
			generation++;
			queue.clear();
			queuedChars = 0;
		}
		connected = false;
		lastInfo.clear();
	}

	/** Routine messages, only shown when "Show messages in chat" is on. */
	public void info(String text)
	{
		if (config.chatNotes())
		{
			note(text);
		}
	}

	/** A message from the clan leaders, shown whatever the "show messages in chat" setting is. {@code shown} runs once it is really in the chat. */
	/** "Name has started a ... team" / "Name is available to +1 ...": only the player's name is pink. */
	private static String teamLine(String text)
	{
		int i = text.indexOf(" has started a ");
		if (i <= 0)
		{
			i = text.indexOf(" is available to +1 ");
		}
		return i > 0 ? "<col=ef3d9a>" + text.substring(0, i) + "</col>" + text.substring(i) : text;
	}

	public void announce(String text, boolean team, Runnable shown)
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", (team ? teamLine(net.runelite.client.util.Text.removeTags(text)) : "<col=ef3d9a>Pink LSZ announcement:</col> " + net.runelite.client.util.Text.removeTags(text)), "PinkLSZ");
				if (shown != null)
				{
					shown.run();
				}
			}
		});
	}

	public void note(String text)
	{
		clientThread.invokeLater(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", "<col=ef3d9a>Pink LSZ:</col> " + text, "PinkLSZ");
			}
		});
	}
}
