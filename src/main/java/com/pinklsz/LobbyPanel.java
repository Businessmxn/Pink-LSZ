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
import javax.swing.JScrollPane;
import javax.swing.SwingConstants;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicComboBoxUI;
import net.runelite.client.game.ItemManager;
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
	private static final Color BG = ColorScheme.DARK_GRAY_COLOR;
	private static final Color CARD = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color FIELD = ColorScheme.DARKER_GRAY_COLOR;
	private static final Color EDGE = ColorScheme.MEDIUM_GRAY_COLOR;
	private static final Color TEXT = new Color(0xEEEEEE);
	private static final Color MUTED = new Color(0xB4B4B4);
	private static final Color DANGER = new Color(0xD9534F);
	private static final Color GOLD = new Color(0xFFB04A);

	// RuneLite's scroll pane moves 1px per wheel step unless told otherwise, which crawls on a small client.
	// Once the panel is on screen, find that scroll pane and give it a sensible step.
	@Override
	public void addNotify()
	{
		super.addNotify();
		javax.swing.SwingUtilities.invokeLater(() ->
		{
			java.awt.Container sp = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane.class, this);
			if (sp instanceof javax.swing.JScrollPane)
			{
				javax.swing.JScrollBar bar = ((javax.swing.JScrollPane) sp).getVerticalScrollBar();
				bar.setUnitIncrement(24);
				bar.setBlockIncrement(160);
				// the same dark colour behind the whole panel (it showed grey under the content), and no outline from the scroll pane
				javax.swing.JScrollPane pane = (javax.swing.JScrollPane) sp;
				pane.setBorder(BorderFactory.createEmptyBorder());
				pane.setViewportBorder(null);
				pane.setBackground(BG);
				pane.getViewport().setBackground(BG);
				pane.setFocusable(false);
				pane.getViewport().setFocusable(false);
			}
		});
	}

	private static final class Act
	{
		final String name;
		final int min;
		final int max;
		final boolean mass;
		final List<String> modes = new ArrayList<>();
		final boolean inv;
		final boolean ffa; // has a Split / FFA choice
		final String group;
		final boolean header; // a group title in the list (Raids, Bosses, Minigames), never a real choice
		final boolean placeholder; // the "Pick an activity" starting row

		Act(String title, boolean pick)
		{
			header = false;
			placeholder = true;
			name = title;
			group = "";
			min = 0;
			max = 0;
			mass = false;
			inv = false;
			ffa = true;
		}

		boolean pickable()
		{
			return !header && !placeholder;
		}

		Act(String title)
		{
			placeholder = false;
			header = true;
			name = title;
			group = title;
			min = 0;
			max = 0;
			mass = false;
			inv = false;
			ffa = false;
		}

		Act(JsonObject o)
		{
			placeholder = false;
			header = false;
			group = o.has("group") && !o.get("group").isJsonNull() ? o.get("group").getAsString() : "";
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

	/** Fills an activity box in the site's order, with a title row at the start of each group. */
	private static void fillActs(JComboBox<Act> box, JsonArray list)
	{
		String last = null;
		for (JsonElement e : list)
		{
			Act a = new Act(e.getAsJsonObject());
			if (!a.group.isEmpty() && !a.group.equals(last))
			{
				box.addItem(new Act(a.group));
				last = a.group;
			}
			box.addItem(a);
		}
		box.setSelectedIndex(-1); // nothing chosen yet: the box shows its grey hint
	}

	/** A title row can't be chosen: move to the next real activity. True when it had to move. */
	private static boolean skipHeader(JComboBox<Act> box)
	{
		Act sel = (Act) box.getSelectedItem();
		if (sel == null || !sel.header)
		{
			return false;
		}
		int i = box.getSelectedIndex();
		box.setSelectedIndex(Math.min(box.getItemCount() - 1, i + 1));
		return true;
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
			return value == 0 ? "Mass" : Integer.toString(value);
		}
	}

	@Inject
	private Dashboard dashboard;

	@Inject
	private ItemManager itemManager;

	@Inject
	private net.runelite.client.Notifier notifier;

	@Inject
	private net.runelite.client.game.WorldService worldService;

	@Inject
	private PinkLszConfig pinkConfig;

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
	private final JCheckBox learnerFriendly = new JCheckBox("Learner-friendly");
	private final JPanel modeRow = new JPanel();
	private final JPanel invRow = new JPanel();
	private final JPanel ffaRow = new JPanel();
	// "available for a team"
	private final JComboBox<Act> availActivity = new JComboBox<>();
	private final JComboBox<String> availSize = new JComboBox<>();
	private final Round availCard = new Round(CARD, EDGE, 14);
	private final JCheckBox availFfa = new JCheckBox("FFA");
	private final JCheckBox availLearner = new JCheckBox("Learner");
	private final JComboBox<String> availMode = new JComboBox<>();
	private final JPanel availModeRow = new JPanel();
	private JButton availGo;
	private final JPanel availForm = new JPanel();
	private final JLabel createTitle = new JLabel("Create a team");
	private final JPanel createBody = new JPanel();
	private boolean createOpen = true; // open by default and again after every login
	private final JPanel availOn = new JPanel();
	private final JLabel availSummary = new JLabel(" ");
	private final JLabel availTitle = new JLabel("Available for a team");
	private boolean availOpen; // the form stays folded until the title is clicked
	private final JPanel invitesBox = new JPanel();
	private final JPanel playersBox = new JPanel();
	private final java.util.Set<Integer> invitesSeen = new java.util.HashSet<>();
	private volatile boolean availActive; // keeps checking in with the panel closed, so the status stays on
	private JsonArray lastLobbies = new JsonArray();

	private boolean built;
	private final java.awt.CardLayout cardLayout = new java.awt.CardLayout();
	private final JPanel cards = new JPanel(cardLayout);
	private final JPanel tabBar = new JPanel(new java.awt.GridLayout(1, 2, 6, 0));
	private final JPanel eventsBody = new JPanel();
	private final JPanel eventsList = new JPanel();
	private final java.util.Map<Integer, BingoBoardWindow> boardWindows = new java.util.HashMap<>();
	private final JLabel eventsNote = new JLabel(" ");
	private String tab = "teams";
	private volatile boolean eventsChecking;
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
	// small round "two arrows in a circle" refresh button
	/** A small icon button that opens a link in the browser. */
	private static JButton linkButton(String resource, String tip, String url)
	{
		BufferedImage img = ImageUtil.loadImageResource(PinkLszPlugin.class, resource);
		JButton b = new JButton(new ImageIcon(img.getScaledInstance(18, 18, java.awt.Image.SCALE_SMOOTH)));
		b.setToolTipText(tip);
		b.setPreferredSize(new Dimension(26, 26));
		b.setContentAreaFilled(false);
		b.setBorderPainted(false);
		b.setFocusPainted(false);
		b.setOpaque(false);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addActionListener(e -> net.runelite.client.util.LinkBrowser.browse(url));
		return b;
	}

	private JButton refreshIcon()
	{
		JButton b = new JButton()
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
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				// one open arc with a single arrowhead (the usual "refresh" arrow), drawn on its own with no circle around it
				int w = getWidth();
				int h = getHeight();
				g2.setColor(hover ? Color.WHITE : PINK);
				g2.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
				double cx = w / 2.0;
				double cy = h / 2.0;
				double r = Math.min(w, h) * 0.28;
				double start = 25;
				double end = 78;
				g2.draw(new java.awt.geom.Arc2D.Double(cx - r, cy - r, r * 2, r * 2, start, -(360 - (end - start)), java.awt.geom.Arc2D.OPEN));
				double a = Math.toRadians(end);
				double ex = cx + r * Math.cos(a);
				double ey = cy - r * Math.sin(a);
				double tx = Math.sin(a);
				double ty = Math.cos(a);
				double nx = -ty;
				double ny = tx;
				java.awt.geom.Path2D.Double head = new java.awt.geom.Path2D.Double();
				head.moveTo(ex - tx * 3.0 + nx * 2.8, ey - ty * 3.0 + ny * 2.8);
				head.lineTo(ex + tx * 0.6, ey + ty * 0.6);
				head.lineTo(ex - tx * 3.0 - nx * 2.8, ey - ty * 3.0 - ny * 2.8);
				g2.draw(head);
				g2.dispose();
			}
		};
		b.setPreferredSize(new Dimension(24, 24));
		b.setToolTipText("Refresh");
		b.setContentAreaFilled(false);
		b.setBorderPainted(false);
		b.setFocusPainted(false);
		b.setOpaque(false);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addActionListener(e -> refresh());
		return b;
	}

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
		c.setPreferredSize(new Dimension(c.getPreferredSize().width, FIELD_H)); // every field is the same height
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, FIELD_H));
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
				if (comboBox.getSelectedItem() != null)
				{
					r.setFont(comboBox.getFont());
					r.setForeground(TEXT);
				}
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
				String hint = (String) c.getClientProperty("hint");
				boolean title = v instanceof Act && ((Act) v).header;
				JLabel x = (JLabel) super.getListCellRendererComponent(l, v, i, sel && !title, foc);
				x.setFont(F(title ? Font.BOLD : Font.PLAIN, title ? 11.5f : 13f));
				x.setBackground(sel && !title ? PINK_DARK : FIELD);
				x.setForeground(title ? PINK : TEXT);
				x.setText(title && v != null ? v.toString().toUpperCase() : x.getText());
				if (v == null && i == -1 && hint != null)
				{
					x.setText(hint);
					x.setForeground(new Color(0x9A9A9A));
					x.setFont(F(Font.ITALIC, 13f));
				}
				x.setBorder(BorderFactory.createEmptyBorder(3, title ? 4 : 12, 3, 6));
				return x;
			}
		});
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		c.setPreferredSize(new Dimension(c.getPreferredSize().width, 32)); // tall enough that the border is never clipped
		c.setMinimumSize(new Dimension(10, 32));
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
	}

	private static final int FIELD_H = 30;
	private static int captionH()
	{
		return caption("SIZE").getPreferredSize().height; // the caption's text plus its padding
	}

	/** the field in a box of exactly the standard height, so side by side fields always match */
	private static JPanel fixedH(JComponent c)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setOpaque(false);
		p.add(c, BorderLayout.CENTER);
		p.setPreferredSize(new Dimension(Math.max(60, c.getPreferredSize().width), FIELD_H));
		p.setMinimumSize(new Dimension(10, FIELD_H));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, FIELD_H));
		p.setAlignmentX(Component.LEFT_ALIGNMENT);
		return p;
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
		f.setPreferredSize(new Dimension(f.getPreferredSize().width, FIELD_H));
		f.setMaximumSize(new Dimension(Integer.MAX_VALUE, FIELD_H));
		f.setLayout(new BorderLayout());
		JLabel ph = new JLabel(hint);
		ph.setForeground(new Color(0x9A9A9A));
		ph.setFont(F(Font.ITALIC, 11.5f));
		ph.setBorder(BorderFactory.createEmptyBorder(0, 7, 0, 0));
		f.add(ph, BorderLayout.CENTER);
		f.putClientProperty("hintLabel", ph);
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

	/** Changes the grey hint shown inside an empty text box. */
	private static void setHint(JTextField f, String text)
	{
		Object ph = f.getClientProperty("hintLabel");
		if (ph instanceof JLabel)
		{
			((JLabel) ph).setText(text);
		}
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
		body.add(header("TeamFinder"));
		JLabel sub = new JLabel("<html><body style='width:165px'>Find a team for group content. Join a team, then meet the host in game.<br><br>If the host listed a party hub, pressing Join automatically puts you in their party hub.</body></html>");
		sub.setForeground(MUTED);
		sub.setFont(F(Font.PLAIN, 12.5f));
		sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		sub.setBorder(BorderFactory.createEmptyBorder(4, 2, 8, 0));
		body.add(sub);

		// invites waiting for you
		stack(invitesBox);
		invitesBox.setBackground(BG);
		invitesBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(invitesBox);

		// create (first)
		Round form = new Round(CARD, EDGE, 14);
		stack(form);
		form.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
		form.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel ft = createTitle;
		ft.setForeground(PINK);
		ft.setFont(F(Font.BOLD, 14f));
		ft.setAlignmentX(Component.LEFT_ALIGNMENT);
		ft.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		ft.setToolTipText("Click to fold or open");
		ft.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				createOpen = !createOpen;
				showCreate();
			}
		});
		form.add(ft);
		stack(createBody);
		createBody.setOpaque(false);
		createBody.setAlignmentX(Component.LEFT_ALIGNMENT);
		form.add(createBody);
		createBody.add(caption("ACTIVITY"));
		activity.putClientProperty("hint", "Pick an activity");
		styleCombo(activity);
		activity.addActionListener(e ->
		{
			if (!skipHeader(activity))
			{
				activityChanged();
			}
		});
		createBody.add(fixedH(activity));

		stack(modeRow);
		modeRow.setOpaque(false);
		modeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		modeRow.add(caption("MODE"));
		styleCombo(mode);
		modeRow.add(fixedH(mode));
		createBody.add(modeRow);

		stack(invRow);
		invRow.setOpaque(false);
		invRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		invRow.add(caption("INVOCATION LEVEL (0 - 600)"));
		styleField(invocation, "e.g. 300");
		invRow.add(fixedH(invocation));
		createBody.add(invRow);

		// team size and party hub share a line: size is narrow, the passphrase takes the rest
		JPanel sizeCol = new JPanel();
		stack(sizeCol);
		sizeCol.setOpaque(false);
		sizeCol.add(caption("SIZE"));
		styleCombo(size);
		sizeCol.add(fixedH(size));
		size.setToolTipText("Number of players (Mass is 9 or more)");
		sizeCol.setPreferredSize(new Dimension(88, captionH() + FIELD_H));
		JPanel hubCol = new JPanel();
		stack(hubCol);
		hubCol.setOpaque(false);
		hubCol.add(caption("PARTY HUB"));
		styleField(hub, "Party passphrase");
		hub.setToolTipText("The RuneLite party passphrase for this team. You are put into it when you create the team, and players who press Join are put into it too");
		hubCol.add(fixedH(hub));
		JPanel sizeHub = new JPanel(new BorderLayout(8, 0));
		sizeHub.setOpaque(false);
		sizeHub.setAlignmentX(Component.LEFT_ALIGNMENT);
		sizeHub.setMaximumSize(new Dimension(Integer.MAX_VALUE, captionH() + FIELD_H));
		sizeHub.add(sizeCol, BorderLayout.WEST);
		sizeHub.add(hubCol, BorderLayout.CENTER);
		createBody.add(sizeHub);
		createBody.add(caption("NOTES (OPTIONAL)"));
		styleField(note, "Roles, KC/Gear");
		note.setToolTipText("Optional, e.g. \"Need a frz/dps\"");
		createBody.add(fixedH(note));
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
		createBody.add(ffaRow);
		learnerFriendly.setOpaque(false);
		learnerFriendly.setForeground(TEXT);
		learnerFriendly.setFont(F(Font.BOLD, 13f));
		learnerFriendly.setFocusPainted(false);
		learnerFriendly.setIcon(boxIcon(false));
		learnerFriendly.setSelectedIcon(boxIcon(true));
		learnerFriendly.setIconTextGap(8);
		learnerFriendly.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		learnerFriendly.setToolTipText("Tick if learners are welcome in this team");
		learnerFriendly.setAlignmentX(Component.LEFT_ALIGNMENT);
		createBody.add(gap(4));
		createBody.add(learnerFriendly);
		createBody.add(gap(10));
		JButton create = pill("Create team", PINK, false);
		create.setAlignmentX(Component.LEFT_ALIGNMENT);
		create.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		create.addActionListener(e -> create());
		createBody.add(create);
		body.add(form);

		status.setForeground(MUTED);
		status.setFont(F(Font.PLAIN, 12.5f));
		status.setAlignmentX(Component.LEFT_ALIGNMENT);
		status.setBorder(BorderFactory.createEmptyBorder(6, 2, 0, 0));
		body.add(status);

		// available for a team
		Round avail = availCard;
		stack(avail);
		avail.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
		avail.setAlignmentX(Component.LEFT_ALIGNMENT);
		JLabel at = availTitle;
		at.setForeground(PINK);
		at.setFont(F(Font.BOLD, 14f));
		at.setAlignmentX(Component.LEFT_ALIGNMENT);
		at.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		at.setToolTipText("Let team hosts know you are free to join a team");
		at.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				availOpen = !availOpen;
				showAvailability();
			}
		});
		avail.add(at);
		stack(availForm);
		availForm.setOpaque(false);
		availForm.setAlignmentX(Component.LEFT_ALIGNMENT);
		availForm.add(caption("ACTIVITY"));
		availActivity.putClientProperty("hint", "Pick an activity");
		styleCombo(availActivity);
		availActivity.addActionListener(e ->
		{
			if (skipHeader(availActivity))
			{
				return;
			}
			Act sel = (Act) availActivity.getSelectedItem();
			boolean ok = sel != null && sel.pickable();
			availFfa.setVisible(!ok || sel.ffa);
			fillAvailModes(ok ? sel : null);
			fillAvailSizes(ok ? sel : null);
		});
		availForm.add(fixedH(availActivity));
		stack(availModeRow);
		availModeRow.setOpaque(false);
		availModeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		availModeRow.add(caption("MODE"));
		styleCombo(availMode);
		availModeRow.add(fixedH(availMode));
		availModeRow.setVisible(false);
		availForm.add(availModeRow);
		JPanel availSizeCol = new JPanel();
		stack(availSizeCol);
		availSizeCol.setOpaque(false);
		availSizeCol.add(caption("SIZE"));
		styleCombo(availSize);
		availSize.setToolTipText("How many players you would like in the team (Any is fine too)");
		availSizeCol.add(fixedH(availSize));
		availSizeCol.setPreferredSize(new Dimension(88, captionH() + FIELD_H));
		availFfa.setOpaque(false);
		availFfa.setForeground(TEXT);
		availFfa.setFont(F(Font.BOLD, 13f));
		availFfa.setFocusPainted(false);
		availFfa.setIcon(boxIcon(false));
		availFfa.setSelectedIcon(boxIcon(true));
		availFfa.setIconTextGap(8);
		availFfa.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		availFfa.setToolTipText("Tick if you are happy with free for all loot. Unticked means Split");
		// Learner's tick sits level with the SIZE caption's text and FFA's tick level with the bottom of the size box
		JPanel availFfaCol = new JPanel(new BorderLayout());
		availFfaCol.setOpaque(false);
		availLearner.setMargin(new Insets(0, 0, 0, 0));
		availFfa.setMargin(new Insets(0, 0, 0, 0));
		availLearner.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		availFfa.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		availFfaCol.add(availLearner, BorderLayout.NORTH);
		availFfaCol.add(availFfa, BorderLayout.SOUTH);
		availFfaCol.setPreferredSize(new Dimension(10, captionH() + FIELD_H)); // never taller than the row it sits in, or the card gets clipped
		JPanel availRow = new JPanel(new BorderLayout(10, 0));
		availRow.setOpaque(false);
		availRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		availRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, captionH() + FIELD_H));
		availRow.add(availSizeCol, BorderLayout.WEST);
		availRow.add(availFfaCol, BorderLayout.CENTER);
		availForm.add(availRow);
		availLearner.setOpaque(false);
		availLearner.setForeground(TEXT);
		availLearner.setFont(F(Font.BOLD, 13f));
		availLearner.setFocusPainted(false);
		availLearner.setIcon(boxIcon(false));
		availLearner.setSelectedIcon(boxIcon(true));
		availLearner.setIconTextGap(8);
		availLearner.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		availLearner.setToolTipText("Tick if you are still learning this content, so hosts know to explain mechanics");
		availLearner.setAlignmentX(Component.LEFT_ALIGNMENT);
		availForm.add(gap(10));
		availGo = pill("I'm available", PINK, false);
		availGo.setAlignmentX(Component.LEFT_ALIGNMENT);
		availGo.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		availGo.addActionListener(e ->
		{
			if (availActive)
			{
				send("avail_stop", null);
				availOpen = false;
				showAvailability();
			}
			else
			{
				setAvailable();
			}
		});
		availForm.add(availGo);
		avail.add(availForm);
		avail.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE)); // let the card be as tall as its contents
		stack(availOn);
		availOn.setOpaque(false);
		availOn.setAlignmentX(Component.LEFT_ALIGNMENT);
		availSummary.setForeground(TEXT);
		availSummary.setFont(F(Font.PLAIN, 13f));
		availSummary.setAlignmentX(Component.LEFT_ALIGNMENT);
		availSummary.setBorder(BorderFactory.createEmptyBorder(6, 1, 6, 0));
		availOn.add(availSummary);
		JButton availStop = pill("Stop", MUTED, true);
		availStop.setAlignmentX(Component.LEFT_ALIGNMENT);
		availStop.addActionListener(e -> send("avail_stop", null));
		availOn.add(availStop);
		avail.add(availOn);
		showAvailability(); // starts folded
		showCreate();
		body.add(gap(8));
		body.add(avail);


		// the open lobbies
		openCount.setForeground(TEXT);
		openCount.setFont(F(Font.BOLD, 14f));
		openCount.setAlignmentX(Component.LEFT_ALIGNMENT);
		openCount.setBorder(BorderFactory.createEmptyBorder(12, 2, 6, 0));
		JPanel openRow = new JPanel(new BorderLayout());
		openRow.setBackground(BG);
		openRow.setAlignmentX(Component.LEFT_ALIGNMENT);
		openRow.add(openCount, BorderLayout.CENTER);
		JPanel refreshWrap = new JPanel(new BorderLayout());
		refreshWrap.setBackground(BG);
		refreshWrap.setBorder(BorderFactory.createEmptyBorder(8, 0, 2, 2));
		refreshWrap.add(refreshIcon(), BorderLayout.CENTER);
		openRow.add(refreshWrap, BorderLayout.EAST);
		openRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
		body.add(openRow);
		stack(list);
		list.setBackground(BG);
		list.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(list);
		stack(playersBox);
		playersBox.setBackground(BG);
		playersBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		body.add(playersBox);

		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setBackground(BG);
		wrap.add(body, BorderLayout.NORTH);

		// two tabs: TeamFinder and Events
		buildEvents();
		JPanel eventsWrap = new JPanel(new BorderLayout());
		eventsWrap.setBackground(BG);
		eventsWrap.add(eventsBody, BorderLayout.NORTH);
		cards.setBackground(BG);
		cards.setOpaque(true);
		cards.setBorder(BorderFactory.createEmptyBorder());
		eventsWrap.setBorder(BorderFactory.createEmptyBorder());
		cards.setFocusable(false);
		tabBar.setFocusable(false);
		setFocusable(false);
		cards.add(wrap, "teams");
		cards.add(eventsWrap, "events");
		tabBar.setBackground(BG);
		tabBar.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
		add(tabBar, BorderLayout.NORTH);
		add(cards, BorderLayout.CENTER);
		showTab("teams");

		modeRow.setVisible(false);
		invRow.setVisible(false);
		render(new JsonArray());
	}

	// ---- tabs and the Events tab ----

	private void showTab(String name)
	{
		tab = name;
		cardLayout.show(cards, name);
		tabBar.removeAll();
		JButton t1 = pill("TeamFinder", PINK, !"teams".equals(name));
		JButton t2 = pill("Events", PINK, !"events".equals(name));
		t1.addActionListener(e -> showTab("teams"));
		t2.addActionListener(e ->
		{
			showTab("events");
			pollEvents(0, null, false);
		});
		t1.setMargin(new Insets(4, 4, 4, 4)); // the default side margins cut "TeamFinder" short
		t2.setMargin(new Insets(4, 4, 4, 4));
		t1.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		t2.setMaximumSize(new Dimension(Integer.MAX_VALUE, 30));
		tabBar.add(t1);
		tabBar.add(t2);
		tabBar.revalidate();
		tabBar.repaint();
	}

	private JPanel header(String text)
	{
		JPanel head = new JPanel(new BorderLayout(8, 0));
		head.setBackground(BG);
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		BufferedImage img = ImageUtil.loadImageResource(PinkLszPlugin.class, "icon.png");
		JLabel logo = new JLabel(new ImageIcon(img));
		JLabel title = new JLabel(text);
		title.setForeground(TEXT);
		title.setFont(F(Font.BOLD, 17f));
		JPanel left = new JPanel(new BorderLayout(8, 0));
		left.setBackground(BG);
		left.add(logo, BorderLayout.WEST);
		left.add(title, BorderLayout.CENTER);
		head.add(left, BorderLayout.CENTER);
		JPanel links = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		links.setBackground(BG);
		links.add(linkButton("link_discord.png", "Join the Pink LSZ Discord", "https://discord.gg/pink-osrs"));
		links.add(linkButton("link_web.png", "Pink LSZ website", "https://pinkosrs.com"));
		links.add(linkButton("link_github.png", "Plugin on GitHub", "https://github.com/Businessmxn/Pink-LSZ"));
		head.add(links, BorderLayout.EAST);
		head.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		return head;
	}

	private void buildEvents()
	{
		stack(eventsBody);
		eventsBody.setBackground(BG);
		eventsBody.add(header("Events"));
		JLabel sub = new JLabel("<html><body style='width:165px'>Clan events coming up. For events that track attendance, just take part: the plugin adds up your time in the event automatically.</body></html>");
		sub.setForeground(MUTED);
		sub.setFont(F(Font.PLAIN, 12.5f));
		sub.setAlignmentX(Component.LEFT_ALIGNMENT);
		sub.setBorder(BorderFactory.createEmptyBorder(4, 2, 8, 0));
		eventsBody.add(sub);
		eventsNote.setForeground(MUTED);
		eventsNote.setFont(F(Font.PLAIN, 12.5f));
		eventsNote.setAlignmentX(Component.LEFT_ALIGNMENT);
		eventsNote.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 0));
		eventsBody.add(eventsNote);
		stack(eventsList);
		eventsList.setBackground(BG);
		eventsList.setAlignmentX(Component.LEFT_ALIGNMENT);
		eventsBody.add(eventsList);
	}

	/** Asks the site for the events list; with a check-in it also says where this player is and who is near. Safe from any thread. */
	public void pollEvents(int region, List<String> near, boolean ping)
	{
		JsonObject d = new JsonObject();
		if (ping)
		{
			d.addProperty("ping", true);
			d.addProperty("region", region);
			d.addProperty("raid", inRaid);
			d.addProperty("wild", inWild);
			JsonArray n = new JsonArray();
			if (near != null)
			{
				for (String x : near)
				{
					n.add(x);
				}
			}
			d.add("near", n);
		}
		dashboard.call("pl_events", d, reply -> SwingUtilities.invokeLater(() -> applyEvents(reply)));
	}

	/** True while an event that tracks attendance is on, so the plugin keeps checking in. */
	public boolean eventsChecking()
	{
		return eventsChecking;
	}

	private void applyEvents(JsonObject reply)
	{
		if (reply.has("error"))
		{
			eventsNote.setText(reply.get("error").getAsString());
			return;
		}
		eventsChecking = reply.has("checking") && reply.get("checking").getAsBoolean();
		eventsList.removeAll();
		JsonArray evs = reply.has("events") && reply.get("events").isJsonArray() ? reply.get("events").getAsJsonArray() : new JsonArray();
		eventsNote.setText(evs.size() == 0 ? "Nothing is on the calendar for the next 31 days." : " ");
		java.time.format.DateTimeFormatter day = java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", java.util.Locale.UK);
		java.time.format.DateTimeFormatter hm = java.time.format.DateTimeFormatter.ofPattern("HH:mm", java.util.Locale.UK);
		JPanel[] groups = new JPanel[3];
		int[] counts = new int[3];
		for (int g = 0; g < 3; g++)
		{
			groups[g] = new JPanel();
			stack(groups[g]);
			groups[g].setBackground(BG);
			groups[g].setAlignmentX(Component.LEFT_ALIGNMENT);
		}
		java.time.LocalDate today = java.time.LocalDate.now();
		for (JsonElement el : evs)
		{
			JsonObject e = el.getAsJsonObject();
			boolean live = e.has("live") && e.get("live").getAsBoolean();
			boolean win = e.has("window") ? e.get("window").getAsBoolean() : live;
			boolean track = e.has("track") && e.get("track").getAsBoolean();
			long start = e.get("start").getAsLong();
			long end = e.get("end").getAsLong();
			if (!live && end < System.currentTimeMillis() / 1000)
			{
				continue; // already finished, nothing to show
			}
			Round card = new Round(CARD, live ? PINK : EDGE, 14);
			stack(card);
			card.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
			card.setAlignmentX(Component.LEFT_ALIGNMENT);
			if (live)
			{
				JLabel ln = new JLabel("<html><font color='#4cd964'>\u25CF</font>&nbsp; <font color='#ef3d9a'><b>LIVE NOW</b></font></html>");
				ln.setFont(F(Font.PLAIN, 11f));
				ln.setAlignmentX(Component.LEFT_ALIGNMENT);
				card.add(ln);
				card.add(gap(2));
			}
			JLabel t = new JLabel("<html><body style='width:150px'>" + esc(e.get("title").getAsString()) + "</body></html>");
			t.setForeground(TEXT);
			t.setFont(F(Font.BOLD, 13.5f));
			t.setAlignmentX(Component.LEFT_ALIGNMENT);
			card.add(t);
			if (e.has("focus") && !e.get("focus").isJsonNull())
			{
				JLabel fl = new JLabel("<html><body style='width:150px'>" + esc(e.get("focus").getAsString()) + "</body></html>");
				fl.setForeground(PINK);
				fl.setFont(F(Font.BOLD, 12.5f));
				fl.setAlignmentX(Component.LEFT_ALIGNMENT);
				card.add(fl);
			}
			java.time.ZonedDateTime s0 = java.time.Instant.ofEpochSecond(start).atZone(java.time.ZoneId.systemDefault());
			java.time.ZonedDateTime e0 = java.time.Instant.ofEpochSecond(end).atZone(java.time.ZoneId.systemDefault());
			boolean sameDay = s0.toLocalDate().equals(e0.toLocalDate());
			String whenText = s0.format(day) + " – " + e0.format(sameDay ? hm : day);
			if (live)
			{
				long left = Math.max(0, end - System.currentTimeMillis() / 1000);
				String lt = left >= 86400 ? (left / 86400) + "d " + ((left % 86400) / 3600) + "h " + ((left % 3600) / 60) + "m" : left >= 3600 ? (left / 3600) + "h " + ((left % 3600) / 60) + "m" : Math.max(1, left / 60) + "m";
				int wno = e.has("world") && !e.get("world").isJsonNull() ? e.get("world").getAsInt() : 0;
				whenText = (wno > 0 ? "World " + wno + " \u00B7 " : "") + "ends in " + lt;
			}
			JLabel when = new JLabel("<html><body style='width:150px'>" + whenText + "</body></html>");
			when.setForeground(MUTED);
			when.setFont(F(Font.PLAIN, 12f));
			when.setAlignmentX(Component.LEFT_ALIGNMENT);
			JsonObject bingo = e.has("bingo") && e.get("bingo").isJsonObject() ? e.getAsJsonObject("bingo") : null;
			if (bingo != null)
			{
				boolean onTeam = bingo.has("team") && !bingo.get("team").isJsonNull();
				String hex = bingo.has("color") && !bingo.get("color").isJsonNull() ? bingo.get("color").getAsString() : "#ef3d9a";
				JLabel tm = new JLabel("<html><body style='width:150px'>" + (onTeam ? "Your team: <font color='" + esc(hex) + "'><b>" + esc(bingo.get("team").getAsString()) + "</b></font>" : "You aren't on a team in this event.") + "</body></html>");
				tm.setForeground(MUTED);
				tm.setFont(F(Font.PLAIN, 12f));
				tm.setAlignmentX(Component.LEFT_ALIGNMENT);
				card.add(tm);
			}
			card.add(when);
			if (bingo != null && bingo.has("team") && !bingo.get("team").isJsonNull())
			{
				card.add(gap(6));
				JButton view = pill("View Board", PINK, false);
				view.setAlignmentX(Component.LEFT_ALIGNMENT);
				int bid = bingo.get("id").getAsInt();
				String btitle = e.get("title").getAsString();
				view.addActionListener(ev -> openBingoBoard(bid, btitle));
				card.add(view);
			}
			if (e.has("hosted") && !e.get("hosted").isJsonNull())
			{
				JLabel hl = new JLabel("Host: " + e.get("hosted").getAsString());
				hl.setForeground(MUTED);
				hl.setFont(F(Font.PLAIN, 12f));
				hl.setAlignmentX(Component.LEFT_ALIGNMENT);
				card.add(hl);
			}
			JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 4));
			chips.setOpaque(false);
			chips.setAlignmentX(Component.LEFT_ALIGNMENT);
			if (track)
			{
				JsonObject me = e.has("me") && e.get("me").isJsonObject() ? e.get("me").getAsJsonObject() : null;
				String st = me != null ? me.get("status").getAsString() : "";
				if (live && ("confirmed".equals(st) || "review".equals(st)))
				{
					// nothing to show: we don't tell people they are done while the event is still on, so they stay for all of it
				}
				else if ("confirmed".equals(st))
				{
					chips.add(chip("Attendance confirmed", new Color(0x5FD38D)));
				}
				else if ("review".equals(st))
				{
					chips.add(chip("Waiting for staff", GOLD));
				}
				else if ("lobby".equals(st))
				{
					chips.add(chip("Only seen at the lobby", MUTED));
				}
				else if ("rejected".equals(st) || "short".equals(st))
				{
					chips.add(chip("short".equals(st) ? "Too short" : "Not counted", DANGER));
				}
				else if (live)
				{
					// the progress bar below says how it is going
				}
			}
			String teach = e.has("teach") && !e.get("teach").isJsonNull() ? e.get("teach").getAsString() : "";
			if ("ok".equals(teach))
			{
				chips.add(chip("Teacher", new Color(0x5FD38D)));
			}
			else if ("pending".equals(teach))
			{
				chips.add(chip("Teacher: waiting for staff", GOLD));
			}
			else if ("no".equals(teach))
			{
				chips.add(chip("Teacher: not confirmed", MUTED));
			}
			if (chips.getComponentCount() > 0)
			{
				card.add(chips);
			}
			if (live)
			{
				// the bar is the event itself, from its start to its end
				long nowSec = System.currentTimeMillis() / 1000;
				double done = end > start ? Math.max(0.0, Math.min(1.0, (nowSec - start) / (double) (end - start))) : 0.0;
				card.add(gap(4));
				card.add(progressBar(done));
				card.add(gap(8));
			}
			if (e.has("gear") && e.get("gear").isJsonArray() && e.get("gear").getAsJsonArray().size() > 0)
			{
				JsonArray ga = e.get("gear").getAsJsonArray();
				JsonArray ids = new JsonArray();
				for (JsonElement ge : ga)
				{
					ids.add(ge.getAsJsonObject().get("id").getAsInt());
				}
				String gl = ga.size() == 1 ? "Gear: " + ga.get(0).getAsJsonObject().get("name").getAsString() : "Gear loadouts (" + ga.size() + ")";
				JButton gb = pill(gl, PINK, true);
				gb.setAlignmentX(Component.LEFT_ALIGNMENT);
				gb.addActionListener(ev -> openGear(ids));
				card.add(gb);
				card.add(gap(4));
			}
			boolean onDay = !s0.toLocalDate().isAfter(today); // hosting, teaching and learning only open on the day of the event
			if (onDay && e.has("canHost") && e.get("canHost").getAsBoolean() && e.has("base") && System.currentTimeMillis() / 1000 < end + 900)
			{
				String hbase = e.get("base").getAsString();
				JCheckBox hb = new JCheckBox("I'm hosting this event", e.has("hostMe") && e.get("hostMe").getAsBoolean());
				hb.setOpaque(false);
				hb.setForeground(TEXT);
				hb.setFont(F(Font.PLAIN, 12.5f));
				hb.setFocusPainted(false);
				hb.setIcon(boxIcon(false));
				hb.setSelectedIcon(boxIcon(true));
				hb.setIconTextGap(8);
				hb.setAlignmentX(Component.LEFT_ALIGNMENT);
				hb.setToolTipText("Staff only: shows you as the host to members");
				hb.addActionListener(ev ->
				{
					JsonObject hd = new JsonObject();
					hd.addProperty("base", hbase);
					hd.addProperty("start", start);
					hd.addProperty("on", hb.isSelected());
					dashboard.call("pl_host", hd, r -> SwingUtilities.invokeLater(() -> pollEvents(0, null, false)));
				});
				card.add(hb);
			}
			boolean teachable = e.has("teachable") && e.get("teachable").getAsBoolean();
			if (onDay && track && teachable && e.has("base") && !"no".equals(teach) && !"ok".equals(teach) && System.currentTimeMillis() / 1000 < end + 900)
			{
				String base = e.get("base").getAsString();
				JCheckBox cb = new JCheckBox("I can teach at this event", "pending".equals(teach));
				cb.setOpaque(false);
				cb.setForeground(TEXT);
				cb.setFont(F(Font.PLAIN, 12.5f));
				cb.setFocusPainted(false);
				cb.setIcon(boxIcon(false));
				cb.setSelectedIcon(boxIcon(true));
				cb.setIconTextGap(8);
				cb.setAlignmentX(Component.LEFT_ALIGNMENT);
				cb.setToolTipText("Staff confirm teachers before they count");
				cb.addActionListener(ev ->
				{
					JsonObject td = new JsonObject();
					td.addProperty("base", base);
					td.addProperty("start", start);
					td.addProperty("on", cb.isSelected());
					dashboard.call("pl_teach", td, r -> SwingUtilities.invokeLater(() -> pollEvents(0, null, false)));
				});
				card.add(cb);
			}
			if (onDay && track && teachable && e.has("base") && teach.isEmpty() && System.currentTimeMillis() / 1000 < end + 900)
			{
				String lbase = e.get("base").getAsString();
				JCheckBox lb = new JCheckBox("I'm learning this content", e.has("learner") && e.get("learner").getAsBoolean());
				lb.setOpaque(false);
				lb.setForeground(TEXT);
				lb.setFont(F(Font.PLAIN, 12.5f));
				lb.setFocusPainted(false);
				lb.setIcon(boxIcon(false));
				lb.setSelectedIcon(boxIcon(true));
				lb.setIconTextGap(8);
				lb.setAlignmentX(Component.LEFT_ALIGNMENT);
				lb.setToolTipText("Tick if you are still learning this, so the teacher is credited for teaching you");
				lb.addActionListener(ev ->
				{
					JsonObject ld = new JsonObject();
					ld.addProperty("base", lbase);
					ld.addProperty("start", start);
					ld.addProperty("learner", lb.isSelected());
					dashboard.call("pl_teach", ld, r -> SwingUtilities.invokeLater(() -> pollEvents(0, null, false)));
				});
				card.add(lb);
			}
			if (track && live && e.has("me") && e.get("me").isJsonObject())
			{
				JsonObject me = e.get("me").getAsJsonObject();
				String st = me.get("status").getAsString();
				String line = null;
				if ("checking".equals(st) || "review".equals(st))
				{
					int mins = me.get("mins").getAsInt();
					int need = me.get("need").getAsInt();
					JsonObject c = me.has("checks") && me.get("checks").isJsonObject() ? me.get("checks").getAsJsonObject() : new JsonObject();
					int worldNo = e.has("world") && !e.get("world").isJsonNull() ? e.get("world").getAsInt() : 0;
					line = missing(c, "world", worldNo > 0 ? "Join world " + worldNo + "." : "Join the event's world.");
					if (line == null)
					{
						line = missing(c, "raid", "Go into the raid with your team.");
					}
					if (line == null)
					{
						line = missing(c, "wild", "Go into the Wilderness.");
					}
					if (line == null)
					{
						line = missing(c, "near", "Stay close to other clan members.");
					}
					if (line == null)
					{
						line = missing(c, "area", "Go to the event area.");
					}
					if (line == null)
					{
						line = missing(c, "clan", "Be in the activity with other clan members.");
					}
					if (line == null)
					{
						line = missing(c, "kills", "Complete a run with your team.");
					}
				}
				if (line != null)
				{
					JLabel hint = new JLabel("<html><body style='width:150px'>" + esc(line) + "</body></html>");
					hint.setForeground(MUTED);
					hint.setFont(F(Font.PLAIN, 12f));
					hint.setAlignmentX(Component.LEFT_ALIGNMENT);
					card.add(hint);
				}
			}
			card.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
			int grp = live ? 0 : 1;
			groups[grp].add(card);
			groups[grp].add(gap(8));
			counts[grp]++;
		}
		String[] names = {"Live events", "Upcoming events", ""};
		for (int g = 0; g < 3; g++)
		{
			if (counts[g] > 0)
			{
				addEventSection(names[g], g, groups[g], counts[g]);
			}
		}
		if (reply.has("history") && reply.get("history").isJsonObject())
		{
			addHistory(reply.getAsJsonObject("history"));
		}
		eventsList.revalidate();
		eventsList.repaint();
	}

	private static JComponent progressBar(double frac)
	{
		JComponent c = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				int w = getWidth();
				int h = getHeight();
				g2.setColor(new Color(0x3A3A3A));
				g2.fillRoundRect(0, 0, w, h, h, h);
				g2.setColor(PINK);
				g2.fillRoundRect(0, 0, Math.max(h, (int) (w * frac)), h, h, h);
				g2.dispose();
			}
		};
		c.setPreferredSize(new Dimension(10, 8));
		c.setMinimumSize(new Dimension(10, 8));
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 8));
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	private static JComponent historyDots(JsonArray dots)
	{
		JComponent c = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				g2.setColor(new Color(0x4CD964));
				g2.setStroke(new BasicStroke(1.6f));
				for (int i = 0; i < dots.size(); i++)
				{
					int x = 2 + i * 20;
					if (dots.get(i).getAsBoolean())
					{
						g2.fillOval(x, 3, 10, 10);
					}
					else
					{
						g2.drawOval(x + 1, 4, 8, 8);
					}
				}
				g2.dispose();
			}
		};
		c.setPreferredSize(new Dimension(10, 16));
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, 16));
		c.setAlignmentX(Component.LEFT_ALIGNMENT);
		return c;
	}

	private void addHistory(JsonObject h)
	{
		JsonArray dots = h.has("dots") && h.get("dots").isJsonArray() ? h.get("dots").getAsJsonArray() : new JsonArray();
		if (true || dots.size() == 0) // the "Your last N" history strip is no longer shown
		{
			return;
		}
		eventsList.add(gap(6));
		JLabel head = new JLabel("Your last " + dots.size());
		head.setForeground(MUTED);
		head.setFont(F(Font.PLAIN, 12.5f));
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		head.setBorder(BorderFactory.createEmptyBorder(0, 2, 4, 0));
		eventsList.add(head);
		eventsList.add(historyDots(dots));
		int streak = h.has("streak") ? h.get("streak").getAsInt() : 0;
		JLabel foot = new JLabel("<html>" + (streak > 0 ? streak + " in a row · " : "") + "<u>full history on site</u></html>");
		foot.setForeground(MUTED);
		foot.setFont(F(Font.PLAIN, 12f));
		foot.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		foot.setAlignmentX(Component.LEFT_ALIGNMENT);
		foot.setBorder(BorderFactory.createEmptyBorder(4, 2, 0, 0));
		foot.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent ev)
			{
				net.runelite.client.util.LinkBrowser.browse("https://pinkosrs.com/events");
			}
		});
		eventsList.add(foot);
	}

	private final boolean[] eventsOpen = {true, true, true};

	private static final String[][] GEAR_GRID = {{"", "head", ""}, {"cape", "neck", "ammo"}, {"weapon", "body", "shield"}, {"", "legs", ""}, {"hands", "feet", "ring"}};

	/** Opens the gear loadout linked to an event in its own small window. */
	private void openGear(JsonArray ids)
	{
		JsonObject d = new JsonObject();
		d.add("ids", ids);
		dashboard.call("pl_gear_setup", d, reply -> SwingUtilities.invokeLater(() ->
		{
			if (reply.has("error"))
			{
				eventsNote.setText(reply.get("error").getAsString());
				return;
			}
			showGear(reply.has("setups") ? reply.getAsJsonArray("setups") : new JsonArray());
		}));
	}

	private JComponent gearCell(JsonElement el)
	{
		JLabel c = new JLabel("", SwingConstants.CENTER);
		c.setOpaque(true);
		c.setBackground(CARD);
		c.setBorder(BorderFactory.createLineBorder(EDGE));
		c.setPreferredSize(new Dimension(40, 36));
		if (el != null && el.isJsonObject())
		{
			JsonObject it = el.getAsJsonObject();
			String name = it.has("n") ? it.get("n").getAsString() : "";
			int iid = it.has("id") && !it.get("id").isJsonNull() ? it.get("id").getAsInt() : 0;
			int q = it.has("q") && !it.get("q").isJsonNull() ? it.get("q").getAsInt() : 1;
			c.setToolTipText(name + (q > 1 ? " x" + q : ""));
			if (iid > 0)
			{
				itemManager.getImage(iid, Math.max(1, q), q > 1).addTo(c);
			}
			else
			{
				c.setFont(F(Font.PLAIN, 9f));
				c.setForeground(TEXT);
				c.setText("<html><center>" + esc(name.length() > 12 ? name.substring(0, 11) + "…" : name) + "</center></html>");
			}
		}
		return c;
	}

	private void showGear(JsonArray setups)
	{
		if (setups.size() == 0)
		{
			return;
		}
		java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
		javax.swing.JDialog dlg = new javax.swing.JDialog(owner, "Gear loadouts");
		dlg.setModalityType(java.awt.Dialog.ModalityType.MODELESS);
		int tall = 0;
		if (setups.size() == 1)
		{
			JPanel one = gearPanel(setups.get(0).getAsJsonObject());
			tall = one.getPreferredSize().height;
			dlg.setContentPane(new JScrollPane(one, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
		}
		else
		{
			javax.swing.JTabbedPane tabs = new javax.swing.JTabbedPane();
			tabs.setBackground(BG);
			for (JsonElement el : setups)
			{
				JsonObject g = el.getAsJsonObject();
				JPanel gp = gearPanel(g);
				tall = Math.max(tall, gp.getPreferredSize().height);
				String nm = g.get("name").getAsString();
				tabs.addTab(nm.length() > 14 ? nm.substring(0, 13) + "…" : nm, new JScrollPane(gp, javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED, javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER));
				tabs.setToolTipTextAt(tabs.getTabCount() - 1, nm);
			}
			dlg.setContentPane(tabs);
			tall += 40;
		}
		dlg.setSize(440, Math.min(640, tall + 75));
		dlg.setLocationRelativeTo(owner);
		dlg.setAlwaysOnTop(true);
		dlg.setVisible(true);
	}

	private JLabel gearCaption(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(MUTED);
		l.setFont(F(Font.BOLD, 12f));
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		l.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		return l;
	}

	private JPanel gearGrid(int rows, int cols, JsonArray items)
	{
		JPanel grid = new JPanel(new java.awt.GridLayout(rows, cols, 4, 4));
		grid.setOpaque(false);
		for (int i = 0; i < rows * cols; i++)
		{
			grid.add(gearCell(items != null && i < items.size() ? items.get(i) : null));
		}
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.add(grid, BorderLayout.WEST);
		wrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		return wrap;
	}

	/** One loadout: equipment on the left, inventory and rune pouch on the right, notes underneath. */
	private JPanel gearPanel(JsonObject g)
	{
		JPanel root = new JPanel();
		stack(root);
		root.setBackground(BG);
		root.setBorder(BorderFactory.createEmptyBorder(10, 12, 12, 12));
		JLabel title = new JLabel("<html><body style='width:330px'><b>" + esc(g.get("name").getAsString()) + "</b>"
			+ (g.has("boss") && !g.get("boss").getAsString().isEmpty() ? "<br><font color='#9a9a9a'>" + esc(g.get("boss").getAsString()) + "</font>" : "") + "</body></html>");
		title.setForeground(TEXT);
		title.setFont(F(Font.PLAIN, 13f));
		title.setAlignmentX(Component.LEFT_ALIGNMENT);
		root.add(title);
		String notes = g.has("notes") && !g.get("notes").isJsonNull() ? g.get("notes").getAsString().trim() : "";
		if (!notes.isEmpty())
		{
			root.add(gap(6));
			JLabel nl = new JLabel("<html><body style='width:330px'>" + esc(notes).replace("\n", "<br>") + "</body></html>");
			nl.setForeground(MUTED);
			nl.setFont(F(Font.PLAIN, 12f));
			nl.setAlignmentX(Component.LEFT_ALIGNMENT);
			root.add(nl);
		}
		root.add(gap(10));

		// left: the worn equipment in the in-game layout
		JsonObject slots = g.has("slots") && g.get("slots").isJsonObject() ? g.get("slots").getAsJsonObject() : new JsonObject();
		JPanel eq = new JPanel(new java.awt.GridLayout(GEAR_GRID.length, 3, 4, 4));
		eq.setOpaque(false);
		for (String[] row : GEAR_GRID)
		{
			for (String slot : row)
			{
				if (slot.isEmpty())
				{
					eq.add(new JLabel());
				}
				else
				{
					eq.add(gearCell(slots.get(slot)));
				}
			}
		}
		JPanel left = new JPanel(new BorderLayout());
		left.setOpaque(false);
		JPanel leftTop = new JPanel();
		stack(leftTop);
		leftTop.setOpaque(false);
		leftTop.add(gearCaption("Equipment"));
		JPanel eqWrap = new JPanel(new BorderLayout());
		eqWrap.setOpaque(false);
		eqWrap.add(eq, BorderLayout.WEST);
		eqWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		leftTop.add(eqWrap);
		left.add(leftTop, BorderLayout.NORTH);

		// right: inventory, with the rune pouch lined up under it
		JPanel right = new JPanel();
		stack(right);
		right.setOpaque(false);
		right.add(gearCaption("Inventory"));
		right.add(gearGrid(7, 4, g.has("inv") && g.get("inv").isJsonArray() ? g.get("inv").getAsJsonArray() : new JsonArray()));
		JsonArray pouch = g.has("pouch") && g.get("pouch").isJsonArray() ? g.get("pouch").getAsJsonArray() : new JsonArray();
		boolean anyPouch = false;
		for (JsonElement x : pouch)
		{
			anyPouch |= x.isJsonObject();
		}
		if (anyPouch)
		{
			JPanel leftBottom = new JPanel();
			stack(leftBottom);
			leftBottom.setOpaque(false);
			leftBottom.add(gearCaption("Rune pouch"));
			leftBottom.add(gearGrid(1, 4, pouch));
			left.add(leftBottom, BorderLayout.SOUTH); // lines up with the bottom of the inventory
		}

		JPanel cols = new JPanel(new java.awt.GridBagLayout());
		cols.setOpaque(false);
		cols.setAlignmentX(Component.LEFT_ALIGNMENT);
		java.awt.GridBagConstraints gc = new java.awt.GridBagConstraints();
		gc.gridy = 0;
		gc.anchor = java.awt.GridBagConstraints.NORTHWEST;
		gc.gridx = 0;
		gc.fill = java.awt.GridBagConstraints.VERTICAL;
		gc.insets = new java.awt.Insets(0, 0, 0, 20);
		cols.add(left, gc);
		gc.gridx = 1;
		gc.insets = new java.awt.Insets(0, 0, 0, 0);
		cols.add(right, gc);
		JPanel colsWrap = new JPanel(new BorderLayout());
		colsWrap.setOpaque(false);
		colsWrap.add(cols, BorderLayout.WEST);
		colsWrap.setAlignmentX(Component.LEFT_ALIGNMENT);
		root.add(colsWrap);

		return root;
	}

	private void addEventSection(String name, int g, JPanel items, int count)
	{
		JLabel head = new JLabel();
		head.setForeground(TEXT);
		head.setFont(F(Font.BOLD, 13f));
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		head.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		head.setBorder(BorderFactory.createEmptyBorder(2, 2, 6, 0));
		Runnable paint = () ->
		{
			head.setText((eventsOpen[g] ? "\u25BC  " : "\u25B6  ") + name + " (" + count + ")");
			items.setVisible(eventsOpen[g]);
		};
		paint.run();
		head.addMouseListener(new java.awt.event.MouseAdapter()
		{
			@Override
			public void mousePressed(java.awt.event.MouseEvent ev)
			{
				eventsOpen[g] = !eventsOpen[g];
				paint.run();
				eventsList.revalidate();
				eventsList.repaint();
			}
		});
		eventsList.add(head);
		eventsList.add(items);
	}

	/** Opens the pop-out board for a bingo event, or brings the one that is already open to the front. */
	private void openBingoBoard(int id, String title)
	{
		BingoBoardWindow w = boardWindows.get(id);
		if (w != null && w.isDisplayable())
		{
			w.setState(java.awt.Frame.NORMAL);
			w.toFront();
			w.load();
			return;
		}
		w = new BingoBoardWindow(dashboard, itemManager, id, title);
		boardWindows.put(id, w);
		w.setVisible(true);
	}

	private static String missing(JsonObject checks, String key, String text)
	{
		return checks.has(key) && !checks.get(key).isJsonNull() && !checks.get(key).getAsBoolean() ? text : null;
	}

	private static String esc(String s)
	{
		return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
		return inLobby || availActive || isShowing();
	}

	private void create()
	{
		Act a = (Act) activity.getSelectedItem();
		if (a == null || !a.pickable())
		{
			setStatus("Pick an activity first.", true);
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
		d.addProperty("learner", learnerFriendly.isSelected());
		send("create", d);
		note.setText("");
	}

	/** null when the world can host a team: a normal members world (or the Duel Arena tournament world), otherwise why not */
	private String worldProblem(int w)
	{
		if (w == 558)
		{
			return null;
		}
		if (w < 301 || w > 600)
		{
			return "Type a members world between 301 and 600.";
		}
		try
		{
			net.runelite.http.api.worlds.WorldResult wr = worldService.getWorlds();
			net.runelite.http.api.worlds.World found = wr == null ? null : wr.findWorld(w);
			if (found == null)
			{
				return "World " + w + " isn't in the world list.";
			}
			boolean members = false;
			for (Object t : found.getTypes())
			{
				if ("MEMBERS".equals(t.toString()))
				{
					members = true;
				}
				else
				{
					return "World " + w + " is a special world. Pick a normal members world.";
				}
			}
			if (!members)
			{
				return "World " + w + " isn't a members world.";
			}
		}
		catch (RuntimeException e)
		{
			// the world list isn't loaded yet: the range check above is all there is
		}
		return null;
	}

	private void act(String op, int id)
	{
		JsonObject d = new JsonObject();
		d.addProperty("id", id);
		send(op, d);
	}

	/** set every game tick by the plugin: is the player inside a raid right now (read by the host's check-in) */
	private volatile boolean inRaid;
	private int hostedWorld;

	private volatile boolean inWild;

	public void setInWild(boolean v)
	{
		inWild = v;
	}

	public void setInRaid(boolean v)
	{
		inRaid = v;
	}

	private final java.util.Map<Integer, String> worldDrafts = new java.util.HashMap<>();
	private int worldFocus = -1;

	private void send(String op, JsonObject extra)
	{
		JsonObject d = extra == null ? new JsonObject() : extra;
		d.addProperty("op", op);
		if ("list".equals(op))
		{
			d.addProperty("raid", inRaid);
		}
		dashboard.call("pl_lobby", d, reply -> SwingUtilities.invokeLater(() -> apply(op, reply)));
	}

	private void apply(String op, JsonObject reply)
	{
		if (reply.has("error"))
		{
			setStatus(reply.get("error").getAsString(), true);
			return;
		}
		if (System.currentTimeMillis() >= errorHoldUntil)
		{
			setStatus(" ", false);
		}
		mod = reply.has("mod") && reply.get("mod").getAsBoolean();
		if (!actsLoaded && reply.has("activities") && reply.get("activities").isJsonArray())
		{
			fillActs(activity, reply.get("activities").getAsJsonArray());
			fillActs(availActivity, reply.get("activities").getAsJsonArray());
			actsLoaded = true;
			activityChanged();
			fillAvailSizes((Act) availActivity.getSelectedItem());
		}
		goneHow = reply.has("gone") && reply.get("gone").isJsonObject() && reply.get("gone").getAsJsonObject().has("how") ? reply.get("gone").getAsJsonObject().get("how").getAsString() : "";
		JsonArray lobbies = reply.has("lobbies") && reply.get("lobbies").isJsonArray() ? reply.get("lobbies").getAsJsonArray() : new JsonArray();
		lastLobbies = lobbies;
		String me = dashboard.playerName();
		if (me != null && !me.isEmpty())
		{
			setHint(hub, me); // the hint suggests your own name as the party passphrase
		}
		announce(op, lobbies);
		render(lobbies);
		renderAvailability(reply);
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
			int worldNow = mineTeam.has("world") && !mineTeam.get("world").isJsonNull() ? mineTeam.get("world").getAsInt() : 0;
			if (hostedWorld != 0 && worldNow != 0 && worldNow != hostedWorld)
			{
				dashboard.note((hosting ? "Team moved to world " : "The host moved the team to world ") + worldNow + ".");
			}
			hostedWorld = worldNow;
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
		hostedWorld = mineTeam.has("world") && !mineTeam.get("world").isJsonNull() ? mineTeam.get("world").getAsInt() : 0;
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

	private volatile long errorHoldUntil; // an error stays on screen for a few seconds, whatever the next refresh brings

	private void setStatus(String text, boolean bad)
	{
		if (bad)
		{
			errorHoldUntil = System.currentTimeMillis() + 8000;
		}
		status.setText("<html><body style='width:165px'>" + escape(text) + "</body></html>");
		status.setForeground(bad ? new Color(0xFF7B7B) : MUTED);
	}

	private void activityChanged()
	{
		Act a = (Act) activity.getSelectedItem();
		mode.removeAllItems();
		size.removeAllItems();
		if (a == null || !a.pickable())
		{
			modeRow.setVisible(false);
			invRow.setVisible(false);
			ffaRow.setVisible(true);
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

	// ---- available for a team ----

	/** The activity's modes (Normal / Challenge Mode, Normal / Hard Mode), like the create form. */
	private void fillAvailModes(Act a)
	{
		availMode.removeAllItems();
		boolean any = a != null && !a.modes.isEmpty();
		if (any)
		{
			for (String m : a.modes)
			{
				availMode.addItem(m);
			}
		}
		availModeRow.setVisible(any);
		availCard.revalidate();
		availCard.repaint();
	}

	/** Same sizes as the create form for the chosen activity, plus "Any". */
	private void fillAvailSizes(Act a)
	{
		availSize.removeAllItems();
		availSize.addItem("Any");
		if (a != null && a.pickable())
		{
			for (int i = a.min; i <= a.max; i++)
			{
				availSize.addItem(Integer.toString(i));
			}
			if (a.mass)
			{
				availSize.addItem("Mass");
			}
		}
		availSize.setSelectedIndex(0);
	}

	private void showCreate()
	{
		createTitle.setText(createOpen ? "Create a team \u25BE" : "Create a team \u25B8");
		createBody.setVisible(createOpen);
		revalidate();
		repaint();
	}

	/** Called when the player logs out: the create form is open again for the next login. */
	public void openCreateForm()
	{
		SwingUtilities.invokeLater(() ->
		{
			createOpen = true;
			showCreate();
		});
	}

	/** The card's folded / open state: a title you click, the form under it, or the "you are available" summary. */
	private void showAvailability()
	{
		availTitle.setText(availOpen ? "Available for a team \u25BE" : "Available for a team \u25B8");
		availForm.setVisible(availOpen);
		if (availGo != null)
		{
			availGo.setText(availActive ? "Not available" : "I'm available");
		}
		availOn.setVisible(false); // the card never turns into a summary: your listing sits in Players available, with its own stop button
		availCard.revalidate();
		availCard.repaint();
	}

	private void setAvailable()
	{
		Act a = (Act) availActivity.getSelectedItem();
		if (a == null || !a.pickable())
		{
			setStatus("Pick an activity first.", true);
			return;
		}
		JsonObject d = new JsonObject();
		d.addProperty("activity", a.name);
		String sz = (String) availSize.getSelectedItem();
		d.addProperty("size", "Mass".equals(sz) ? -1 : sz == null || !sz.matches("\\d+") ? 0 : Integer.parseInt(sz));
		d.addProperty("ffa", a.ffa && availFfa.isSelected());
		d.addProperty("learner", availLearner.isSelected());
		if (!a.modes.isEmpty() && availMode.getSelectedItem() != null)
		{
			d.addProperty("mode", (String) availMode.getSelectedItem());
		}
		send("avail", d);
		availOpen = false; // fold the card as soon as you post
		createOpen = false; // and the create form too
		showCreate();
		showAvailability();
	}

	private static String availWords(JsonObject v)
	{
		StringBuilder t = new StringBuilder();
		if (v.has("ffa") && !v.get("ffa").isJsonNull())
		{
			t.append(v.get("ffa").getAsBoolean() ? "FFA" : "Split").append(" · ");
		}
		int n = v.get("size").getAsInt();
		t.append(n < 0 ? "Mass" : n == 0 ? "Any size" : n + " players");
		if (v.has("learner") && v.get("learner").getAsBoolean())
		{
			t.append(" · Learner");
		}
		return t.toString();
	}

	/** the world's region as the world list shows it, e.g. " (UK)": the game chat and notifications cannot draw flag characters, so it is the region code */
	private String worldFlag(int world)
	{
		try
		{
			net.runelite.http.api.worlds.WorldResult wr = worldService.getWorlds();
			net.runelite.http.api.worlds.World w = wr == null ? null : wr.findWorld(world);
			if (w == null)
			{
				return "";
			}
			// the world list region (an enum such as UNITED_KINGDOM)
			String t = String.valueOf(w.getRegion()).toUpperCase();
			if (t.equals("0") || t.contains("UNITED_STATES") || t.equals("US") || t.contains("USA"))
			{
				return " (US)";
			}
			if (t.equals("1") || t.contains("UNITED_KINGDOM") || t.equals("UK") || t.equals("GB"))
			{
				return " (UK)";
			}
			if (t.equals("3") || t.contains("AUSTRALIA") || t.equals("AU"))
			{
				return " (AU)";
			}
			if (t.equals("7") || t.contains("GERMANY") || t.equals("DE"))
			{
				return " (DE)";
			}
			return "";
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	/** a tiny flag for a world's region, or null when the region is not known */
	private ImageIcon flagIcon(int world)
	{
		String code = worldFlag(world).replaceAll("[^A-Z]", "").toLowerCase();
		if (code.isEmpty())
		{
			return null;
		}
		BufferedImage img = ImageUtil.loadImageResource(PinkLszPlugin.class, "flag_" + code + ".png");
		return img == null ? null : new ImageIcon(img.getScaledInstance(15, 10, java.awt.Image.SCALE_SMOOTH));
	}

	private void renderAvailability(JsonObject reply)
	{
		JsonObject mineAv = reply.has("myAvail") && reply.get("myAvail").isJsonObject() ? reply.getAsJsonObject("myAvail") : null;
		boolean wasActive = availActive;
		availActive = mineAv != null;
		if (availActive && !wasActive)
		{
			availOpen = false; // fold the form once you are listed
			createOpen = false;
			showCreate();
		}
		showAvailability();
		if (mineAv != null)
		{
			availSummary.setText("<html><body style='width:150px'><b>" + escape(mineAv.get("activity").getAsString()) + "</b><br><font color='#9a9a9a'>" + escape(availWords(mineAv)) + "</font><br><font color='#9a9a9a'>Hosts can invite you. Off after 2 hours or when you log out.</font></body></html>");
		}

		// invites: one card each, and a line in the game chat the first time one arrives
		invitesBox.removeAll();
		JsonArray invites = reply.has("invites") && reply.get("invites").isJsonArray() ? reply.getAsJsonArray("invites") : new JsonArray();
		for (JsonElement e : invites)
		{
			JsonObject iv = e.getAsJsonObject();
			int iid = iv.get("id").getAsInt();
			if (invitesSeen.add(iid))
			{
				dashboard.note(iv.get("from").getAsString() + " invited you to a " + iv.get("short").getAsString() + " team. Open the Pink LSZ panel to join.");
				if (pinkConfig.notifyInvites())
				{
					int hw = iv.has("world") && !iv.get("world").isJsonNull() ? iv.get("world").getAsInt() : 0;
					notifier.notify(iv.get("from").getAsString() + " invited you to a " + iv.get("short").getAsString() + " team" + (hw > 0 ? " on world " + hw + worldFlag(hw) : "") + ".");
				}
			}
			Round card = new Round(new Color(0x2A1224), PINK, 14);
			card.setLayout(new BorderLayout(6, 0));
			card.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 8));
			card.setAlignmentX(Component.LEFT_ALIGNMENT);
			card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));
			String sub = iv.get("short").getAsString() + " · " + iv.get("team").getAsString() + (iv.get("world").isJsonNull() ? "" : " · W" + iv.get("world").getAsInt());
			JLabel head = new JLabel("<html><b>" + escape(iv.get("from").getAsString()) + "</b> invited you</html>");
			head.setForeground(TEXT);
			head.setFont(F(Font.PLAIN, 12.5f));
			String subA = iv.get("short").getAsString() + " · " + iv.get("team").getAsString();
			int fw = iv.has("world") && !iv.get("world").isJsonNull() ? iv.get("world").getAsInt() : 0;
			JLabel txt = new JLabel("<html><font color='#9a9a9a'>" + escape(subA) + (fw > 0 ? " ·" : "") + "</font></html>");
			txt.setForeground(TEXT);
			txt.setFont(F(Font.PLAIN, 12.5f));
			JPanel subRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
			subRow.setOpaque(false);
			subRow.setBorder(BorderFactory.createEmptyBorder(0, -3, 0, 0));
			subRow.add(txt);
			if (fw > 0)
			{
				JLabel wl = new JLabel("<html><font color='#9a9a9a'>W" + fw + "</font></html>");
				wl.setFont(F(Font.PLAIN, 12.5f));
				ImageIcon fi = flagIcon(fw);
				if (fi != null)
				{
					wl.setIcon(fi);
					wl.setIconTextGap(4);
				}
				subRow.add(wl);
			}
			JPanel top = new JPanel();
			top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
			top.setOpaque(false);
			head.setAlignmentX(Component.LEFT_ALIGNMENT);
			subRow.setAlignmentX(Component.LEFT_ALIGNMENT);
			top.add(head);
			top.add(subRow);
			card.add(top, BorderLayout.NORTH);
			JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
			btns.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
			btns.setOpaque(false);
			JButton join = pill("Join", PINK, false);
			join.addActionListener(ev -> act("invite_accept", iid));
			JButton no = pill("✕", MUTED, true);
			no.addActionListener(ev -> act("invite_decline", iid));
			btns.add(join);
			btns.add(no);
			card.add(btns, BorderLayout.SOUTH);
			invitesBox.add(card);
			invitesBox.add(gap(8));
		}
		invitesBox.revalidate();
		invitesBox.repaint();

		// players available: everyone sees the list, hosts get an Invite button on rows that match their team
		String hostAct = "";
		String hostMode = "";
		boolean hostFull = false;
		for (JsonElement e : lastLobbies)
		{
			JsonObject l = e.getAsJsonObject();
			if (l.get("mine").getAsBoolean())
			{
				hostAct = l.get("activity").getAsString();
				hostMode = l.has("mode") && !l.get("mode").isJsonNull() ? l.get("mode").getAsString() : "";
				int cap = l.get("size").getAsInt();
				hostFull = cap > 0 && l.getAsJsonArray("members").size() >= cap;
			}
		}
		playersBox.removeAll();
		JsonArray av = reply.has("avail") && reply.get("avail").isJsonArray() ? reply.getAsJsonArray("avail") : new JsonArray();
		JsonObject ranks = reply.has("availRanks") && reply.get("availRanks").isJsonObject() ? reply.getAsJsonObject("availRanks") : new JsonObject();
		if (av.size() > 0)
		{
			JLabel hd = new JLabel("Players available (" + av.size() + ")");
			hd.setForeground(TEXT);
			hd.setFont(F(Font.BOLD, 14f));
			hd.setAlignmentX(Component.LEFT_ALIGNMENT);
			hd.setBorder(BorderFactory.createEmptyBorder(12, 2, 6, 0));
			playersBox.add(hd);
		}
		for (JsonElement e : av)
		{
			JsonObject v = e.getAsJsonObject();
			String n = v.get("rsn").getAsString();
			boolean me = v.get("mine").getAsBoolean();
			Round row = new Round(CARD, EDGE, 12);
			row.setLayout(new BorderLayout(6, 0));
			row.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
			JPanel who = new JPanel();
			who.setLayout(new javax.swing.BoxLayout(who, javax.swing.BoxLayout.Y_AXIS));
			who.setOpaque(false);
			JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0)); // rank icon and name on one line
			top.setOpaque(false);
			top.setAlignmentX(Component.LEFT_ALIGNMENT);
			JLabel rankSlot = new JLabel();
			rankSlot.setPreferredSize(new Dimension(18, 18));
			String role = ranks.has(n.toLowerCase()) && !ranks.get(n.toLowerCase()).isJsonNull() ? ranks.get(n.toLowerCase()).getAsString() : "";
			if (!role.isEmpty())
			{
				BufferedImage rimg = dashboard.roleIcon(role, this::refresh);
				if (rimg != null)
				{
					rankSlot.setIcon(new javax.swing.ImageIcon(rimg));
				}
			}
			top.add(rankSlot);
			JLabel nm = new JLabel(n);
			nm.setForeground(TEXT);
			nm.setFont(F(Font.PLAIN, 13f));
			top.add(nm);
			who.add(top);
			JLabel det = new JLabel(v.get("short").getAsString());
			det.setForeground(TEXT);
			det.setFont(F(Font.BOLD, 12.5f));
			det.setAlignmentX(Component.LEFT_ALIGNMENT);
			det.setBorder(BorderFactory.createEmptyBorder(0, 4, 2, 0));
			who.add(det);
			// the same coloured tags the open teams use: size, mode, learner and FFA/Split
			JPanel tags = new JPanel(new WrapLayout(FlowLayout.LEFT, 3, 2, 130)); // wraps onto a second line, and says how tall that is, instead of clipping
			tags.setOpaque(false);
			tags.setAlignmentX(Component.LEFT_ALIGNMENT);
			int sz = v.get("size").getAsInt();
			tags.add(chip(sz < 0 ? "Mass" : sz == 0 ? "Any size" : sz + "p", GOLD));
			String vm = v.has("mode") && !v.get("mode").isJsonNull() ? v.get("mode").getAsString() : "";
			if (!vm.isEmpty() && !"Normal".equals(vm) && !"Challenge Mode".equals(vm) && !"Hard Mode".equals(vm))
			{
				tags.add(chip(vm, new Color(0x9C8CFF)));
			}
			if (v.has("learner") && v.get("learner").getAsBoolean())
			{
				tags.add(chip("Learner", new Color(0x7BE0A0)));
			}
			if (v.has("ffa") && !v.get("ffa").isJsonNull())
			{
				tags.add(chip(v.get("ffa").getAsBoolean() ? "FFA" : "Split", new Color(0xFF7BAC)));
			}
			who.add(tags);
			row.add(who, BorderLayout.CENTER);
			if (me)
			{
				JButton x = pill("\u2715", MUTED, true);
				x.setToolTipText("Stop being available");
				x.addActionListener(ev -> send("avail_stop", null));
				JPanel east = new JPanel(new java.awt.GridBagLayout()); // centres the button vertically
				east.setOpaque(false);
				east.add(x);
				row.add(east, BorderLayout.EAST);
			}
			else if (!hostAct.isEmpty() && hostAct.equals(v.get("activity").getAsString()) && hostMode.equals(v.has("mode") && !v.get("mode").isJsonNull() ? v.get("mode").getAsString() : "") && !hostFull)
			{
				boolean invited = v.get("invited").getAsBoolean();
				JButton b = pill(invited ? "Invited" : "Invite", invited ? MUTED : PINK, invited);
				b.setEnabled(!invited);
				b.addActionListener(ev ->
				{
					JsonObject d = new JsonObject();
					d.addProperty("to", n);
					send("invite", d);
				});
				JPanel east = new JPanel(new java.awt.GridBagLayout()); // centres the button vertically
				east.setOpaque(false);
				east.add(b);
				row.add(east, BorderLayout.EAST);
			}
			playersBox.add(row);
			playersBox.add(gap(5));
		}
		playersBox.revalidate();
		playersBox.repaint();
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

		String rawMode = l.has("mode") && !l.get("mode").isJsonNull() ? l.get("mode").getAsString() : "";
		String modeSuffix = "Challenge Mode".equals(rawMode) ? " - CM" : "Hard Mode".equals(rawMode) ? " - HM" : "";
		JLabel head = new JLabel("<html><body style='width:150px'>" + escape(l.get("activity").getAsString() + modeSuffix) + "</body></html>");
		head.setForeground(TEXT);
		head.setFont(F(Font.BOLD, 14f));
		head.setAlignmentX(Component.LEFT_ALIGNMENT);
		c.add(head);
		c.add(gap(5));

		JPanel chips = new JPanel(new WrapLayout(FlowLayout.LEFT, 4, 2));
		chips.setOpaque(false);
		chips.setAlignmentX(Component.LEFT_ALIGNMENT);
		chips.setBorder(BorderFactory.createEmptyBorder(0, -4, 0, 0));
		JPanel chips3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		chips3.setOpaque(false);
		chips3.setAlignmentX(Component.LEFT_ALIGNMENT);
		chips3.setBorder(BorderFactory.createEmptyBorder(0, -4, 0, 0));
		JPanel chips2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		chips2.setOpaque(false);
		chips2.setAlignmentX(Component.LEFT_ALIGNMENT);
		chips2.setBorder(BorderFactory.createEmptyBorder(0, -4, 0, 0));
		chips.add(chip(cap == 0 ? "Mass · " + members.size() : members.size() + "/" + cap, GOLD));
		String modeText = l.get("mode").getAsString();
		if (!modeText.isEmpty() && !"Normal".equals(modeText) && !"Challenge Mode".equals(modeText) && !"Hard Mode".equals(modeText)) // the raid's mode is in the title instead
		{
			chips.add(chip(modeText, new Color(0x9C8CFF)));
		}
		if (!l.get("invocation").isJsonNull())
		{
			chips.add(chip("Inv " + l.get("invocation").getAsInt(), new Color(0x4AD1A0)));
		}
		if (l.has("inRaid") && l.get("inRaid").getAsBoolean())
		{
			chips.add(chip("In raid", new Color(0xF0A030)));
		}
		if (l.has("learner") && l.get("learner").getAsBoolean())
		{
			chips.add(chip("Learner-friendly", new Color(0x7BE0A0)));
		}
		if (l.has("ffa") && !l.get("ffa").isJsonNull())
		{
			chips.add(chip(l.get("ffa").getAsBoolean() ? "FFA" : "Split", new Color(0xFF7BAC)));
		}
		if (!l.get("world").isJsonNull())
		{
			int cw = l.get("world").getAsInt();
			JLabel wc = chip("W" + cw, new Color(0x5AB0FF));
			ImageIcon wi = flagIcon(cw);
			if (wi != null)
			{
				wc.setIcon(wi);
				wc.setIconTextGap(4);
			}
			chips.add(wc);
		}
		c.add(chips);
		if (chips2.getComponentCount() > 0)
		{
			c.add(gap(3));
			c.add(chips2);
		}
		if (chips3.getComponentCount() > 0)
		{
			c.add(gap(3));
			c.add(chips3);
		}
		c.add(gap(6));

		JsonObject ranks = l.has("ranks") && l.get("ranks").isJsonObject() ? l.getAsJsonObject("ranks") : new JsonObject();
		JPanel namesBox = new JPanel();
		namesBox.setLayout(new javax.swing.BoxLayout(namesBox, javax.swing.BoxLayout.Y_AXIS));
		namesBox.setOpaque(false);
		namesBox.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (JsonElement m : members)
		{
			String n = m.getAsString();
			boolean host = n.equalsIgnoreCase(l.get("host").getAsString());
			JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 0));
			row.setOpaque(false);
			row.setAlignmentX(Component.LEFT_ALIGNMENT);
			row.setBorder(BorderFactory.createEmptyBorder(1, -3, 1, 0));
			// fixed columns so every name lines up: a slot for the host crown, a slot for the rank icon, then the name
			JLabel mark = new JLabel(host ? "<html><font color='#ef3d9a'>&#9733;</font></html>" : "");
			mark.setForeground(TEXT);
			mark.setFont(F(Font.PLAIN, 13f));
			mark.setPreferredSize(new Dimension(14, 18));
			row.add(mark);
			JLabel rankSlot = new JLabel();
			rankSlot.setPreferredSize(new Dimension(18, 18));
			String role = ranks.has(n.toLowerCase()) && !ranks.get(n.toLowerCase()).isJsonNull() ? ranks.get(n.toLowerCase()).getAsString() : "";
			if (!role.isEmpty())
			{
				BufferedImage rimg = dashboard.roleIcon(role, this::refresh);
				if (rimg != null)
				{
					rankSlot.setIcon(new javax.swing.ImageIcon(rimg));
				}
			}
			row.add(rankSlot);
			JLabel nm = new JLabel("<html>" + escape(n) + (host ? " <font color='#9a9a9a'>(host)</font>" : "") + "</html>");
			nm.setForeground(TEXT);
			nm.setFont(F(Font.PLAIN, 13f));
			row.add(nm);
			namesBox.add(row);
		}
		c.add(namesBox);
		String noteText = l.get("note").getAsString();
		if (!noteText.isEmpty())
		{
			JLabel note = new JLabel("<html><body style='width:150px'><i><font color='#9a9a9a'>" + escape(noteText) + "</font></i></body></html>");
			note.setFont(F(Font.PLAIN, 13f));
			note.setAlignmentX(Component.LEFT_ALIGNMENT);
			c.add(note);
		}
		c.add(gap(8));

		JButton changeBtn = null;
		if (mine)
		{
			// the host can move the team to another world without closing it
			JPanel wr = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
			wr.setOpaque(false);
			wr.setAlignmentX(Component.LEFT_ALIGNMENT);
			wr.setBorder(BorderFactory.createEmptyBorder(0, -6, 0, 0));
			JLabel wl = new JLabel("World");
			wl.setForeground(MUTED);
			wl.setFont(F(Font.PLAIN, 13f));
			wr.add(wl);
			// the list is rebuilt on every refresh, so what is being typed is kept per team and put back
			String shown = l.has("world") && !l.get("world").isJsonNull() ? Integer.toString(l.get("world").getAsInt()) : "";
			JTextField wf = new JTextField(worldDrafts.containsKey(id) && (worldFocus == id || !worldDrafts.get(id).isEmpty()) ? worldDrafts.get(id) : shown, 4);
			wf.getDocument().addDocumentListener(new javax.swing.event.DocumentListener()
			{
				private void keep()
				{
					worldDrafts.put(id, wf.getText());
				}

				@Override
				public void insertUpdate(javax.swing.event.DocumentEvent e)
				{
					keep();
				}

				@Override
				public void removeUpdate(javax.swing.event.DocumentEvent e)
				{
					keep();
				}

				@Override
				public void changedUpdate(javax.swing.event.DocumentEvent e)
				{
					keep();
				}
			});
			if (worldFocus == id)
			{
				SwingUtilities.invokeLater(() ->
				{
					wf.requestFocusInWindow();
					wf.setCaretPosition(wf.getText().length());
				});
			}
			wf.addFocusListener(new java.awt.event.FocusAdapter()
			{
				@Override
				public void focusGained(java.awt.event.FocusEvent e)
				{
					worldFocus = id;
				}
			});
			wf.setBackground(FIELD);
			wf.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(EDGE), BorderFactory.createEmptyBorder(2, 4, 2, 4)));
			wf.setForeground(TEXT);
			wf.setCaretColor(TEXT);
			wr.add(wf);
			JButton wb = pill("Change", PINK, true);
			wf.addActionListener(ev -> wb.doClick());
			wb.addActionListener(ev ->
			{
				try
				{
					int nw = Integer.parseInt(wf.getText().trim());
					String bad = worldProblem(nw);
					if (bad != null)
					{
						setStatus(bad, true);
						return;
					}
					JsonObject wd = new JsonObject();
					wd.addProperty("id", id);
					wd.addProperty("world", nw);
					worldDrafts.remove(id);
					worldFocus = -1;
					send("world", wd);
				}
				catch (NumberFormatException ex)
				{
					setStatus("Type a world number, like 373.", true);
				}
			});
			changeBtn = wb;
			c.add(wr);
			c.add(gap(6));
		}
		JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		actions.setOpaque(false);
		actions.setAlignmentX(Component.LEFT_ALIGNMENT);
		actions.setBorder(BorderFactory.createEmptyBorder(0, -6, 0, 0));
		if (mine)
		{
			if (changeBtn != null)
			{
				actions.add(changeBtn); // Change sits under the World box, beside Close team
			}
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


	/** a FlowLayout that wraps onto new lines within a given width and reports the taller height, so no tag is ever clipped */
	private static class WrapLayout extends FlowLayout
	{
		private final int width;

		WrapLayout(int align, int hgap, int vgap)
		{
			this(align, hgap, vgap, 200); // the open-team card is about 200 px wide
		}

		WrapLayout(int align, int hgap, int vgap, int width)
		{
			super(align, hgap, vgap);
			this.width = width;
		}

		@Override
		public Dimension preferredLayoutSize(java.awt.Container target)
		{
			return size(target);
		}

		@Override
		public Dimension minimumLayoutSize(java.awt.Container target)
		{
			return size(target);
		}

		private Dimension size(java.awt.Container target)
		{
			synchronized (target.getTreeLock())
			{
				int w = 0;
				int rowW = 0;
				int rowH = 0;
				int h = 0;
				for (Component m : target.getComponents())
				{
					if (!m.isVisible())
					{
						continue;
					}
					Dimension d = m.getPreferredSize();
					if (rowW > 0 && rowW + d.width > width)
					{
						w = Math.max(w, rowW);
						h += rowH + getVgap();
						rowW = 0;
						rowH = 0;
					}
					rowW += d.width + getHgap();
					rowH = Math.max(rowH, d.height);
				}
				w = Math.max(w, rowW);
				h += rowH;
				return new Dimension(w + getHgap(), h + getVgap() * 2);
			}
		}
	}
}
