package com.slayercodex;

import com.google.inject.Provides;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(
	name = "Slayer Codex"
)
public class SlayerCodexPlugin extends Plugin
{
	private static final Pattern ASSIGNMENT_PATTERN = Pattern.compile(
		"(?:(?:now\\s+)?assigned\\s+to\\s+(?:kill|slay)|hunt|new\\s+task\\s+is\\s+to\\s+(?:kill|slay))\\s+(\\d+)\\s+([a-zA-Z '\\-]+)",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern REMAINING_PATTERN = Pattern.compile(
		"(?:hunting|assigned\\s+to\\s+(?:kill|slay))\\s+([a-zA-Z '\\-]+).*?(\\d+)\\s+(?:to go|more to go|remaining)",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern TASK_ENDED_PATTERN = Pattern.compile(
		"(?:you(?:'ve| have)\\s+completed\\s+your\\s+(?:slayer\\s+)?task"
			+ "|task\\s+(?:has\\s+been\\s+)?cancell?ed"
			+ "|return\\s+to\\s+a\\s+slayer\\s+master"
			+ "|you\\s+can\\s+now\\s+earn\\s+slayer\\s+reward\\s+points)",
		Pattern.CASE_INSENSITIVE);
	private static final Pattern NUMBER_PATTERN = Pattern.compile("(\\d+)");
	// Value of VarPlayerID.SLAYER_TARGET that means "boss task" — the actual boss is then
	// found via VarbitID.SLAYER_TARGET_BOSSID (same lookup the core Slayer plugin uses).
	private static final int SLAYER_TASK_BOSS_TARGET_ID = 98;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private SlayerCodexDataStore dataStore;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private SlayerCodexOwnershipTracker ownershipTracker;

	@Inject
	private SlayerCodexItemResolver itemResolver;

	@Inject
	private SlayerCodexRecommendationService recommendationService;

	@Inject
	private SlayerCodexConfig config;

	@Inject
	private SlayerCodexTaskState taskState;

	@Inject
	private SlayerCodexBankFilter bankFilter;

	@Inject
	private EventBus eventBus;

	private String currentTaskName;
	private Integer currentTaskRemaining;
	private volatile boolean lastTaskAutoSelected;
	private int lastOwnershipSignature;

	private SlayerCodexPanel panel;
	private NavigationButton navButton;

	@Override
	protected void startUp()
	{
		panel = new SlayerCodexPanel(dataStore, itemManager, recommendationService, ownershipTracker, config);
		panel.setFocusListener(this::onPanelFocusChanged);

		navButton = NavigationButton.builder()
			.tooltip("Slayer Codex")
			.priority(5)
			.icon(createSlayerIcon())
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		eventBus.register(bankFilter);

		try
		{
			loadBundledData();
			updateUi(() ->
			{
				panel.refreshRecommendations();
				panel.setCurrentTask(currentTaskName, currentTaskRemaining, false);
			});
		}
		catch (Exception ex)
		{
			log.error("Slayer Codex failed to initialize fully", ex);
			final String message = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
			updateUi(() -> panel.setErrorStatus(message));
		}

		// Plugin may be toggled on mid-session — pick up the current assignment immediately.
		// Runs after loadBundledData so the task→monster lookup has data to match against.
		clientThread.invoke(() ->
		{
			if (client.getGameState() == GameState.LOGGED_IN)
			{
				itemResolver.warmUp();
				if (config.autoDetectTask())
				{
					updateTaskFromVarps();
				}
			}
		});

		log.info("Slayer Codex plugin started");
	}

	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (!config.autoDetectTask())
		{
			return;
		}

		ChatMessageType type = event.getType();
		if (type != ChatMessageType.GAMEMESSAGE
			&& type != ChatMessageType.SPAM
			&& type != ChatMessageType.CONSOLE
			&& type != ChatMessageType.FRIENDSCHATNOTIFICATION)
		{
			return;
		}

		String message = Text.removeTags(event.getMessage());
		applyTaskFromText(message, "chat");
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (!config.autoDetectTask())
		{
			return;
		}

		int groupId = event.getGroupId();
		if (groupId != InterfaceID.CHAT_LEFT && groupId != InterfaceID.CHAT_RIGHT)
		{
			return;
		}

		// The dialog widget fires WidgetLoaded before its text is populated by the client
		// scripts — defer the read so we see the actual NPC line, not an empty placeholder.
		clientThread.invokeLater(() ->
		{
			Widget textWidget = client.getWidget(
				groupId == InterfaceID.CHAT_LEFT
					? InterfaceID.ChatLeft.TEXT
					: InterfaceID.ChatRight.TEXT);
			if (textWidget == null)
			{
				return;
			}

			String raw = textWidget.getText();
			if (raw == null || raw.isEmpty())
			{
				return;
			}

			// Wiki dialog text uses <br> as line breaks — flatten so the regex can span them.
			String message = Text.removeTags(raw.replace("<br>", " "));
			applyTaskFromText(message, "dialog");
		});
	}

	private void applyTaskFromText(String message, String source)
	{
		if (message == null)
		{
			return;
		}

		if (TASK_ENDED_PATTERN.matcher(message).find())
		{
			clearCurrentTask();
			log.debug("Slayer task ended via {}: {}", source, message);
			return;
		}

		String lower = message.toLowerCase();
		if (!lower.contains("slayer")
			&& !lower.contains("to go")
			&& !lower.contains("assigned")
			&& !lower.contains("new task")
			&& !lower.contains("hunting"))
		{
			return;
		}

		TaskUpdate update = parseTaskUpdate(message);
		if (update == null)
		{
			return;
		}

		applyDetectedTask(update.taskName, update.remaining, source);
	}

	/**
	 * Reads the Slayer assignment straight from the player's varps — the same source the
	 * core Slayer plugin uses. Unlike chat parsing this works on login (no need to talk to
	 * a Slayer master or check a gem) and keeps the remaining count live as kills happen.
	 */
	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		if (!config.autoDetectTask())
		{
			return;
		}

		int varpId = event.getVarpId();
		if (varpId != VarPlayerID.SLAYER_COUNT
			&& varpId != VarPlayerID.SLAYER_TARGET
			&& event.getVarbitId() != VarbitID.SLAYER_TARGET_BOSSID)
		{
			return;
		}

		// Defer one cycle so all task varps in the same tick have settled before we read them.
		clientThread.invokeLater(this::updateTaskFromVarps);
	}

