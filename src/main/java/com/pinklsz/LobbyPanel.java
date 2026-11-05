package com.pinklsz;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.pinklsz.net.Dashboard;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicComboBoxUI;
import net.runelite.client.party.PartyService;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.ImageUtil;

/**
 * TeamFinder: the clan's open group lobbies. Anyone can post one (raid, boss) and clannies join it;
 * joining puts you on the list and shows you the host and their world, then you meet in game.
 */
@Singleton
public class LobbyPanel extends PluginPanel
{
	private static final Color PINK = new Color(0xEF3D9A);
	private static final Color PINK_DARK = new Color(0xC42D7E);
	private static final Color BG = new Color(0x1B1620);
	private static final Color CARD = new Color(0x26202D);
	private static final Color FIELD = new Color(0x15111A);
	private static final Color EDGE = new Color(0x4C4259);
	private static final Color TEXT = new Color(0xEDE7F2);
	private static final Color MUTED = new Color(0xCBC1D6);
	private static final Color DANGER = new Color(0xD9534F);
	private static final Color GOLD = new Color(0xFFB04A);

	private static final class Act
	{
		final String name;
		final int min;
		final int max;
		final boolean mass;
		final List<String> modes = new ArrayList<>();
		final boolean inv;
		final boolean ffa; // has a Split / FFA choice

		Act(JsonObject o)
		{
			ffa = !o.has("ffa") || o.get("ffa").getAsBoolean();
			name = o.get("name").getAsString();
			min = o.get("min").getAsInt();
			max = o.get("max").getAsInt();
			mass = o.get("mass").getAsBoolean();
			inv = o.get("inv").getAsBoolean();
			for (JsonElement m : o.getAsJsonArray("modes"))
			{
				modes.add(m.getAsString());
			}
		}

		@Override
		public String toString()
		{
			return name;
		}
	}

	private static final class Size
	{
		final int value; // 0 = mass

		Size(int value)
		{
			this.value = value;
		}

		@Override
		public String toString()
		{
			return value == 0 ? "Mass (9+ players)" : value + " players";
		}
	}

	@Inject
	private Dashboard dashboard;

	@Inject
	private PartyService partyService; // RuneLite's own party (the one the Party Panel plugin shows)

	private final JPanel list = new JPanel();
	private final JLabel status = new JLabel(" ");
	private final JLabel openCount = new JLabel("Open teams");
	private final JComboBox<Act> activity = new JComboBox<>();
	private final JComboBox<String> mode = new JComboBox<>();
	private final JComboBox<Size> size = new JComboBox<>();
	private final JTextField invocation = new JTextField();
	private final JTextField note = new JTextField();
	private final JTextField hub = new JTextField();
	private final JCheckBox ffa = new JCheckBox("FFA");
	private final JPanel modeRow = new JPanel();
	private final JPanel invRow = new JPanel();
	private final JPanel ffaRow = new JPanel();

	private boolean built;
	private boolean actsLoaded;
	private boolean mod;
	private volatile boolean inLobby; // hosting or joined: keep checking in even with the panel closed
	private String hostedMembers = "";
	private long hostedId = -1;
	private String goneHow = ""; // why the dashboard says you are no longer in a team
	private String hostName = "";
	private boolean fullTold;

	public LobbyPanel()
	{
		super();
	}

	// ---- small pieces of look and feel ----

	/** A clear sans-serif font: RuneLite's own pixel font is hard to read at small sizes. */
	private static Font F(int style, float size)
	{
		return new Font(Font.SANS_SERIF, style, 12).deriveFont(style, size);
	}

	private static void smooth(Graphics2D g)
	{
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
	}

	/** A panel with rounded corners and a thin edge. */
	private static class Round extends JPanel
	{
		private final Color fill;
		private final Color edge;
		private final int arc;

		Round(Color fill, Color edge, int arc)
		{
			this.fill = fill;
			this.edge = edge;
			this.arc = arc;
			setOpaque(false);
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			Graphics2D g = (Graphics2D) g0.create();
			smooth(g);
			g.setColor(fill);
			g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
			if (edge != null)
			{
				g.setColor(edge);
				g.setStroke(new BasicStroke(1f));
				g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
			}
			g.dispose();
			super.paintComponent(g0);
		}
	}

