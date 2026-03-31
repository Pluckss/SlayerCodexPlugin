package com.slayercodex;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ButtonGroup;
import javax.swing.DefaultListModel;
import javax.swing.AbstractButton;
import javax.swing.JButton;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import net.runelite.api.ItemID;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;

public class SlayerCodexPanel extends PluginPanel
{
	private static final String WIKI_BASE_URL = "https://oldschool.runescape.wiki/w/";

	private static final Color BG_ROOT = new Color(18, 21, 27);
	private static final Color BG_CARD = new Color(25, 30, 38);
	private static final Color BG_CARD_ALT = new Color(31, 37, 47);
	private static final Color BORDER = new Color(54, 63, 77);
	private static final Color TEXT_MAIN = new Color(225, 232, 240);
	private static final Color TEXT_MUTED = new Color(147, 160, 176);
	private static final Color BLUE_ACCENT = new Color(72, 148, 255);
	private static final Color GREEN_ACCENT = new Color(76, 196, 110);
	private static final Color RED_ACCENT = new Color(220, 53, 69);
	private static final Color ORANGE_ACCENT = new Color(255, 160, 50);

	private final SlayerCodexDataStore dataStore;
	private final ItemManager itemManager;
	private final SlayerCodexRecommendationService recommendationService;
	private final SlayerCodexOwnershipTracker ownershipTracker;
	private final JLabel statusLabel = createMutedLabel("Search monsters and pick a setup");
	private final JLabel taskLabel = createMutedLabel("Current task: not detected yet");
	private final JLabel taskIconLabel = new JLabel();
	private final JButton taskActionButton = new JButton("Check Slayer Task");
	private final JButton browserToggleButton = new JButton("Browse Strategies");
	private final JButton browseAllButton = new JButton("Browse all monsters");
	private final JTextField searchField = new JTextField();
	private final DefaultListModel<SlayerCodexDataStore.MonsterSummary> listModel = new DefaultListModel<>();
	private final JList<SlayerCodexDataStore.MonsterSummary> monsterList = new JList<>(listModel);
	private final JLabel selectedMonsterLabel = createTitleLabel("Select a monster");
	private final JLabel detailsMetaLabel = createMutedLabel("Pick a monster from the list");
	private final JLabel recommendationStatusLabel = createMutedLabel("Your Best uses equipped and inve...");
	private final JPanel bankHintPanel = new JPanel(new BorderLayout(6, 0));
	private final JLabel bankHintLabel = new JLabel("⚠  Open your bank once for full gear picks");
	private final JButton wikiButton = new JButton("Wiki");
	private final JPanel styleButtonPanel = new JPanel();
	private final GearMatrixTableModel gearTableModel = new GearMatrixTableModel();
	private final JTable gearTable = new JTable(gearTableModel);
	private final JTextArea notesArea = new JTextArea();
	private final JPanel browserPanel = new JPanel(new BorderLayout(6, 6));

	private final List<JToggleButton> styleButtons = new ArrayList<>();
	private final ButtonGroup styleGroup = new ButtonGroup();
	private final JButton variantToggleButton = new JButton();
	private final JPanel variantTogglePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
	private String variantTargetKey;

	private List<SlayerCodexDataStore.MonsterSummary> allMonsters = Collections.emptyList();
	private Map<String, SlayerCodexDataStore.MonsterSummary> summaryByKey = Collections.emptyMap();
	private List<GearMatrixRow> currentMatrixRows = Collections.emptyList();
	private String currentTaskMonsterKey;
	private boolean browserExpanded;
	private SlayerCodexDataStore.MonsterDetails currentMonster;
	private SlayerCodexDataStore.CombatStyleDetails currentStyle;

	public SlayerCodexPanel(
		SlayerCodexDataStore dataStore,
		ItemManager itemManager,
		SlayerCodexRecommendationService recommendationService,
		SlayerCodexOwnershipTracker ownershipTracker)
	{
		this.dataStore = dataStore;
		this.itemManager = itemManager;
		this.recommendationService = recommendationService;
		this.ownershipTracker = ownershipTracker;

		setLayout(new BorderLayout(8, 8));
		setBackground(BG_ROOT);
		setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		add(buildHeader(), BorderLayout.NORTH);
		add(buildMain(), BorderLayout.CENTER);

		wireListeners();
		setInitialDisabledState();
		setIcon(taskIconLabel, ItemID.SLAYER_HELMET_I);
	}

	public void initializeFromStore()
	{
		allMonsters = dataStore.getMonsterSummaries();
		Map<String, SlayerCodexDataStore.MonsterSummary> byKey = new LinkedHashMap<>();
		for (SlayerCodexDataStore.MonsterSummary summary : allMonsters)
		{
			byKey.put(summary.getKey(), summary);
		}
		summaryByKey = byKey;
		filterMonsters();
	}

	public void setDataStatus(int monsterCount, String crawlDate)
	{
		statusLabel.setText("Search monsters and pick a setup");
		recommendationStatusLabel.setText(recommendationService.buildBestSetupStatus());
	}