	private void updateTaskFromVarps()
	{
		int amount = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		if (amount <= 0)
		{
			if (currentTaskName != null)
			{
				clearCurrentTask();
				log.debug("Slayer task ended via varp");
			}
			return;
		}

		// Resolve the task name from the game's own slayer DB tables — the same lookup
		// the core Slayer plugin performs in [proc,helper_slayer_current_assignment].
		int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		int taskDBRow;
		if (taskId == SLAYER_TASK_BOSS_TARGET_ID)
		{
			var bossRows = client.getDBRowsByValue(
				DBTableID.SlayerTaskSublist.ID,
				DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID,
				0,
				client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID));
			if (bossRows.isEmpty())
			{
				return;
			}
			taskDBRow = (Integer) client.getDBTableField(bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
		}
		else
		{
			var taskRows = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
			if (taskRows.isEmpty())
			{
				return;
			}
			taskDBRow = taskRows.get(0);
		}

		String taskName = (String) client.getDBTableField(taskDBRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
		if (taskName == null || taskName.trim().isEmpty())
		{
			return;
		}

		applyDetectedTask(taskName, amount, "varp");
	}

	private void applyDetectedTask(String taskName, Integer remaining, String source)
	{
		if (taskName == null)
		{
			return;
		}

		boolean sameTask = taskName.equalsIgnoreCase(currentTaskName);
		if (sameTask && java.util.Objects.equals(remaining, currentTaskRemaining))
		{
			// Same task already loaded — avoid re-firing the panel update on every dialog tick.
			return;
		}

		currentTaskName = taskName;
		currentTaskRemaining = remaining;

		final Integer remainingFinal = remaining;
		if (sameTask)
		{
			// Only the kill count moved — update the header pill without re-running the
			// monster lookup or yanking the panel selection back to the task's monster.
			updateUi(() -> panel.setCurrentTask(taskName, remainingFinal, lastTaskAutoSelected));
			return;
		}

		final String key = dataStore.findBestMonsterKeyForTask(taskName, config.preferBossVariant());
		updateUi(() ->
		{
			panel.setCurrentTaskTarget(key);
			boolean autoSelected = panel.selectMonsterByKey(key);
			lastTaskAutoSelected = autoSelected;
			if (key == null)
			{
				// Detected the task but the bundled dataset has no strategy for it — surface the
				// task name in the panel with a wiki link so the player still gets useful info.
				panel.setTaskWithoutStrategy(taskName);
			}
			panel.setCurrentTask(taskName, remainingFinal, autoSelected);
		});

		log.debug("Detected task via {}: {} ({}) -> {}", source, taskName, remaining, key);
	}

	private void clearCurrentTask()
	{
		currentTaskName = null;
		currentTaskRemaining = null;
		updateUi(() ->
		{
			panel.setCurrentTaskTarget(null);
			panel.setCurrentTask(null, null, false);
		});
	}

	/**
	 * Runs a panel update on the Swing EDT. Plugin event handlers fire on the client thread,
	 * so any Swing mutation they trigger must be marshalled onto the EDT. Skips the action if
	 * the panel has been torn down (shutDown sets it null).
	 */
	private void updateUi(Runnable action)
	{
		SwingUtilities.invokeLater(() ->
		{
			if (panel != null)
			{
				action.run();
			}
		});
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		ownershipTracker.capture(event.getContainerId(), event.getItemContainer());

		// Item cache may have just become available — index now and re-resolve the
		// current task's gear so the bank filter picks up fresh item IDs.
		boolean wasIndexed = itemResolver.isIndexed();
		itemResolver.warmUp();
		boolean justIndexed = !wasIndexed && itemResolver.isIndexed();

		// Inventory changes constantly during combat; only rebuild the panel when the
		// ownership status of an item the current view actually cares about changed.
		int signature = ownershipSignature();
		if (!justIndexed && signature == lastOwnershipSignature)
		{
			return;
		}
		lastOwnershipSignature = signature;

		updateUi(() ->
		{
			panel.refreshRecommendations();
			if (justIndexed)
			{
				panel.refreshFocus();
			}
		});
	}

	private int ownershipSignature()
	{
		int hash = ownershipTracker.isBankKnown() ? 1 : 0;
		for (int id : taskState.getRelevantItemIds())
		{
			int status = (ownershipTracker.getOwnedQuantity(id) > 0 ? 4 : 0)
				| (ownershipTracker.isEquipped(id) ? 2 : 0)
				| (ownershipTracker.isInBank(id) ? 1 : 0);
			hash = 31 * hash + id;
			hash = 31 * hash + status;
		}
		return hash;
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(bankFilter);
		bankFilter.shutDown();
		taskState.clear();

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
			navButton = null;
		}

		panel = null;
		log.info("Slayer Codex plugin stopped");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!"slayercodex".equals(event.getGroup()))
		{
			return;
		}