	/** A card that also shows a pink stripe down its left edge when you are in the lobby. */
	private static final class Accent extends Round
	{
		private final boolean on;

		Accent(boolean on)
		{
			super(CARD, on ? PINK : EDGE, 14);
			this.on = on;
		}

		@Override
		protected void paintComponent(Graphics g0)
		{
			super.paintComponent(g0);
			if (on)
			{
				Graphics2D g = (Graphics2D) g0.create();
				smooth(g);
				g.setColor(PINK);
				g.fillRoundRect(0, 10, 4, getHeight() - 21, 4, 4);
				g.dispose();
			}
		}
	}

	/** A rounded button: pink for the main action, outlined for the others. */
	private static JButton pill(String text, Color fill, boolean outline)
	{
		JButton b = new JButton(text)
		{
			private boolean hover;

			{
				addMouseListener(new MouseAdapter()
				{
					@Override
					public void mouseEntered(MouseEvent e)
					{
						hover = true;
						repaint();
					}

					@Override
					public void mouseExited(MouseEvent e)
					{
						hover = false;
						repaint();
					}
				});
			}

			@Override
			protected void paintComponent(Graphics g0)
			{
				Graphics2D g = (Graphics2D) g0.create();
				smooth(g);
				Color base = isEnabled() ? fill : EDGE;
				if (outline)
				{
					g.setColor(hover && isEnabled() ? new Color(base.getRed(), base.getGreen(), base.getBlue(), 50) : new Color(0, 0, 0, 0));
					g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
					g.setColor(base);
					g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
				}
				else
				{
					g.setColor(hover && isEnabled() ? base.brighter() : base);
					g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 20, 20);
				}
				g.dispose();
				super.paintComponent(g0);
			}
		};
		b.setContentAreaFilled(false);
		b.setBorderPainted(false);
		b.setFocusPainted(false);
		b.setFocusable(false);
		b.setOpaque(false);
		b.setForeground(outline ? (fill == PINK ? PINK : fill) : Color.WHITE);
		b.setFont(F(Font.BOLD, 13f));
		b.setMargin(new Insets(4, 14, 4, 14));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return b;
	}

	private static <T> void styleCombo(JComboBox<T> c)
	{
		c.setFont(F(Font.PLAIN, 13f));
		c.setBackground(FIELD);
		c.setForeground(TEXT);
		c.setFocusable(false);
		c.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), BorderFactory.createEmptyBorder(2, 6, 2, 2)));
		c.setUI(new BasicComboBoxUI()
		{
			@Override
			protected javax.swing.plaf.basic.ComboPopup createPopup()
			{
				javax.swing.plaf.basic.BasicComboPopup p = (javax.swing.plaf.basic.BasicComboPopup) super.createPopup();
				p.getList().setBackground(FIELD);
				p.getList().setForeground(TEXT);
				p.getList().setSelectionBackground(PINK_DARK);
				p.getList().setSelectionForeground(Color.WHITE);
				p.setBorder(BorderFactory.createLineBorder(EDGE));
				return p;
			}

			@Override
			public void paintCurrentValueBackground(Graphics g, java.awt.Rectangle bounds, boolean hasFocus)
			{
				g.setColor(FIELD);
				g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
			}

			@Override
			public void paintCurrentValue(Graphics g, java.awt.Rectangle bounds, boolean hasFocus)
			{
				Component r = comboBox.getRenderer().getListCellRendererComponent(listBox, comboBox.getSelectedItem(), -1, false, false);
				r.setFont(comboBox.getFont());
				r.setForeground(TEXT);
				r.setBackground(FIELD);
				currentValuePane.paintComponent(g, r, comboBox, bounds.x, bounds.y, bounds.width, bounds.height, false);
			}

			@Override
			protected javax.swing.JButton createArrowButton()
			{
				JButton arrow = new JButton()
				{
					@Override
					public void paint(Graphics g0)
					{
						Graphics2D g = (Graphics2D) g0.create();
						smooth(g);
						g.setColor(FIELD);
						g.fillRect(0, 0, getWidth(), getHeight());
						g.setColor(MUTED);
						int cx = getWidth() / 2;
						int cy = getHeight() / 2;
						g.fillPolygon(new int[]{cx - 4, cx + 4, cx}, new int[]{cy - 2, cy - 2, cy + 3}, 3);
						g.dispose();
					}
				};
				arrow.setBorder(BorderFactory.createEmptyBorder());
				arrow.setContentAreaFilled(false);
				return arrow;
			}
		});
		c.setRenderer(new DefaultListCellRenderer()
		{
			@Override
			public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean sel, boolean foc)
			{
				JLabel x = (JLabel) super.getListCellRendererComponent(l, v, i, sel, foc);
				x.setFont(F(Font.PLAIN, 13f));
				x.setBackground(sel ? PINK_DARK : FIELD);
				x.setForeground(TEXT);
				x.setBorder(BorderFactory.createEmptyBorder(3, 6, 3, 6));
				return x;
			}
		});
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
	}

	/** A dark text box that shows a hint while it is empty. */
	private static void styleField(JTextField f, String hint)
	{
		f.setFont(F(Font.PLAIN, 13f));
		f.setBackground(FIELD);
		f.setForeground(TEXT);
		f.setCaretColor(TEXT);
		f.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), BorderFactory.createEmptyBorder(4, 6, 4, 6)));
		f.setAlignmentX(Component.LEFT_ALIGNMENT);
		f.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		f.setLayout(new BorderLayout());
		JLabel ph = new JLabel(hint);
		ph.setForeground(new Color(0xA395B3));
		ph.setFont(F(Font.ITALIC, 11.5f));
		ph.setBorder(BorderFactory.createEmptyBorder(0, 7, 0, 0));
		f.add(ph, BorderLayout.CENTER);
		f.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
		{
			private void sync()
			{
				ph.setVisible(f.getText().isEmpty());
			}

			@Override
			public void insertUpdate(javax.swing.event.DocumentEvent e)
			{
				sync();
			}

			@Override
			public void removeUpdate(javax.swing.event.DocumentEvent e)
			{
				sync();
			}

			@Override
			public void changedUpdate(javax.swing.event.DocumentEvent e)
			{
				sync();
			}
		});
	}

	private static JLabel caption(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(MUTED);
		l.setFont(F(Font.BOLD, 11.5f));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		l.setBorder(BorderFactory.createEmptyBorder(6, 1, 3, 0));
		return l;
	}

	private static JLabel chip(String text, Color color)
	{
		JLabel l = new JLabel(text)
		{
			@Override
			protected void paintComponent(Graphics g0)
			{
				Graphics2D g = (Graphics2D) g0.create();
				smooth(g);
				g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 70));
				g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 12, 12);
				g.dispose();
				super.paintComponent(g0);
			}
		};
		l.setForeground(color);
		l.setFont(F(Font.BOLD, 11.5f));
		l.setBorder(BorderFactory.createEmptyBorder(1, 7, 1, 7));
		l.setOpaque(false);
		return l;
	}

	private static Component gap(int h)
	{
		return Box.createRigidArea(new Dimension(0, h));
	}

	private static JPanel stack(JComponent c)
	{
		c.setLayout(new BoxLayout(c, BoxLayout.Y_AXIS));
		return (JPanel) c;
	}

	// ---- building the panel ----

	/** Called by the plugin once everything is injected. */
	void build()
	{
		if (built)
		{
			return;
		}
		built = true;
		setLayout(new BorderLayout());
		setBackground(BG);
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

		JPanel body = stack(new JPanel());
		body.setBackground(BG);

		// header
		JPanel head = new JPanel(new BorderLayout(8, 0));
		head.setBackground(BG);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		BufferedImage img = ImageUtil.loadImageResource(PinkLszPlugin.class, "icon.png");
		JLabel logo = new JLabel(new ImageIcon(img));
		JLabel title = new JLabel("TeamFinder");
		title.setForeground(TEXT);
		title.setFont(F(Font.BOLD, 17f));
		JPanel left = new JPanel(new BorderLayout(8, 0));
		left.setBackground(BG);
		left.add(logo, BorderLayout.WEST);
		left.add(title, BorderLayout.CENTER);
		head.add(left, BorderLayout.CENTER);
		JButton refresh = pill("Refresh", MUTED, true);
		refresh.setMargin(new Insets(2, 8, 2, 8));
		refresh.addActionListener(e -> refresh());
		head.add(refresh, BorderLayout.EAST);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		body.add(head);
		JLabel sub = new JLabel("<html><body style='width:165px'>Find a team for group content. Join a team, then meet the host in game.</body></html>");
		sub.setForeground(MUTED);
		sub.setFont(F(Font.PLAIN, 12.5f));
		sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		sub.setBorder(BorderFactory.createEmptyBorder(4, 2, 8, 0));
		body.add(sub);

		// create (first)
		Round form = new Round(CARD, EDGE, 14);
		stack(form);
		form.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
		form.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel ft = new JLabel("Create a team");
		ft.setForeground(PINK);
		ft.setFont(F(Font.BOLD, 14f));
		ft.setAlignmentX(Component.LEFT_ALIGNMENT);
		form.add(ft);
		form.add(caption("ACTIVITY"));
		styleCombo(activity);
		activity.addActionListener(e -> activityChanged());
		form.add(activity);

		stack(modeRow);
		modeRow.setOpaque(false);
		modeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		modeRow.add(caption("MODE"));
		styleCombo(mode);
		modeRow.add(mode);
		form.add(modeRow);

		stack(invRow);
		invRow.setOpaque(false);
		invRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		invRow.add(caption("INVOCATION LEVEL (0 - 600)"));
		styleField(invocation, "e.g. 300");
		invRow.add(invocation);
		form.add(invRow);

		form.add(caption("TEAM SIZE"));
		styleCombo(size);
		form.add(size);
		form.add(caption("PARTY HUB (OPTIONAL)"));
		styleField(hub, "Party passphrase");
		hub.setToolTipText("The RuneLite party passphrase for this team. You are put into it when you create the team, and players who press Join are put into it too");
		form.add(hub);
		form.add(caption("NOTES (OPTIONAL)"));
		styleField(note, "Learner friendly, KC/gear");
		note.setToolTipText("Optional, e.g. \"Learner friendly\" or \"300+ KC, scythe preferred\"");
		form.add(note);
		stack(ffaRow);
		ffaRow.setOpaque(false);
		ffaRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		ffa.setOpaque(false);
		ffa.setForeground(TEXT);
		ffa.setFont(F(Font.BOLD, 13f));
		ffa.setFocusPainted(false);
		ffa.setIcon(boxIcon(false));
		ffa.setSelectedIcon(boxIcon(true));
		ffa.setIconTextGap(8);
		ffa.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		ffa.setToolTipText("Tick for free for all loot. Unticked shows as Split");
		ffa.setAlignmentX(Component.LEFT_ALIGNMENT);
		ffaRow.add(gap(6));
		ffaRow.add(ffa);
		form.add(ffaRow);
		form.add(gap(10));
		JButton create = pill("Create team", PINK, false);
		create.setAlignmentX(Component.LEFT_ALIGNMENT);
		create.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		create.addActionListener(e -> create());
		form.add(create);
		body.add(form);

		status.setForeground(MUTED);
		status.setFont(F(Font.PLAIN, 12.5f));
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		status.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 0));
		body.add(status);

		// the open lobbies
		openCount.setForeground(TEXT);
		openCount.setFont(F(Font.BOLD, 14f));
		openCount.setAlignmentX(Component.LEFT_ALIGNMENT);
		openCount.setBorder(BorderFactory.createEmptyBorder(12, 2, 6, 0));
		body.add(openCount);
		stack(list);
		list.setBackground(BG);
		list.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(list);

		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(BG);
		wrap.add(body, BorderLayout.NORTH);
		add(wrap, BorderLayout.CENTER);

		modeRow.setVisible(false);
		invRow.setVisible(false);
		render(new JsonArray());
	}

	// ---- talking to the site ----

	/** RuneLite calls this each time the panel is opened. */
	@Override
	public void onActivate()
	{
		refresh();
	}

	/** Safe to call from any thread. */
	public void refresh()
	{
		send("list", null);
	}

	public boolean wantsPolling()
	{
		return inLobby || isShowing();
	}

	private void create()
	{
		Act a = (Act) activity.getSelectedItem();
		if (a == null)
		{
			setStatus("Loading the activity list. Try again in a moment.", false);
			return;
		}
		JsonObject d = new JsonObject();
		d.addProperty("activity", a.name);
		if (!a.modes.isEmpty() && mode.getSelectedItem() != null)
		{
			d.addProperty("mode", (String) mode.getSelectedItem());
		}
		if (a.inv)
		{
			String t = invocation.getText().replaceAll("[^0-9]", "");
			d.addProperty("invocation", t.isEmpty() ? 0 : Math.min(600, Integer.parseInt(t.length() > 4 ? "600" : t)));
		}
		Object sz = size.getSelectedItem();
		d.addProperty("size", sz instanceof Size ? ((Size) sz).value : a.max);
		d.addProperty("note", note.getText());
		d.addProperty("hub", hub.getText().trim());
		d.addProperty("ffa", a.ffa && ffa.isSelected());
		send("create", d);
		note.setText("");
	}

	private void act(String op, int id)
	{
		JsonObject d = new JsonObject();
		d.addProperty("id", id);
		send(op, d);
	}

	private void send(String op, JsonObject extra)
	{
		JsonObject d = extra == null ? new JsonObject() : extra;
		d.addProperty("op", op);
		dashboard.call("pl_lobby", d, reply -> SwingUtilities.invokeLater(() -> apply(op, reply)));
	}

	private void apply(String op, JsonObject reply)
	{
		if (reply.has("error"))
		{
			setStatus(reply.get("error").getAsString(), true);
			return;
		}
		setStatus(" ", false);
		mod = reply.has("mod") && reply.get("mod").getAsBoolean();
		if (!actsLoaded && reply.has("activities") && reply.get("activities").isJsonArray())
		{
			for (JsonElement e : reply.get("activities").getAsJsonArray())
			{
				activity.addItem(new Act(e.getAsJsonObject()));
			}
			actsLoaded = true;
			activityChanged();
		}
		goneHow = reply.has("gone") && reply.get("gone").isJsonObject() && reply.get("gone").getAsJsonObject().has("how") ? reply.get("gone").getAsJsonObject().get("how").getAsString() : "";
		JsonArray lobbies = reply.has("lobbies") && reply.get("lobbies").isJsonArray() ? reply.get("lobbies").getAsJsonArray() : new JsonArray();
		announce(op, lobbies);
		render(lobbies);
	}

	/** Chat lines for the team you are in (hosting or joined): what you joined, who joins after, and when it is full. */
	private void announce(String op, JsonArray lobbies)
	{
		JsonObject mineTeam = null;
		for (JsonElement e : lobbies)
		{
			JsonObject l = e.getAsJsonObject();
			if (l.get("in").getAsBoolean())
			{
				mineTeam = l;
				break;
			}
		}
		inLobby = mineTeam != null;
		if (mineTeam == null)
		{
			if (hostedId != -1 && "list".equals(op))
			{
				String how = goneHow;
				dashboard.note("away".equals(how) ? "You were removed from the team: the dashboard heard nothing from your game for 45 seconds."
					: "removed".equals(how) ? "A moderator removed the team."
					: "closed".equals(how) ? "The host closed the team."
					: "You are no longer in the team.");
			}
			hostedId = -1;
			hostName = "";
			hostedMembers = "";
			fullTold = false;
			return;
		}
		long id = mineTeam.get("id").getAsLong();
		boolean hosting = mineTeam.get("mine").getAsBoolean();
		String members = joinNames(mineTeam.getAsJsonArray("members"));
		int count = mineTeam.getAsJsonArray("members").size();
		int size = mineTeam.get("size").getAsInt();
		boolean full = size > 0 && count >= size;
		if (id != hostedId)
		{
			// first time we see this team: no "joined" lines for people already in it
			if (hosting && "create".equals(op))
			{
				joinPartyHub(mineTeam, true);
			}
			if (!hosting && "join".equals(op))
			{
				String w = mineTeam.get("world").isJsonNull() ? "" : " on world " + mineTeam.get("world").getAsInt();
				joinPartyHub(mineTeam, false);
				dashboard.note("Joined " + mineTeam.get("host").getAsString() + "'s " + mineTeam.get("activity").getAsString() + " team" + w + ". Meet them in game.");
			}
			fullTold = full;
			if (full && "join".equals(op))
			{
				dashboard.note(fullLine(mineTeam, members, count, size));
			}
		}
		else
		{
			String hostNow = mineTeam.get("host").getAsString();
			if (!hostName.isEmpty() && !hostName.equals(hostNow))
			{
				dashboard.note(hosting ? "You are now the host of the team." : hostNow + " is now the host of the team.");
			}
			Set<String> before = new HashSet<>(Arrays.asList(hostedMembers.split("\n")));
			for (String m : members.split("\n"))
			{
				if (!m.isEmpty() && !before.contains(m))
				{
					dashboard.note(m + (hosting ? " joined your team." : " joined the team.") + (size > 0 ? " (" + count + "/" + size + ")" : ""));
				}
			}
			Set<String> now = new HashSet<>(Arrays.asList(members.split("\n")));
			for (String m : before)
			{
				if (!m.isEmpty() && !now.contains(m))
				{
					dashboard.note(m + " left the team." + (size > 0 ? " (" + count + "/" + size + ")" : ""));
				}
			}
			if (full && !fullTold)
			{
				dashboard.note(fullLine(mineTeam, members, count, size));
			}
			fullTold = full;
		}
		hostedId = id;
		hostName = mineTeam.get("host").getAsString();
		hostedMembers = members;
	}

	private static String fullLine(JsonObject team, String members, int count, int size)
	{
		String world = team.has("world") && !team.get("world").isJsonNull() ? " Hop to world " + team.get("world").getAsInt() + "!" : "";
		return "Team full (" + count + "/" + size + "): Time to go!" + world;
	}

	/** Pressing Join also puts you in the host's RuneLite party (the one Party Panel shows), if the host listed a party hub. */
	private void joinPartyHub(JsonObject team, boolean created)
	{
		String hubName = team.has("hub") && !team.get("hub").isJsonNull() ? team.get("hub").getAsString().trim() : "";
		if (hubName.isEmpty())
		{
			return;
		}
		try
		{
			if (!hubName.equals(partyService.getPartyPassphrase()))
			{
				partyService.changeParty(hubName);
			}
		}
		catch (RuntimeException e)
		{
			dashboard.note("Could not join the party hub \"" + hubName + "\". Join it from the Party Panel with that passphrase.");
		}
	}

	/** A clear tick box: an outlined square, filled pink with a white tick when it is on. */
	private static ImageIcon boxIcon(boolean on)
	{
		BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		smooth(g);
		g.setColor(on ? PINK : FIELD);
		g.fillRoundRect(1, 1, 17, 17, 6, 6);
		g.setColor(on ? PINK : MUTED);
		g.setStroke(new BasicStroke(2f));
		g.drawRoundRect(1, 1, 17, 17, 6, 6);
		if (on)
		{
			g.setColor(Color.WHITE);
			g.setStroke(new BasicStroke(2.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawLine(5, 10, 8, 13);
			g.drawLine(8, 13, 14, 6);
		}
		g.dispose();
		return new ImageIcon(img);
	}

	private static String joinNames(JsonArray a)
	{
		StringBuilder sb = new StringBuilder();
		for (JsonElement e : a)
		{
			if (sb.length() > 0)
			{
				sb.append('\n');
			}
			sb.append(e.getAsString());
		}
		return sb.toString();
	}

	// ---- drawing ----

	private void setStatus(String text, boolean bad)
	{
		status.setText("<html><body style='width:165px'>" + escape(text) + "</body></html>");
		status.setForeground(bad ? new Color(0xFF7B7B) : MUTED);
	}

	private void activityChanged()
	{
		Act a = (Act) activity.getSelectedItem();
		mode.removeAllItems();
		size.removeAllItems();
		if (a == null)
		{
			return;
		}
		for (String m : a.modes)
		{
			mode.addItem(m);
		}
		modeRow.setVisible(!a.modes.isEmpty());
		invRow.setVisible(a.inv);
		ffaRow.setVisible(a.ffa);
		if (!a.ffa)
		{
			ffa.setSelected(false);
		}
		for (int i = a.min; i <= a.max; i++)
		{
			size.addItem(new Size(i));
		}
		if (a.mass)
		{
			size.addItem(new Size(0));
		}
		size.setSelectedIndex(Math.max(0, Math.min(size.getItemCount() - 1, 4 - a.min)));
		revalidate();
		repaint();
	}

	private void render(JsonArray lobbies)
	{
		list.removeAll();
		openCount.setText("Open teams (" + lobbies.size() + ")");
		if (lobbies.size() == 0)
		{
			Round empty = new Round(CARD, EDGE, 14);
			empty.setLayout(new BorderLayout());
			empty.setBorder(BorderFactory.createEmptyBorder(14, 12, 14, 12));
			JLabel none = new JLabel("<html><body style='width:150px'>Nobody is looking for a team right now. Be the first: create a team above.</body></html>");
			none.setForeground(MUTED);
			empty.add(none, BorderLayout.CENTER);
			empty.setAlignmentX(Component.LEFT_ALIGNMENT);
			list.add(empty);
		}
		for (JsonElement e : lobbies)
		{
			list.add(card(e.getAsJsonObject()));
			list.add(gap(8));
		}
		list.revalidate();
		list.repaint();
	}

	private JPanel card(JsonObject l)
	{
		boolean mine = l.get("mine").getAsBoolean();
		boolean in = l.get("in").getAsBoolean();
		JsonArray members = l.getAsJsonArray("members");
		int cap = l.get("size").getAsInt();
		boolean full = cap > 0 && members.size() >= cap;
		int id = l.get("id").getAsInt();

		JPanel c = stack(new Accent(in));
		c.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 10));
		c.setAlignmentX(Component.LEFT_ALIGNMENT);

		JLabel head = new JLabel("<html><body style='width:150px'>" + escape(l.get("activity").getAsString()) + "</body></html>");
		head.setForeground(TEXT);
		head.setFont(F(Font.BOLD, 14f));
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		c.add(head);
		c.add(gap(5));

		JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		chips.setOpaque(false);
		chips.setAlignmentX(Component.LEFT_ALIGNMENT);
		chips.setBorder(BorderFactory.createEmptyBorder(0, -4, 0, 0));
		chips.add(chip(cap == 0 ? "Mass · " + members.size() : members.size() + "/" + cap, GOLD));
		String modeText = l.get("mode").getAsString();
		if (!modeText.isEmpty())
		{
			chips.add(chip(modeText, new Color(0x9C8CFF)));
		}
		if (!l.get("invocation").isJsonNull())
		{
			chips.add(chip("Inv " + l.get("invocation").getAsInt(), new Color(0x4AD1A0)));
		}
		if (l.has("ffa") && !l.get("ffa").isJsonNull())
		{
			chips.add(chip(l.get("ffa").getAsBoolean() ? "FFA" : "Split", new Color(0xFF7BAC)));
		}
		if (!l.get("world").isJsonNull())
		{
			chips.add(chip("W" + l.get("world").getAsInt(), new Color(0x5AB0FF)));
		}
		c.add(chips);
		c.add(gap(6));

		StringBuilder sb = new StringBuilder("<html><body style='width:150px'>");
		for (JsonElement m : members)
		{
			String n = m.getAsString();
			boolean host = n.equalsIgnoreCase(l.get("host").getAsString());
			sb.append(host ? "<font color='#ef3d9a'>&#9818;</font> " : "&bull; ").append(escape(n)).append(host ? " <font color='#a79cb3'>(host)</font>" : "").append("<br>");
		}
		String noteText = l.get("note").getAsString();
		if (!noteText.isEmpty())
		{
			sb.append("<i><font color='#a79cb3'>").append(escape(noteText)).append("</font></i>");
		}
		sb.append("</body></html>");
		JLabel names = new JLabel(sb.toString());
		names.setForeground(TEXT);
		names.setFont(F(Font.PLAIN, 13f));
		names.setAlignmentX(Component.LEFT_ALIGNMENT);
		c.add(names);
		c.add(gap(8));

		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		actions.setOpaque(false);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		actions.setBorder(BorderFactory.createEmptyBorder(0, -6, 0, 0));
		if (mine)
		{
			JButton b = pill("Close team", DANGER, true);
			b.addActionListener(ev -> act("close", id));
			actions.add(b);
		}
		else if (in)
		{
			JButton b = pill("Leave", MUTED, true);
			b.addActionListener(ev -> act("leave", id));
			actions.add(b);
		}
		else
		{
			JButton b = pill(full ? "Full" : "Join", PINK, false);
			b.setEnabled(!full);
			b.addActionListener(ev -> act("join", id));
			actions.add(b);
		}
		if (mod && !mine)
		{
			JButton rm = pill("Remove", DANGER, true);
			rm.setToolTipText("Moderator: take this team down");
			rm.addActionListener(ev -> act("remove", id));
			actions.add(rm);
		}
		c.add(actions);
		return c;
	}

	private static String escape(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}
}
