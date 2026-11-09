package com.pinklsz;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pinklsz.net.Dashboard;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.HashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;

/**
 * The pop-out window for a bingo event: the board, and how the player's own team is getting on. It shows only this team's progress.
 * It refreshes itself every minute while it is open, and has a button to refresh at once.
 */
public class BingoBoardWindow extends JFrame
{
	private static final Color PINK = new Color(0xEF3D9A);
	private static final Color BG = ColorScheme.DARK_GRAY_COLOR;
	private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color EDGE = ColorScheme.MEDIUM_GRAY_COLOR;
	private static final Color TEXT = new Color(0xEEEEEE);
	private static final Color MUTED = new Color(0xB4B4B4);
	private static final Color GREEN = new Color(0x4CD964);

	private static Font font(int style, float size)
	{
		return new Font(Font.SANS_SERIF, style, 12).deriveFont(style, size);
	}

	private final Dashboard dashboard;
	private final ItemManager itemManager;
	private final int eventId;
	private final JPanel header = new JPanel();
	private final JPanel board = new JPanel();
	private final JPanel footer = new JPanel();
	private final JLabel status = new JLabel(" ");
	private final Timer timer;

	public BingoBoardWindow(Dashboard dashboard, ItemManager itemManager, int eventId, String title)
	{
		super("Bingo – " + title);
		this.dashboard = dashboard;
		this.itemManager = itemManager;
		this.eventId = eventId;
		setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
		getContentPane().setBackground(BG);
		setLayout(new BorderLayout(0, 8));
		((JPanel) getContentPane()).setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setOpaque(false);
		add(header, BorderLayout.NORTH);

		board.setOpaque(false);
		JLabel loading = new JLabel("Loading the board…", SwingConstants.CENTER);
		loading.setForeground(MUTED);
		board.setLayout(new BorderLayout());
		board.add(loading, BorderLayout.CENTER);
		board.setPreferredSize(new Dimension(520, 520));
		add(board, BorderLayout.CENTER);

		footer.setLayout(new BoxLayout(footer, BoxLayout.Y_AXIS));
		footer.setOpaque(false);
		add(footer, BorderLayout.SOUTH);

		pack();
		setLocationRelativeTo(null);
		timer = new Timer(60_000, e -> load());
		timer.start();
		addWindowListener(new java.awt.event.WindowAdapter()
		{
			@Override
			public void windowClosed(java.awt.event.WindowEvent e)
			{
				timer.stop();
			}
		});
		load();
	}

	/** Asks the site for the board again. */
	public void load()
	{
		JsonObject d = new JsonObject();
		d.addProperty("event", eventId);
		dashboard.call("pl_bingo", d, reply -> SwingUtilities.invokeLater(() -> apply(reply)));
	}

	private static JLabel label(String text, Color c, Font f)
	{
		JLabel l = new JLabel(text);
		l.setForeground(c);
		l.setFont(f);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		return l;
	}

	private static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String str(JsonObject o, String k)
	{
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : "";
	}

	private static int num(JsonObject o, String k)
	{
		return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsInt() : 0;
	}

	private static String ago(long at)
	{
		long s = Math.max(0, System.currentTimeMillis() / 1000 - at);
		if (s < 90)
		{
			return "just now";
		}
		if (s < 3600)
		{
			return (s / 60) + "m ago";
		}
		if (s < 86400)
		{
			return (s / 3600) + "h ago";
		}
		return (s / 86400) + "d ago";
	}