		// Reload current view so changes like compact rows / preferred style take effect immediately
		updateUi(() ->
		{
			panel.applyDisplaySettings();
			panel.refreshRecommendations();
		});
		bankFilter.onTaskStateChanged();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state != GameState.LOGIN_SCREEN && state != GameState.LOGGED_IN)
		{
			return;
		}

		clientThread.invoke(() ->
		{
			boolean wasIndexed = itemResolver.isIndexed();
			itemResolver.warmUp();
			if (!wasIndexed && itemResolver.isIndexed())
			{
				// Index just became available — re-fire focus listener so the previously-empty
				// taskState picks up the now-resolvable item ids.
				updateUi(() -> panel.refreshFocus());
			}

			if (state == GameState.LOGGED_IN && config.autoDetectTask())
			{
				// Sync the assignment straight from varps — no chat message needed.
				updateTaskFromVarps();
			}
		});
	}

	private void onPanelFocusChanged(SlayerCodexDataStore.MonsterDetails details)
	{
		if (details == null)
		{
			taskState.clear();
		}
		else
		{
			taskState.update(
				details.getSummary().getKey(),
				details.getSummary().getName(),
				recommendationService.collectAllRelevantItemIds(details));
		}
		bankFilter.onTaskStateChanged();
	}

	@Provides
	SlayerCodexConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SlayerCodexConfig.class);
	}

	private void loadBundledData()
	{
		try
		{
			dataStore.load();
			updateUi(() ->
			{
				panel.initializeFromStore();
				panel.setDataStatus(dataStore.getMonsterCount(), dataStore.getCrawlDate());
			});
		}
		catch (Exception ex)
		{
			log.error("Could not load bundled strategy JSON", ex);
			final String message = ex.getMessage();
			updateUi(() -> panel.setErrorStatus(message));
		}
	}

	private BufferedImage createSlayerIcon()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D graphics = image.createGraphics();
		try
		{
			graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

			graphics.setColor(new Color(17, 33, 58));
			graphics.fillRoundRect(0, 0, 16, 16, 5, 5);

			graphics.setColor(new Color(57, 150, 255));
			graphics.fillOval(2, 2, 12, 12);

			graphics.setStroke(new BasicStroke(2f));
			graphics.setColor(new Color(210, 238, 255));
			graphics.drawLine(5, 11, 11, 5);
			graphics.drawLine(8, 12, 11, 9);

			graphics.setColor(new Color(136, 213, 255));
			graphics.fillRect(4, 10, 3, 3);
		}
		finally
		{
			graphics.dispose();
		}

		return image;
	}

	// Package-private and static so the chat-parsing logic is unit-testable.
	static TaskUpdate parseTaskUpdate(String message)
	{
		Matcher assignment = ASSIGNMENT_PATTERN.matcher(message);
		if (assignment.find())
		{
			int amount = Integer.parseInt(assignment.group(1));
			String task = cleanupTaskName(assignment.group(2));
			return new TaskUpdate(task, amount);
		}

		Matcher remaining = REMAINING_PATTERN.matcher(message);
		if (remaining.find())
		{
			String task = cleanupTaskName(remaining.group(1));
			int amount = Integer.parseInt(remaining.group(2));
			return new TaskUpdate(task, amount);
		}

		if (message.toLowerCase().contains("new slayer assignment"))
		{
			int colon = message.indexOf(':');
			if (colon >= 0 && colon < message.length() - 1)
			{
				String trailing = message.substring(colon + 1).trim();
				String[] chunks = trailing.split("\\(");
				String task = cleanupTaskName(chunks[0]);
				Integer amount = extractFirstNumber(trailing);
				if (task != null)
				{
					return new TaskUpdate(task, amount);
				}
			}
		}

		return null;
	}

	static String cleanupTaskName(String task)
	{
		if (task == null)
		{
			return null;
		}

		String cleaned = task
			.replace(".", "")
			.replace(";", "")
			.trim();

		String lower = cleaned.toLowerCase();
		int inIndex = lower.indexOf(" in ");
		int atIndex = lower.indexOf(" at ");
		int withIndex = lower.indexOf(" with ");
		int cutIndex = -1;
		for (int index : new int[] {inIndex, atIndex, withIndex})
		{
			if (index >= 0 && (cutIndex == -1 || index < cutIndex))
			{
				cutIndex = index;
			}
		}
		if (cutIndex >= 0)
		{
			cleaned = cleaned.substring(0, cutIndex).trim();
		}

		if (cleaned.isEmpty())
		{
			return null;
		}

		String[] pieces = cleaned.split("\\s+");
		StringBuilder title = new StringBuilder();
		for (String piece : pieces)
		{
			if (piece.isEmpty())
			{
				continue;
			}
			title.append(Character.toUpperCase(piece.charAt(0)));
			if (piece.length() > 1)
			{
				title.append(piece.substring(1).toLowerCase());
			}
			title.append(' ');
		}
		return title.toString().trim();
	}

	private static Integer extractFirstNumber(String text)
	{
		Matcher matcher = NUMBER_PATTERN.matcher(text);
		if (matcher.find())
		{
			return Integer.parseInt(matcher.group(1));
		}
		return null;
	}

	static final class TaskUpdate
	{
		final String taskName;
		final Integer remaining;

		private TaskUpdate(String taskName, Integer remaining)
		{
			this.taskName = taskName;
			this.remaining = remaining;
		}
	}
}