	public void setErrorStatus(String message)
	{
		statusLabel.setText("Failed to load data: " + message);
		setInitialDisabledState();
	}

	public void setCurrentTask(String taskName, Integer remaining, boolean autoSelected)
	{
		if (taskName == null || taskName.trim().isEmpty())
		{
			taskLabel.setText("Current task: not detected yet");
			taskLabel.setForeground(TEXT_MUTED);
			taskActionButton.setVisible(true);
			taskActionButton.setEnabled(false);
			styleButton(taskActionButton, false, 10);
			return;
		}

		StringBuilder text = new StringBuilder("Current task: ").append(taskName);
		if (remaining != null)
		{
			text.append(" (").append(remaining).append(" left)");
		}
		taskLabel.setText(text.toString());
		taskLabel.setForeground(GREEN_ACCENT);

		if (autoSelected)
		{
			// Monster already shown — button not needed
			taskActionButton.setVisible(false);
		}
		else if (currentTaskMonsterKey != null)
		{
			// Task found but user hasn't navigated yet — highlight red
			taskActionButton.setEnabled(true);
			taskActionButton.setVisible(true);
			styleButtonRed(taskActionButton, 10);
		}
		else
		{
			// Task detected but no monster mapping found
			taskActionButton.setEnabled(false);
			taskActionButton.setVisible(true);
			styleButton(taskActionButton, false, 10);
		}
	}

	public void setCurrentTaskTarget(String monsterKey)
	{
		currentTaskMonsterKey = monsterKey;
		if (monsterKey != null)
		{
			taskActionButton.setEnabled(true);
			taskActionButton.setVisible(true);
			styleButtonRed(taskActionButton, 10);
		}
		else
		{
			taskActionButton.setEnabled(false);
			styleButton(taskActionButton, false, 10);
		}
	}

	public boolean selectMonsterByKey(String monsterKey)
	{
		if (monsterKey == null)
		{
			return false;
		}

		SlayerCodexDataStore.MonsterSummary summary = summaryByKey.get(monsterKey);
		if (summary == null)
		{
			return false;
		}

		if (!searchField.getText().isEmpty())
		{
			searchField.setText("");
		}

		monsterList.setSelectedValue(summary, true);
		int index = listModel.indexOf(summary);
		if (index >= 0)
		{
			monsterList.ensureIndexIsVisible(index);
		}
		return true;
	}

	public void refreshRecommendations()
	{
		recommendationStatusLabel.setText(recommendationService.buildBestSetupStatus());
		// Hide bank hint once bank has been seen this session
		if (ownershipTracker.isBankKnown())
		{
			bankHintPanel.setVisible(false);
		}
		else
		{
			bankHintPanel.setVisible(true);
		}
		if (currentStyle != null)
		{
			onCombatStyleChanged(currentStyle);
		}
	}