	private void apply(JsonObject r)
	{
		if (r.has("error"))
		{
			status.setText(str(r, "error"));
			if (board.getComponentCount() == 1 && board.getLayout() instanceof BorderLayout)
			{
				JLabel l = new JLabel("<html><body style='width:300px;text-align:center'>" + esc(str(r, "error")) + "</body></html>", SwingConstants.CENTER);
				l.setForeground(MUTED);
				board.removeAll();
				board.add(l, BorderLayout.CENTER);
				board.revalidate();
				board.repaint();
			}
			return;
		}
		JsonObject ev = r.getAsJsonObject("event");
		JsonObject team = r.getAsJsonObject("team");
		JsonArray tiles = r.getAsJsonArray("tiles");
		int size = Math.max(1, num(ev, "size"));
		Color tc = PINK;
		try
		{
			tc = Color.decode(str(team, "color"));
		}
		catch (RuntimeException ex)
		{
			// keep the pink
		}

		header.removeAll();
		header.add(label(str(ev, "name"), TEXT, font(Font.BOLD, 17f)));
		String ended = "ended".equals(str(ev, "status")) ? "  ·  event has ended" : "";
		JLabel tl = new JLabel("<html><font color='#" + String.format("%06x", tc.getRGB() & 0xFFFFFF) + "'><b>" + esc(str(team, "name")) + "</b></font>"
			+ " &nbsp;·&nbsp; " + ordinal(num(team, "rank")) + " of " + num(team, "of") + ended + "</html>");
		tl.setForeground(TEXT);
		tl.setFont(font(Font.PLAIN, 13f));
		tl.setAlignmentX(Component.LEFT_ALIGNMENT);
		header.add(tl);
		header.add(label(num(team, "points") + " of " + num(ev, "total") + " points  ·  " + num(team, "tiles") + " of " + tiles.size() + " tiles", MUTED, font(Font.PLAIN, 12f)));
		header.add(bar(num(ev, "total") > 0 ? (double) num(team, "points") / num(ev, "total") : 0, tc));

		board.removeAll();
		board.setLayout(new GridLayout(size, size, 5, 5));
		int box = size <= 5 ? 100 : size == 6 ? 94 : 88;
		board.setPreferredSize(new Dimension(size * (box + 5) - 5, size * (box + 5) - 5));
		Map<Integer, String> names = new HashMap<>();
		for (JsonElement el : tiles)
		{
			JsonObject t = el.getAsJsonObject();
			names.put(num(t, "pos"), str(t, "name"));
			board.add(tile(t, tc, box));
		}

		footer.removeAll();
		String members = "";
		if (team.has("members") && team.get("members").isJsonArray())
		{
			StringBuilder sb = new StringBuilder();
			for (JsonElement m : team.getAsJsonArray("members"))
			{
				sb.append(sb.length() > 0 ? ", " : "").append(m.getAsString());
			}
			members = sb.toString();
		}
		if (!members.isEmpty())
		{
			footer.add(label("<html><body style='width:480px'>Team: " + esc(members) + "</body></html>", MUTED, font(Font.PLAIN, 12f)));
		}
		JsonArray recent = r.has("recent") && r.get("recent").isJsonArray() ? r.getAsJsonArray("recent") : new JsonArray();
		if (recent.size() > 0)
		{
			footer.add(Box_gap(6));
			footer.add(label("Latest for your team", TEXT, font(Font.BOLD, 12f)));
			int shown = 0;
			for (JsonElement el : recent)
			{
				if (shown++ >= 5)
				{
					break;
				}
				JsonObject f = el.getAsJsonObject();
				String nm = names.getOrDefault(num(f, "tile"), "a tile");
				footer.add(label("<html><body style='width:480px'>" + esc(str(f, "rsn")) + " – " + esc(nm) + " <font color='#8a8a8a'>" + ago(f.get("at").getAsLong()) + "</font></body></html>", MUTED, font(Font.PLAIN, 12f)));
			}
		}
		footer.add(Box_gap(6));
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JButton refresh = new JButton("Refresh");
		refresh.setFocusable(false);
		refresh.addActionListener(e -> load());
		row.add(refresh);
		String site = dashboard.siteUrl("/bingo/" + eventId);
		if (site != null)
		{
			JButton web = new JButton("View on website");
			web.setFocusable(false);
			web.addActionListener(e -> net.runelite.client.util.LinkBrowser.browse(site));
			row.add(web);
		}
		status.setText("Updated " + java.time.LocalTime.now().withNano(0) + " · refreshes every minute");
		status.setForeground(MUTED);
		status.setFont(font(Font.PLAIN, 11f));
		row.add(status);
		footer.add(row);

		board.revalidate();
		board.repaint();
		header.revalidate();
		footer.revalidate();
		pack();
	}

