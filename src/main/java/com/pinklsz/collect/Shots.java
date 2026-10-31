package com.pinklsz.collect;

import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.ui.DrawManager;
import net.runelite.client.util.Text;

/**
 * Screenshots of the game as base64 JPEG. Private messages are hidden for the captured frame (as the Dink plugin does),
 * both the panel above the chatbox and any private message lines in it.
 */
@Singleton
public class Shots
{
	private static final int MAX_WIDTH = 2560;
	private static final long RECENT_MS = 45_000L;
	private static final int MAX_NAMES = 30;

	// "From Name: text" / "To Name: text", maybe after a timestamp
	private static final Pattern PRIVATE_LINE = Pattern.compile("^(?:\\[[0-9:]+\\]\\s*)?(?:From|To)\\s+[^:]{1,30}:.*");

	@Inject
	private DrawManager drawManager;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	// recent private message names; chat lines naming them are hidden too
	private final Deque<String> pmNames = new ArrayDeque<>();
	private volatile long lastPmAt;

	@Subscribe
	public void onChatMessage(ChatMessage e)
	{
		ChatMessageType t = e.getType();
		if (t != ChatMessageType.PRIVATECHAT && t != ChatMessageType.PRIVATECHATOUT && t != ChatMessageType.MODPRIVATECHAT)
		{
			return;
		}
		lastPmAt = System.currentTimeMillis();
		String n = clean(e.getName());
		if (!n.isEmpty())
		{
			synchronized (pmNames)
			{
				pmNames.remove(n);
				pmNames.addFirst(n);
				while (pmNames.size() > MAX_NAMES)
				{
					pmNames.removeLast();
				}
			}
		}
	}

	/** Call on the client thread. The callback gets null if no safe picture could be made. */
	public void take(Consumer<String> done)
	{
		final List<Widget> hidden = new ArrayList<>();
		try
		{
			// the split private chat panel
			hide(client.getWidget(InterfaceID.PmChat.CONTAINER), hidden);
			// private message lines in the chatbox
			Widget[] roots = client.getWidgetRoots();
			if (roots != null)
			{
				for (Widget w : roots)
				{
					scan(w, 0, hidden);
				}
			}
		}
		catch (RuntimeException e)
		{
			show(hidden);
			done.accept(null);
			return;
		}
		if (hidden.isEmpty() && System.currentTimeMillis() - lastPmAt < RECENT_MS)
		{
			done.accept(null);
			return;
		}
		drawManager.requestNextFrameListener(image ->
		{
			show(hidden);
			executor.execute(() -> done.accept(encode(image)));
		});
		// in case no frame is drawn (minimised), don't leave messages hidden
		if (!hidden.isEmpty())
		{
			executor.schedule(() -> show(hidden), 3, java.util.concurrent.TimeUnit.SECONDS);
		}
	}

	private static void hide(Widget w, List<Widget> hidden)
	{
		if (w != null && !w.isHidden())
		{
			w.setHidden(true);
			hidden.add(w);
		}
	}

	private void show(List<Widget> hidden)
	{
		if (hidden.isEmpty())
		{
			return;
		}
		clientThread.invoke(() ->
		{
			for (Widget w : hidden)
			{
				w.setHidden(false);
			}
		});
	}

	private void scan(Widget w, int depth, List<Widget> hidden)
	{
		if (w == null || depth > 8 || w.isHidden())
		{
			return;
		}
		String t = w.getText();
		if (t != null && !t.isEmpty() && isPrivate(clean(t), Text.removeTags(t).replace(' ', ' ').trim()))
		{
			hide(w, hidden);
			return;
		}
		for (Widget[] kids : new Widget[][] {w.getStaticChildren(), w.getDynamicChildren(), w.getNestedChildren()})
		{
			if (kids == null)
			{
				continue;
			}
			for (Widget k : kids)
			{
				scan(k, depth + 1, hidden);
			}
		}
	}

	private boolean isPrivate(String lower, String plain)
	{
		if (PRIVATE_LINE.matcher(plain).matches())
		{
			return true;
		}
		synchronized (pmNames)
		{
			for (String n : pmNames)
			{
				if (lower.contains(n + ":") || lower.contains(n + " :"))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static String clean(String s)
	{
		return s == null ? "" : Text.removeTags(s).replace(' ', ' ').trim().toLowerCase();
	}

	private static String encode(Image image)
	{
		try
		{
			int w = image.getWidth(null);
			int h = image.getHeight(null);
			if (w <= 0 || h <= 0)
			{
				return null;
			}
			double scale = w > MAX_WIDTH ? (double) MAX_WIDTH / w : 1.0;
			int tw = (int) Math.round(w * scale);
			int th = (int) Math.round(h * scale);
			BufferedImage rgb = new BufferedImage(tw, th, BufferedImage.TYPE_INT_RGB);
			Graphics2D g = rgb.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
			g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING, java.awt.RenderingHints.VALUE_RENDER_QUALITY);
			g.drawImage(image, 0, 0, tw, th, null);
			g.dispose();
			ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
			ImageWriteParam p = writer.getDefaultWriteParam();
			p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			p.setCompressionQuality(0.93f);
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out))
			{
				writer.setOutput(ios);
				writer.write(null, new IIOImage(rgb, null, null), p);
			}
			writer.dispose();
			return Base64.getEncoder().encodeToString(out.toByteArray());
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