	private Component buildHeader()
	{
		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setOpaque(false);

		JPanel taskCard = createCardPanel();
		taskCard.setLayout(new BorderLayout(8, 0));
		taskCard.add(taskIconLabel, BorderLayout.WEST);
		taskCard.add(taskLabel, BorderLayout.CENTER);
		styleButton(taskActionButton, false, 10);
		taskActionButton.setPreferredSize(new Dimension(108, 30));
		taskActionButton.setToolTipText("Check your current Slayer task and open its recommended gear setup");
		taskCard.add(taskActionButton, BorderLayout.EAST);

		// Bank hint banner — red until bank is opened this session
		bankHintLabel.setForeground(ORANGE_ACCENT);
		bankHintLabel.setFont(new Font("SansSerif", Font.BOLD, 10));
		bankHintPanel.setBackground(new Color(60, 30, 10));
		bankHintPanel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(ORANGE_ACCENT),
			BorderFactory.createEmptyBorder(5, 8, 5, 8)
		));
		bankHintLabel.setToolTipText("Open your bank at least once so the plugin can compare your owned gear against recommended setups");
		bankHintPanel.add(bankHintLabel, BorderLayout.CENTER);

		header.add(taskCard);
		header.add(Box.createVerticalStrut(4));
		header.add(bankHintPanel);
		return header;
	}

	private Component buildMain()
	{
		searchField.setToolTipText("Search monster...");
		searchField.putClientProperty("JTextField.placeholderText", "Search monsters...");
		searchField.setForeground(TEXT_MAIN);
		searchField.setFont(new Font("SansSerif", Font.PLAIN, 13));
		searchField.setBackground(BG_CARD_ALT);
		searchField.setCaretColor(TEXT_MAIN);
		searchField.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BORDER),
			BorderFactory.createEmptyBorder(7, 10, 7, 10)
		));

		JScrollPane listScroll = new JScrollPane(monsterList);
		listScroll.setBorder(BorderFactory.createLineBorder(BORDER));
		listScroll.getViewport().setBackground(BG_CARD_ALT);
		monsterList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
		monsterList.setCellRenderer(new MonsterSummaryRenderer());
		monsterList.setFont(new Font("SansSerif", Font.PLAIN, 13));
		monsterList.setBackground(BG_CARD_ALT);

		browserPanel.setOpaque(false);
		browserPanel.add(searchField, BorderLayout.NORTH);
		browserPanel.add(listScroll, BorderLayout.CENTER);
		browserPanel.add(statusLabel, BorderLayout.SOUTH);

		JPanel browserCard = createCardPanel();
		browserCard.setLayout(new BorderLayout(6, 6));
		styleButton(browserToggleButton, false);
		browserToggleButton.setText("Browse Strategies");
		browserCard.add(browserToggleButton, BorderLayout.NORTH);
		browserCard.add(browserPanel, BorderLayout.CENTER);

		JPanel detailPanel = createCardPanel();
		detailPanel.setLayout(new BorderLayout(8, 8));
		detailPanel.add(buildDetailHeader(), BorderLayout.NORTH);
		detailPanel.add(buildGearSection(), BorderLayout.CENTER);
		detailPanel.add(buildNotesSection(), BorderLayout.SOUTH);

		JPanel main = new JPanel();
		main.setOpaque(false);
		main.setLayout(new BoxLayout(main, BoxLayout.Y_AXIS));
		main.add(browserCard);
		main.add(Box.createVerticalStrut(6));
		main.add(detailPanel);

		setBrowserExpanded(false);
		return main;
	}

	private Component buildDetailHeader()
	{
		JPanel header = new JPanel(new BorderLayout(6, 8));
		header.setOpaque(false);

		JPanel textStack = new JPanel();
		textStack.setLayout(new BoxLayout(textStack, BoxLayout.Y_AXIS));
		textStack.setOpaque(false);
		textStack.add(selectedMonsterLabel);
		textStack.add(Box.createVerticalStrut(2));
		textStack.add(detailsMetaLabel);
		header.add(textStack, BorderLayout.NORTH);

		JPanel actionRow = new JPanel(new BorderLayout(6, 0));
		actionRow.setOpaque(false);
		styleButtonPanel.setOpaque(false);
		actionRow.add(styleButtonPanel, BorderLayout.CENTER);

		styleButton(wikiButton, false);
		wikiButton.setFont(new Font("SansSerif", Font.BOLD, 11));
		wikiButton.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BLUE_ACCENT),
			BorderFactory.createEmptyBorder(4, 10, 4, 10)
		));
		wikiButton.setPreferredSize(new Dimension(58, 26));
		wikiButton.setMaximumSize(new Dimension(58, 26));
		actionRow.add(wikiButton, BorderLayout.EAST);

		// Variant toggle — only visible when monster has a boss/base counterpart
		variantToggleButton.setFont(new Font("SansSerif", Font.BOLD, 10));
		variantToggleButton.setForeground(Color.WHITE);
		variantToggleButton.setBackground(new Color(90, 60, 160));
		variantToggleButton.setOpaque(true);
		variantToggleButton.setFocusPainted(false);
		variantToggleButton.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(new Color(130, 90, 210)),
			BorderFactory.createEmptyBorder(3, 8, 3, 8)
		));
		variantTogglePanel.setOpaque(false);
		variantTogglePanel.add(variantToggleButton);
		variantTogglePanel.setVisible(false);

		JPanel bottomStack = new JPanel();
		bottomStack.setOpaque(false);
		bottomStack.setLayout(new BoxLayout(bottomStack, BoxLayout.Y_AXIS));
		bottomStack.add(variantTogglePanel);
		bottomStack.add(Box.createVerticalStrut(4));
		bottomStack.add(actionRow);
		header.add(bottomStack, BorderLayout.SOUTH);
		return header;
	}

	private Component buildGearSection()
	{
		JPanel section = new JPanel(new BorderLayout(6, 6));
		section.setOpaque(false);

		JPanel titlePanel = new JPanel();
		titlePanel.setOpaque(false);
		titlePanel.setLayout(new BoxLayout(titlePanel, BoxLayout.Y_AXIS));
		JLabel title = new JLabel("Recommended Setups");
		title.setForeground(TEXT_MAIN);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
		titlePanel.add(title);
		titlePanel.add(Box.createVerticalStrut(2));
		JLabel slotHint = createMutedLabel("* indicates slots with notes");
		titlePanel.add(slotHint);
		section.add(titlePanel, BorderLayout.NORTH);

		gearTable.setFillsViewportHeight(true);
		gearTable.setBackground(BG_CARD_ALT);
		gearTable.setForeground(TEXT_MAIN);
		gearTable.setFont(new Font("SansSerif", Font.PLAIN, 11));
		gearTable.setGridColor(BORDER);
		gearTable.setRowHeight(36);
		gearTable.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
		gearTable.getTableHeader().setBackground(BG_CARD_ALT);
		gearTable.getTableHeader().setForeground(TEXT_MAIN);
		gearTable.getTableHeader().setFont(new Font("SansSerif", Font.BOLD, 11));
		gearTable.setDefaultRenderer(Object.class, new GearCellRenderer());
		gearTable.getColumnModel().getColumn(0).setMinWidth(44);
		gearTable.getColumnModel().getColumn(0).setPreferredWidth(52);
		gearTable.getColumnModel().getColumn(1).setPreferredWidth(90);
		gearTable.getColumnModel().getColumn(2).setPreferredWidth(104);

		JScrollPane tableScroll = new JScrollPane(gearTable);
		tableScroll.setBorder(BorderFactory.createLineBorder(BORDER));
		tableScroll.getViewport().setBackground(BG_CARD_ALT);
		section.add(tableScroll, BorderLayout.CENTER);

		return section;
	}

	private Component buildNotesSection()
	{
		JPanel notesCard = new JPanel(new BorderLayout(6, 6));
		notesCard.setOpaque(false);

		JLabel title = new JLabel("Notes For Selected Slot");
		title.setForeground(TEXT_MAIN);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 13f));
		notesCard.add(title, BorderLayout.NORTH);

		notesArea.setEditable(false);
		notesArea.setLineWrap(true);
		notesArea.setWrapStyleWord(true);
		notesArea.setRows(4);
		notesArea.setForeground(TEXT_MAIN);
		notesArea.setFont(new Font("SansSerif", Font.PLAIN, 12));
		notesArea.setBackground(BG_CARD_ALT);
		notesArea.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JScrollPane notesScroll = new JScrollPane(notesArea);
		notesScroll.setBorder(BorderFactory.createLineBorder(BORDER));
		notesScroll.getViewport().setBackground(BG_CARD_ALT);
		notesCard.add(notesScroll, BorderLayout.CENTER);

		return notesCard;
	}

	private void wireListeners()
	{
		searchField.getDocument().addDocumentListener(new DocumentListener()
		{
			@Override
			public void insertUpdate(DocumentEvent event)
			{
				filterMonsters();
			}

			@Override
			public void removeUpdate(DocumentEvent event)
			{
				filterMonsters();
			}

			@Override
			public void changedUpdate(DocumentEvent event)
			{
				filterMonsters();
			}
		});

		monsterList.addListSelectionListener(new ListSelectionListener()
		{
			@Override
			public void valueChanged(ListSelectionEvent event)
			{
				if (!event.getValueIsAdjusting())
				{
					onMonsterSelected(monsterList.getSelectedValue());
				}
			}
		});

		gearTable.getSelectionModel().addListSelectionListener(event ->
		{
			if (!event.getValueIsAdjusting())
			{
				updateNotesForSelectedRow();
			}
		});

		variantToggleButton.addActionListener(event ->
		{
			if (variantTargetKey != null)
			{
				selectMonsterByKey(variantTargetKey);
			}
		});
		browserToggleButton.addActionListener(event -> setBrowserExpanded(!browserExpanded));
		browseAllButton.addActionListener(event -> setBrowserExpanded(true));
		taskActionButton.addActionListener(event ->
		{
			boolean ok = selectMonsterByKey(currentTaskMonsterKey);
			if (ok)
			{
				// User navigated to the task — button no longer needed
				taskActionButton.setVisible(false);
			}
		});
		wikiButton.addActionListener(event -> openCurrentWikiPage());
	}

	private void setBrowserExpanded(boolean expanded)
	{
		browserExpanded = expanded;
		browserPanel.setVisible(expanded);
		browserToggleButton.setText(expanded ? "Hide Strategies" : "Browse Strategies");
		revalidate();
		repaint();
	}

	private void filterMonsters()
	{
		String query = searchField.getText() == null ? "" : searchField.getText().trim().toLowerCase(Locale.ENGLISH);
		SlayerCodexDataStore.MonsterSummary selected = monsterList.getSelectedValue();

		listModel.clear();
		for (SlayerCodexDataStore.MonsterSummary summary : allMonsters)
		{
			if (query.isEmpty() || summary.getName().toLowerCase(Locale.ENGLISH).contains(query))
			{
				listModel.addElement(summary);
			}
		}

		if (listModel.isEmpty())
		{
			clearDetails("No monsters match your search.");
			return;
		}

		if (selected != null)
		{
			monsterList.setSelectedValue(selected, true);
			return;
		}

		clearDetails("Detect a Slayer task or open the monster browser to choose a setup.");
	}

	private void onMonsterSelected(SlayerCodexDataStore.MonsterSummary summary)
	{
		if (summary == null)
		{
			clearDetails("Select a monster to view strategy details.");
			return;
		}

		SlayerCodexDataStore.MonsterDetails details = dataStore.getMonsterDetails(summary.getKey());
		if (details == null)
		{
			clearDetails("Unable to load details for " + summary.getName() + ".");
			return;
		}

		currentMonster = details;
		selectedMonsterLabel.setText(summary.getName());
		wikiButton.setEnabled(summary.getWikiPage() != null && !summary.getWikiPage().trim().isEmpty());

		List<String> metaParts = new ArrayList<>();
		if (summary.getSlayerLevel() != null)
		{
			metaParts.add("Slayer " + summary.getSlayerLevel());
		}
		if (summary.getCombatLevel() != null)
		{
			metaParts.add("Combat " + summary.getCombatLevel());
		}
		if (summary.getTaskWeight() != null)
		{
			metaParts.add("Task weight " + summary.getTaskWeight());
		}
		if (details.isBossVariant() && details.getBaseTask() != null)
		{
			metaParts.add("Boss variant for " + details.getBaseTask().replace('_', ' '));
		}
		detailsMetaLabel.setText(metaParts.isEmpty() ? "General strategy" : String.join("  |  ", metaParts));

		// Boss/base variant toggle
		String bossKey = dataStore.getBossKeyForBase(summary.getKey());
		String baseKey = details.isBossVariant() ? details.getBaseTask() : null;
		if (bossKey != null && summaryByKey.containsKey(bossKey))
		{
			variantTargetKey = bossKey;
			SlayerCodexDataStore.MonsterSummary bossSummary = summaryByKey.get(bossKey);
			variantToggleButton.setText("▲ Boss: " + bossSummary.getName());
			variantToggleButton.setToolTipText("Switch to boss variant: " + bossSummary.getName());
			variantTogglePanel.setVisible(true);
		}
		else if (baseKey != null && summaryByKey.containsKey(baseKey))
		{
			variantTargetKey = baseKey;
			SlayerCodexDataStore.MonsterSummary baseSummary = summaryByKey.get(baseKey);
			String baseName = baseSummary != null ? baseSummary.getName() : baseKey.replace('_', ' ');
			variantToggleButton.setText("↓ Base: " + baseName);
			variantToggleButton.setToolTipText("Switch to base task: " + baseName);
			variantTogglePanel.setVisible(true);
		}
		else
		{
			variantTargetKey = null;
			variantTogglePanel.setVisible(false);
		}

		List<SlayerCodexDataStore.CombatStyleDetails> visibleStyles = dedupeStylesByName(getStylesWithUsableRows(details.getCombatStyles()));
		rebuildStyleButtons(visibleStyles);
		if (!styleButtons.isEmpty())
		{
			styleButtons.get(0).doClick();
		}
		else
		{
			gearTableModel.setRows(Collections.emptyList());
			if (details.getCombatStyles().isEmpty())
			{
				notesArea.setText("No builds found for this monster.");
			}
			else
			{
				notesArea.setText("No usable gear slots found in crawler data for this monster yet.");
			}
		}
	}

	private List<SlayerCodexDataStore.CombatStyleDetails> getStylesWithUsableRows(List<SlayerCodexDataStore.CombatStyleDetails> styles)
	{
		List<SlayerCodexDataStore.CombatStyleDetails> filtered = new ArrayList<>();
		for (SlayerCodexDataStore.CombatStyleDetails style : styles)
		{
			if (hasUsableRows(style))
			{
				filtered.add(style);
			}
		}
		return filtered;
	}

	private List<SlayerCodexDataStore.CombatStyleDetails> dedupeStylesByName(List<SlayerCodexDataStore.CombatStyleDetails> styles)
	{
		Map<String, SlayerCodexDataStore.CombatStyleDetails> deduped = new LinkedHashMap<>();
		for (SlayerCodexDataStore.CombatStyleDetails style : styles)
		{
			String key = normalizeStyleName(style.getName());
			SlayerCodexDataStore.CombatStyleDetails existing = deduped.get(key);
			if (existing == null || countUsableRows(style) > countUsableRows(existing))
			{
				deduped.put(key, style);
			}
		}
		return new ArrayList<>(deduped.values());
	}

	private String normalizeStyleName(String name)
	{
		if (name == null)
		{
			return "";
		}
		return name.trim().toLowerCase(Locale.ENGLISH).replaceAll("\\s+", " ");
	}

	private int countUsableRows(SlayerCodexDataStore.CombatStyleDetails style)
	{
		int count = 0;
		for (List<SlayerCodexDataStore.GearRow> rows : style.getTierRows().values())
		{
			for (SlayerCodexDataStore.GearRow row : rows)
			{
				if (row != null && row.getItemName() != null && !row.getItemName().trim().isEmpty())
				{
					count++;
				}
			}
		}
		return count;
	}

	private boolean hasUsableRows(SlayerCodexDataStore.CombatStyleDetails style)
	{
		if (style == null || style.getTierRows().isEmpty())
		{
			return false;
		}

		for (List<SlayerCodexDataStore.GearRow> rows : style.getTierRows().values())
		{
			for (SlayerCodexDataStore.GearRow row : rows)
			{
				if (row != null && row.getItemName() != null && !row.getItemName().trim().isEmpty())
				{
					return true;
				}
			}
		}

		return false;
	}

	private void rebuildStyleButtons(List<SlayerCodexDataStore.CombatStyleDetails> styles)
	{
		styleButtonPanel.removeAll();
		styleButtons.clear();
		styleGroup.clearSelection();

		int styleCount = styles.size();
		if (styleCount > 0)
		{
			int columns = Math.min(4, styleCount);
			int rows = (int) Math.ceil(styleCount / 4.0);
			styleButtonPanel.setLayout(new GridLayout(rows, columns, 4, 4));
		}

		for (SlayerCodexDataStore.CombatStyleDetails style : styles)
		{
			JToggleButton button = new JToggleButton(style.getName());
			styleButton(button, true);
			button.setFont(new Font("SansSerif", Font.BOLD, 10));
			button.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(BORDER),
				BorderFactory.createEmptyBorder(3, 6, 3, 6)
			));
			button.setPreferredSize(new Dimension(0, 24));
			button.setToolTipText(style.getName());
			button.addActionListener(event -> onCombatStyleChanged(style));
			styleButtons.add(button);
			styleGroup.add(button);
			styleButtonPanel.add(button);
		}

		styleButtonPanel.revalidate();
		styleButtonPanel.repaint();
	}

	private void onCombatStyleChanged(SlayerCodexDataStore.CombatStyleDetails style)
	{
		if (currentMonster == null)
		{
			return;
		}

		currentStyle = style;
		List<GearMatrixRow> matrixRows = buildGearMatrixRows(style);
		currentMatrixRows = matrixRows;
		gearTableModel.setRows(matrixRows);
		if (!matrixRows.isEmpty())
		{
			gearTable.clearSelection();
			notesArea.setText("Slots marked with * contain notes. Select a marked slot to see note details (e.g., (a) full explanation).\n");
			notesArea.setCaretPosition(0);
		}
		else
		{
			notesArea.setText("This build has no gear slots in crawler data yet. Select another build, or update the data file.");
			notesArea.setCaretPosition(0);
		}
	}

	private List<GearMatrixRow> buildGearMatrixRows(SlayerCodexDataStore.CombatStyleDetails style)
	{
		if (currentMonster == null)
		{
			return Collections.emptyList();
		}

		List<SlayerCodexRecommendationService.RecommendationRow> rows = recommendationService.buildRows(
			currentMonster.getSummary().getKey(),
			style);

		List<GearMatrixRow> matrix = new ArrayList<>();
		for (SlayerCodexRecommendationService.RecommendationRow row : rows)
		{
			matrix.add(new GearMatrixRow(
				row.getSlot(),
				row.getWiki(),
				row.getYourBest(),
				row.getNoteIds()
			));
		}
		return matrix;
	}

	private void updateNotesForSelectedRow()
	{
		if (currentMonster == null || currentMatrixRows.isEmpty())
		{
			notesArea.setText("Slots marked with * contain notes. Select a marked slot to see note details.");
			notesArea.setCaretPosition(0);
			return;
		}

		int selectedRow = gearTable.getSelectedRow();
		if (selectedRow < 0 || selectedRow >= currentMatrixRows.size())
		{
			notesArea.setText("Slots marked with * contain notes. Select a marked slot to see note details.");
			notesArea.setCaretPosition(0);
			return;
		}

		GearMatrixRow row = currentMatrixRows.get(selectedRow);
		notesArea.setText(buildNotesForRow(row.noteIds));
		notesArea.setCaretPosition(0);
	}

	private String buildNotesForRow(List<String> noteIds)
	{
		if (noteIds == null || noteIds.isEmpty())
		{
			return "No notes for this slot.";
		}

		Map<String, String> footnotes = currentMonster == null ? Collections.emptyMap() : currentMonster.getFootnotes();
		LinkedHashSet<String> uniqueKnownNoteIds = new LinkedHashSet<>();
		for (String noteId : noteIds)
		{
			if (footnotes.containsKey(noteId))
			{
				uniqueKnownNoteIds.add(noteId);
			}
		}

		if (uniqueKnownNoteIds.isEmpty())
		{
			return "No notes for this slot.";
		}

		StringBuilder text = new StringBuilder("Relevant notes for this slot:\n");
		for (String noteId : uniqueKnownNoteIds)
		{
			String code = displayNoteId(noteId);
			String note = footnotes.get(noteId);
			text.append("(").append(code).append(") ").append(note).append("\n");
		}

		return text.toString().trim();
	}

	private List<String> getVisibleNoteCodes(List<String> noteIds)
	{
		if (noteIds == null || noteIds.isEmpty() || currentMonster == null)
		{
			return Collections.emptyList();
		}

		Map<String, String> footnotes = currentMonster.getFootnotes();
		LinkedHashSet<String> codes = new LinkedHashSet<>();
		for (String noteId : noteIds)
		{
			if (footnotes.containsKey(noteId))
			{
				codes.add(displayNoteId(noteId));
			}
		}
		return new ArrayList<>(codes);
	}

	private String displayNoteId(String noteId)
	{
		if (noteId == null)
		{
			return "note";
		}
		int idx = noteId.lastIndexOf(':');
		if (idx >= 0 && idx + 1 < noteId.length())
		{
			return noteId.substring(idx + 1);
		}
		return noteId;
	}

	private void openCurrentWikiPage()
	{
		if (currentMonster == null)
		{
			return;
		}

		String wikiPage = currentMonster.getSummary().getWikiPage();
		if (wikiPage == null || wikiPage.trim().isEmpty())
		{
			return;
		}

		String url = wikiPage.startsWith("http://") || wikiPage.startsWith("https://")
			? wikiPage
			: WIKI_BASE_URL + wikiPage.replace(" ", "_");
		LinkBrowser.browse(url);
	}

	private void clearDetails(String message)
	{
		currentMonster = null;
		currentStyle = null;
		currentMatrixRows = Collections.emptyList();
		selectedMonsterLabel.setText("Select a monster");
		detailsMetaLabel.setText("Pick a task setup or open the monster browser");
		styleButtonPanel.removeAll();
		styleButtons.clear();
		styleGroup.clearSelection();
		gearTableModel.setRows(Collections.emptyList());
		notesArea.setText(message);
		wikiButton.setEnabled(false);
	}

	private void setIcon(JLabel label, int itemId)
	{
		AsyncBufferedImage image = itemManager.getImage(itemId);
		image.addTo(label);
	}

	private void setInitialDisabledState()
	{
		wikiButton.setEnabled(false);
		taskActionButton.setEnabled(false);
		taskActionButton.setVisible(true);
		bankHintPanel.setVisible(true);
		notesArea.setText("Select a task setup or a monster build. Slots marked with * contain notes.");
	}

	private JPanel createCardPanel()
	{
		JPanel panel = new JPanel();
		panel.setBackground(BG_CARD);
		panel.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(BORDER),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)
		));
		return panel;
	}

	private JLabel createTitleLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(TEXT_MAIN);
		label.setFont(new Font("SansSerif", Font.BOLD, 16));
		return label;
	}

	private JLabel createMutedLabel(String text)
	{
		JLabel label = new JLabel(text);
		label.setForeground(TEXT_MUTED);
		label.setFont(new Font("SansSerif", Font.PLAIN, 12));
		return label;
	}

	private void styleButton(AbstractButton button, boolean compact)
	{
		styleButton(button, compact, compact ? 11 : 12);
	}

	private void styleButton(AbstractButton button, boolean compact, int fontSize)
	{
		button.setFocusPainted(false);
		button.setBackground(compact ? BG_CARD_ALT : new Color(28, 63, 110));
		button.setForeground(TEXT_MAIN);
		button.setFont(new Font("SansSerif", Font.BOLD, fontSize));
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(compact ? BORDER : BLUE_ACCENT),
			BorderFactory.createEmptyBorder(5, compact ? 8 : 12, 5, compact ? 8 : 12)
		));
	}

	private void styleButtonRed(AbstractButton button)
	{
		styleButtonRed(button, 12);
	}

	private void styleButtonRed(AbstractButton button, int fontSize)
	{
		button.setFocusPainted(false);
		button.setBackground(new Color(100, 20, 20));
		button.setForeground(Color.WHITE);
		button.setFont(new Font("SansSerif", Font.BOLD, fontSize));
		button.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(RED_ACCENT),
			BorderFactory.createEmptyBorder(5, 12, 5, 12)
		));
	}

	private final class MonsterSummaryRenderer extends JLabel implements ListCellRenderer<SlayerCodexDataStore.MonsterSummary>
	{
		private MonsterSummaryRenderer()
		{
			setOpaque(true);
			setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
		}

		@Override
		public Component getListCellRendererComponent(
			JList<? extends SlayerCodexDataStore.MonsterSummary> list,
			SlayerCodexDataStore.MonsterSummary value,
			int index,
			boolean isSelected,
			boolean cellHasFocus)
		{
			if (value == null)
			{
				setText("");
			}
			else
			{
				List<String> parts = new ArrayList<>();
				if (value.getSlayerLevel() != null)
				{
					parts.add("Slayer " + value.getSlayerLevel());
				}
				if (value.getCombatLevel() != null)
				{
					parts.add("Combat " + value.getCombatLevel());
				}
				String suffix = parts.isEmpty() ? "" : "<br/><span style='color:#9CA7B7; font-size:10px;'>" + String.join(" | ", parts) + "</span>";
				setText("<html><div style='font-size:13px;'>" + value.getName() + suffix + "</div></html>");
			}

			if (isSelected)
			{
				setBackground(new Color(32, 57, 88));
				setForeground(TEXT_MAIN);
			}
			else
			{
				setBackground(BG_CARD_ALT);
				setForeground(TEXT_MAIN);
			}

			return this;
		}
	}

	private final class GearCellRenderer extends DefaultTableCellRenderer
	{
		@Override
		public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int col)
		{
			Component component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, col);
			setIcon(null);
			setHorizontalAlignment(CENTER);
			setFont(new Font("SansSerif", Font.PLAIN, col == 0 ? 10 : 11));
			if (!isSelected)
			{
				component.setBackground(col == 0 ? new Color(34, 41, 53) : BG_CARD_ALT);
			}

			if (col == 0)
			{
				String slotText = abbreviateSlot(String.valueOf(value));
				List<String> noteCodes = Collections.emptyList();
				if (row >= 0 && row < currentMatrixRows.size())
				{
					noteCodes = getVisibleNoteCodes(currentMatrixRows.get(row).noteIds);
				}

				if (!noteCodes.isEmpty())
				{
					setForeground(new Color(255, 214, 102));
					setText(slotText + "*");
					setToolTipText("Notes: (" + String.join("), (", noteCodes) + "). Select this slot to read details below.");
				}
				else
				{
					setForeground(TEXT_MUTED);
					setText(slotText);
					setToolTipText("No notes for this slot.");
				}
				setHorizontalAlignment(LEFT);
				return component;
			}

			if (value instanceof SlayerCodexRecommendationService.RecommendationCell)
			{
				SlayerCodexRecommendationService.RecommendationCell cell = (SlayerCodexRecommendationService.RecommendationCell) value;
				setText(shortCellLabel(cell));
				if (cell.getItemId() > 0)
				{
					AsyncBufferedImage image = itemManager.getImage(cell.getItemId());
					setIcon(new ImageIcon(image));
					image.onLoaded(() -> SwingUtilities.invokeLater(table::repaint));
				}

				String toolTip = cell.getLabel();
				if (row >= 0 && row < currentMatrixRows.size() && !currentMatrixRows.get(row).noteIds.isEmpty())
				{
					toolTip = toolTip + " - this slot has extra notes below";
				}
				if (cell.isEquipped())
				{
					setForeground(new Color(120, 223, 145));
					toolTip = toolTip + " - equipped now";
				}
				else if (cell.isBanked())
				{
					setForeground(new Color(132, 192, 255));
					toolTip = toolTip + " - found in bank";
				}
				else if (cell.isOwned())
				{
					setForeground(new Color(205, 232, 255));
					toolTip = toolTip + " - found in inventory";
				}
				else if (cell.isUnavailableFallback())
				{
					setForeground(TEXT_MUTED);
					toolTip = toolTip + " - not owned yet";
				}
				else
				{
					setForeground(TEXT_MAIN);
				}
				setToolTipText(toolTip);
			}
			else
			{
				setForeground(TEXT_MAIN);
				setText(Objects.toString(value, ""));
				setToolTipText(null);
			}
			return component;
		}

		private String shortCellLabel(SlayerCodexRecommendationService.RecommendationCell cell)
		{
			String label = cell.getLabel();
			if (label == null || label.isBlank())
			{
				return "-";
			}

			String[] parts = label.split("\\s+");
			if (parts.length == 1)
			{
				return trimToWidth(parts[0], 8);
			}

			StringBuilder shortLabel = new StringBuilder();
			for (int index = 0; index < Math.min(2, parts.length); index++)
			{
				if (parts[index].isEmpty())
				{
					continue;
				}
				if (shortLabel.length() > 0)
				{
					shortLabel.append(' ');
				}
				shortLabel.append(trimToWidth(parts[index], 4));
			}
			return shortLabel.toString();
		}

		private String trimToWidth(String value, int maxLength)
		{
			return value.length() <= maxLength ? value : value.substring(0, Math.max(1, maxLength - 1)) + ".";
		}

		private String abbreviateSlot(String slot)
		{
			switch (slot)
			{
				case "HEAD":
					return "Hd";
				case "NECK":
					return "Nk";
				case "CAPE":
					return "Cp";
				case "BODY":
					return "Bd";
				case "LEGS":
					return "Lg";
				case "WEAPON":
					return "Wp";
				case "SHIELD":
					return "Sh";
				case "AMMO":
					return "Am";
				case "HANDS":
					return "Ha";
				case "FEET":
					return "Ft";
				case "RING":
					return "Rg";
				case "SPECIAL":
					return "Sp";
				default:
					return trimToWidth(slot, 3);
			}
		}
	}

	private static final class GearMatrixTableModel extends AbstractTableModel
	{
		private static final String[] COLUMNS = {"Slot", "Wiki", "You"};
		private List<GearMatrixRow> rows = Collections.emptyList();

		void setRows(List<GearMatrixRow> rows)
		{
			this.rows = rows;
			fireTableDataChanged();
		}

		@Override
		public int getRowCount()
		{
			return rows.size();
		}

		@Override
		public int getColumnCount()
		{
			return COLUMNS.length;
		}

		@Override
		public String getColumnName(int column)
		{
			return COLUMNS[column];
		}

		@Override
		public Object getValueAt(int rowIndex, int columnIndex)
		{
			GearMatrixRow row = rows.get(rowIndex);
			switch (columnIndex)
			{
				case 0:
					return row.slot;
				case 1:
					return row.wiki;
				case 2:
					return row.yourBest;
				default:
					return "";
			}
		}
	}

	private static final class GearMatrixRow
	{
		private final String slot;
		private final SlayerCodexRecommendationService.RecommendationCell wiki;
		private final SlayerCodexRecommendationService.RecommendationCell yourBest;
		private final List<String> noteIds;

		private GearMatrixRow(
			String slot,
			SlayerCodexRecommendationService.RecommendationCell wiki,
			SlayerCodexRecommendationService.RecommendationCell yourBest,
			List<String> noteIds)
		{
			this.slot = slot;
			this.wiki = wiki;
			this.yourBest = yourBest;
			this.noteIds = noteIds;
		}
	}
}
