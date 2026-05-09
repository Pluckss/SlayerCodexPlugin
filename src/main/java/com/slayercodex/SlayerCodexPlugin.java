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
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
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
import net.runelite.client.ui.overlay.OverlayManager;
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
	private SlayerCodexItemOverlay itemOverlay;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private EventBus eventBus;

	private String currentTaskName;
	private Integer currentTaskRemaining;

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
		overlayManager.add(itemOverlay);

		try
		{
			loadBundledData();
			panel.refreshRecommendations();
			panel.setCurrentTask(currentTaskName, currentTaskRemaining, false);
		}
		catch (Exception ex)
		{
			log.error("Slayer Codex failed to initialize fully", ex);
			panel.setErrorStatus(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
		}

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

		// Same task already loaded — avoid re-firing the panel update on every dialog tick.
		if (update.taskName != null
			&& update.taskName.equalsIgnoreCase(currentTaskName)
			&& java.util.Objects.equals(update.remaining, currentTaskRemaining))
		{
			return;
		}

		currentTaskName = update.taskName;
		currentTaskRemaining = update.remaining;

		String key = dataStore.findBestMonsterKeyForTask(currentTaskName, config.preferBossVariant());
		panel.setCurrentTaskTarget(key);
		boolean autoSelected = panel.selectMonsterByKey(key);
		if (key == null)
		{
			// Detected the task but the bundled dataset has no strategy for it — surface the
			// task name in the panel with a wiki link so the player still gets useful info.
			panel.setTaskWithoutStrategy(currentTaskName);
		}
		panel.setCurrentTask(currentTaskName, currentTaskRemaining, autoSelected);

		log.debug("Detected task via {}: {} ({}) -> {}", source, currentTaskName, currentTaskRemaining, key);
	}

	private void clearCurrentTask()
	{
		currentTaskName = null;
		currentTaskRemaining = null;
		if (panel != null)
		{
			panel.setCurrentTaskTarget(null);
			panel.setCurrentTask(null, null, false);
		}
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		ownershipTracker.capture(event.getContainerId(), event.getItemContainer());
		if (panel != null)
		{
			panel.refreshRecommendations();
		}

		// Item cache may have just become available — index now and re-resolve the
		// current task's gear so the bank filter / overlay pick up fresh item IDs.
		boolean wasIndexed = itemResolver.isIndexed();
		itemResolver.warmUp();
		if (!wasIndexed && itemResolver.isIndexed() && panel != null)
		{
			panel.refreshFocus();
		}
	}

	@Override
	protected void shutDown()
	{
		eventBus.unregister(bankFilter);
		overlayManager.remove(itemOverlay);
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

		if (panel == null)
		{
			return;
		}

		// Reload current view so changes like compact rows / preferred style take effect immediately
		panel.refreshRecommendations();
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
			if (!wasIndexed && itemResolver.isIndexed() && panel != null)
			{
				// Index just became available — re-fire focus listener so the previously-empty
				// taskState picks up the now-resolvable item ids.
				panel.refreshFocus();
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
			panel.initializeFromStore();
			panel.setDataStatus(dataStore.getMonsterCount(), dataStore.getCrawlDate());
		}
		catch (Exception ex)
		{
			log.error("Could not load bundled strategy JSON", ex);
			panel.setErrorStatus(ex.getMessage());
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

	private TaskUpdate parseTaskUpdate(String message)
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

	private String cleanupTaskName(String task)
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

	private Integer extractFirstNumber(String text)
	{
		Matcher matcher = Pattern.compile("(\\d+)").matcher(text);
		if (matcher.find())
		{
			return Integer.parseInt(matcher.group(1));
		}
		return null;
	}

	private static final class TaskUpdate
	{
		private final String taskName;
		private final Integer remaining;

		private TaskUpdate(String taskName, Integer remaining)
		{
			this.taskName = taskName;
			this.remaining = remaining;
		}
	}
}