	private static Component Box_gap(int h)
	{
		return javax.swing.Box.createRigidArea(new Dimension(0, h));
	}

	private static String ordinal(int n)
	{
		if (n <= 0)
		{
			return "-";
		}
		int m = n % 100;
		String s = m >= 11 && m <= 13 ? "th" : n % 10 == 1 ? "st" : n % 10 == 2 ? "nd" : n % 10 == 3 ? "rd" : "th";
		return n + s;
	}

	private static JPanel bar(double frac, Color c)
	{
		JPanel p = new JPanel()
		{
			@Override
			protected void paintComponent(java.awt.Graphics g)
			{
				super.paintComponent(g);
				g.setColor(new Color(0x3A3A3A));
				g.fillRect(0, 0, getWidth(), getHeight());
				g.setColor(c);
				g.fillRect(0, 0, (int) (getWidth() * Math.max(0, Math.min(1, frac))), getHeight());
			}
		};
		p.setPreferredSize(new Dimension(10, 6));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 6));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		p.setBorder(BorderFactory.createEmptyBorder());
		return p;
	}

	/** Breaks a tile's name into lines that fit the tile (at most three, with an ellipsis if it is longer). */
	private static String wrap(String text, java.awt.FontMetrics fm, int width)
	{
		java.util.List<String> lines = new java.util.ArrayList<>();
		StringBuilder cur = new StringBuilder();
		for (String w : text.split("\\s+"))
		{
			String tryLine = cur.length() == 0 ? w : cur + " " + w;
			if (fm.stringWidth(tryLine) <= width || cur.length() == 0)
			{
				cur = new StringBuilder(tryLine);
			}
			else
			{
				lines.add(cur.toString());
				cur = new StringBuilder(w);
			}
		}
		if (cur.length() > 0)
		{
			lines.add(cur.toString());
		}
		if (lines.size() > 3)
		{
			lines = new java.util.ArrayList<>(lines.subList(0, 3));
			lines.set(2, lines.get(2) + "\u2026");
		}
		StringBuilder out = new StringBuilder("<html><center>");
		for (int i = 0; i < lines.size(); i++)
		{
			out.append(i > 0 ? "<br>" : "").append(esc(lines.get(i)));
		}
		return out.append("</center></html>").toString();
	}

	/** The details of a tile that asks for several items: what the team already has, who got it, and what is left. */
	private void showTile(JsonObject t)
	{
		javax.swing.JDialog d = new javax.swing.JDialog(this, str(t, "name"), false);
		JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		body.setBackground(BG);
		body.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
		int need = Math.max(1, num(t, "need"));
		int have = Math.min(num(t, "have"), need);
		boolean done = t.has("done") && t.get("done").getAsBoolean();
		JsonArray list = t.has("list") && t.get("list").isJsonArray() ? t.getAsJsonArray("list") : new JsonArray();
		JsonArray who = t.has("who") && t.get("who").isJsonArray() ? t.getAsJsonArray("who") : new JsonArray();
		body.add(label(str(t, "name"), TEXT, font(Font.BOLD, 15f)));
		String sum = done ? "Complete" : list.size() > 0 ? (need >= list.size() ? "Collect all " + list.size() : "Collect " + need + " of these " + list.size()) + " \u00B7 " + have + " so far"
			: have + " of " + need + " collected";
		body.add(label(sum, done ? GREEN : MUTED, font(Font.PLAIN, 12f)));
		body.add(Box_gap(8));
		if (list.size() > 0)
		{
			JsonArray got = new JsonArray();
			JsonArray left = new JsonArray();
			for (JsonElement el : list)
			{
				(el.getAsJsonObject().get("got").getAsBoolean() ? got : left).add(el);
			}
			if (got.size() > 0)
			{
				body.add(label("Got", GREEN, font(Font.BOLD, 12f)));
				for (JsonElement el : got)
				{
					JsonObject it = el.getAsJsonObject();
					String by = str(it, "by");
					body.add(itemRow(it, "<html><b>" + esc(str(it, "n")) + "</b><br><font color='#9a9a9a'>" + (by.isEmpty() ? "" : "by " + esc(by) + " \u00B7 ")
						+ (it.has("manual") && it.get("manual").getAsBoolean() ? "marked by staff" : ago(it.has("at") ? it.get("at").getAsLong() : 0)) + "</font></html>", true));
				}
				body.add(Box_gap(6));
			}
			if (left.size() > 0)
			{
				body.add(label(done ? "Not needed" : "Still to get", PINK, font(Font.BOLD, 12f)));
				for (JsonElement el : left)
				{
					body.add(itemRow(el.getAsJsonObject(), "<html>" + esc(str(el.getAsJsonObject(), "n")) + "</html>", false));
				}
			}
		}
		if (who.size() > 0)
		{
			body.add(label("Added so far", GREEN, font(Font.BOLD, 12f)));
			for (JsonElement el : who)
			{
				JsonObject w = el.getAsJsonObject();
				body.add(label(str(w, "rsn") + "  \u00D7" + num(w, "n"), TEXT, font(Font.PLAIN, 12f)));
			}
		}
		d.setContentPane(body);
		d.pack();
		d.setMinimumSize(new Dimension(260, 120));
		d.setLocationRelativeTo(this);
		d.setVisible(true);
	}

	private JPanel itemRow(JsonObject it, String html, boolean got)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
		row.setOpaque(false);
		row.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel ic = new JLabel();
		ic.setPreferredSize(new Dimension(36, 32));
		if (num(it, "id") > 0)
		{
			itemManager.getImage(num(it, "id")).addTo(ic);
		}
		JLabel tx = new JLabel(html.replaceFirst("^<html>", got ? "<html>\u2713 " : "<html>"));
		tx.setForeground(got ? GREEN : MUTED);
		tx.setFont(font(Font.PLAIN, 12f));
		row.add(ic);
		row.add(tx);
		return row;
	}

	private JPanel tile(JsonObject t, Color team, int box)
	{
		JPanel p = new JPanel(new BorderLayout(0, 2));
		p.setPreferredSize(new Dimension(box, box));
		if (t.has("empty") && t.get("empty").getAsBoolean())
		{
			p.setBackground(BG);
			p.setBorder(BorderFactory.createLineBorder(new Color(0x2E2E2E), 1));
			return p;
		}
		boolean done = t.has("done") && t.get("done").getAsBoolean();
		boolean free = t.has("free") && t.get("free").getAsBoolean();
		int need = Math.max(1, num(t, "need"));
		int have = num(t, "have");
		String name = str(t, "name");
		p.setBackground(done ? new Color(team.getRed() / 4 + 20, team.getGreen() / 4 + 30, team.getBlue() / 4 + 20) : CARD);
		p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(done ? team : EDGE, done ? 2 : 1), BorderFactory.createEmptyBorder(3, 3, 2, 3)));

		StringBuilder tip = new StringBuilder("<html><b>" + esc(name) + "</b>");
		if (num(t, "points") > 1)
		{
			tip.append("<br>").append(num(t, "points")).append(" points");
		}
		JsonArray list = t.has("list") && t.get("list").isJsonArray() ? t.getAsJsonArray("list") : new JsonArray();
		if (list.size() > 0)
		{
			tip.append("<br>").append(need > 1 ? "Collect " + need + " of these:" : "Any one of these:");
			for (JsonElement el : list)
			{
				JsonObject it = el.getAsJsonObject();
				boolean got = it.has("got") && it.get("got").getAsBoolean();
				tip.append("<br>").append(got ? "<font color='#4cd964'>\u2713 " : "\u2022 ").append(esc(str(it, "n"))).append(got ? "</font>" : "");
			}
		}
		if (need > 1 && !done)
		{
			tip.append("<br>").append(Math.min(have, need)).append(" of ").append(need).append(list.size() > 0 ? " so far" : "");
		}
		if (done && !free && !str(t, "by").isEmpty())
		{
			tip.append("<br>Done by ").append(esc(str(t, "by")));
		}
		p.setToolTipText(tip.append("</html>").toString());
		JsonArray whoAdded = t.has("who") && t.get("who").isJsonArray() ? t.getAsJsonArray("who") : new JsonArray();
		if (list.size() > 0 || whoAdded.size() > 0)
		{
			p.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			p.addMouseListener(new java.awt.event.MouseAdapter()
			{
				@Override
				public void mouseClicked(java.awt.event.MouseEvent ev)
				{
					showTile(t);
				}
			});
		}

		JLabel icon = new JLabel();
		icon.setHorizontalAlignment(SwingConstants.CENTER);
		icon.setPreferredSize(new Dimension(box - 8, 32));
		int item = num(t, "item");
		boolean pictured = false;
		String data = str(t, "icon");
		if (!data.isEmpty())
		{
			try
			{
				java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(data)));
				if (img != null)
				{
					double sc = Math.min(1.0, Math.min(34.0 / img.getWidth(), 32.0 / img.getHeight()));
					int w = Math.max(1, (int) Math.round(img.getWidth() * sc));
					int h = Math.max(1, (int) Math.round(img.getHeight() * sc));
					icon.setIcon(new javax.swing.ImageIcon(sc < 1.0 ? img.getScaledInstance(w, h, java.awt.Image.SCALE_SMOOTH) : img));
					pictured = true;
				}
			}
			catch (java.io.IOException | RuntimeException ex)
			{
				// fall back to the item or a symbol
			}
		}
		if (!pictured && item > 0)
		{
			itemManager.getImage(item).addTo(icon);
		}
		else if (!pictured && free)
		{
			icon.setText("\u2605");
			icon.setFont(font(Font.BOLD, 22f));
			icon.setForeground(PINK);
		}
		else if (!pictured)
		{
			String k = str(t, "kind");
			icon.setText("kill".equals(k) ? "\u2694" : "speed".equals(k) ? "\u23F1" : "pet".equals(k) ? "\u2665" : "");
			icon.setFont(font(Font.PLAIN, 22f));
			icon.setForeground(MUTED);
		}
		JLabel nm = new JLabel();
		nm.setFont(font(Font.PLAIN, 10f));
		nm.setForeground(done ? TEXT : MUTED);
		nm.setHorizontalAlignment(SwingConstants.CENTER);
		nm.setVerticalAlignment(SwingConstants.TOP);
		nm.setText(wrap(free && name.isEmpty() ? "FREE" : name, nm.getFontMetrics(nm.getFont()), box - 14));
		p.add(icon, BorderLayout.NORTH);
		p.add(nm, BorderLayout.CENTER);
		String footText = done ? (free ? "" : "\u2713 done") : need > 1 || list.size() > 1 ? Math.min(have, need) + "/" + need : "";
		if (!footText.isEmpty())
		{
			JLabel foot = new JLabel(footText, SwingConstants.CENTER);
			foot.setForeground(done ? GREEN : PINK);
			foot.setFont(font(Font.BOLD, 11f));
			p.add(foot, BorderLayout.SOUTH);
		}
		return p;
	}
}
