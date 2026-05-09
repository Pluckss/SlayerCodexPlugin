package com.slayercodex;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ItemID;
import net.runelite.api.ScriptID;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.JavaScriptCallback;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetType;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;

/**
 * Adds a "Slayer" toggle button to the bank UI. When toggled on, hides any bank widget
 * whose item id is not in {@link SlayerCodexTaskState#getRelevantItemIds()} for the
 * currently focused monster. Robust against script changes because it does not rebuild
 * the bank widgets — it only flips visibility of existing item children.
 */
@Slf4j
@Singleton
public class SlayerCodexBankFilter
{
	private static final int TOGGLE_X = 392;
	private static final int TOGGLE_Y = 6;
	private static final int TOGGLE_SIZE = 22;

	// Lower opacity = more visible. 0 = full color, 255 = invisible.
	private static final int OPACITY_ON = 0;
	private static final int OPACITY_OFF = 70;
	private static final int OPACITY_DISABLED = 140;

	// Standard OSRS bank slot grid
	private static final int ITEMS_PER_ROW = 8;
	private static final int ITEM_STRIDE_X = 48;
	private static final int ITEM_STRIDE_Y = 36;

	private final Client client;
	private final ClientThread clientThread;
	private final ItemManager itemManager;
	private final SlayerCodexTaskState taskState;
	private final SlayerCodexConfig config;

	private boolean filterActive;
	private Widget toggleWidget;
	private final IdentityHashMap<Widget, int[]> savedPositions = new IdentityHashMap<>();

	@Inject
	public SlayerCodexBankFilter(
		Client client,
		ClientThread clientThread,
		ItemManager itemManager,
		SlayerCodexTaskState taskState,
		SlayerCodexConfig config)
	{
		this.client = client;
		this.clientThread = clientThread;
		this.itemManager = itemManager;
		this.taskState = taskState;
		this.config = config;
	}

	public void shutDown()
	{
		filterActive = false;
		toggleWidget = null;
	}

	public boolean isFilterActive()
	{
		return filterActive;
	}

	public void setFilterActive(boolean active)
	{
		if (filterActive == active)
		{
			return;
		}
		filterActive = active;
		clientThread.invoke(() ->
		{
			refreshToggleAppearance();
			if (filterActive)
			{
				applyFilter();
			}
			else
			{
				unhideAllItems();
			}
		});
	}

	public void onTaskStateChanged()
	{
		clientThread.invoke(() ->
		{
			if (!taskState.hasFocus() && filterActive)
			{
				filterActive = false;
				unhideAllItems();
			}
			refreshToggleAppearance();
			if (filterActive)
			{
				applyFilter();
			}
		});
	}

	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		if (event.getGroupId() != InterfaceID.BANKMAIN)
		{
			return;
		}

		if (!config.bankFilterEnabled())
		{
			return;
		}

		// Bank widgets are re-created on each open — the previous reference is stale.
		toggleWidget = null;

		if (config.autoApplyBankFilter() && taskState.hasFocus())
		{
			filterActive = true;
		}

		clientThread.invoke(this::injectToggleWidget);
	}

	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		if (event.getScriptId() != ScriptID.BANKMAIN_FINISHBUILDING)
		{
			return;
		}

		refreshToggleAppearance();

		if (filterActive)
		{
			applyFilter();
		}
	}

	private void injectToggleWidget()
	{
		try
		{
			Widget parent = client.getWidget(InterfaceID.Bankmain.UNIVERSE);
			if (parent == null)
			{
				return;
			}

			Widget child = parent.createChild(-1, WidgetType.GRAPHIC);
			child.setName("Slayer Codex Filter");
			child.setItemId(ItemID.SLAYER_HELMET_I);
			child.setItemQuantity(0);
			child.setItemQuantityMode(0);
			child.setOriginalX(TOGGLE_X);
			child.setOriginalY(TOGGLE_Y);
			child.setOriginalWidth(TOGGLE_SIZE);
			child.setOriginalHeight(TOGGLE_SIZE);
			child.setHasListener(true);
			child.setNoClickThrough(true);
			child.setAction(0, "Toggle Slayer filter");
			child.setOnOpListener((JavaScriptCallback) ev -> handleToggle());
			child.revalidate();

			toggleWidget = child;
			refreshToggleAppearance();
		}
		catch (Throwable ex)
		{
			log.debug("Could not inject Slayer Codex bank toggle", ex);
		}
	}

	private void handleToggle()
	{
		if (!taskState.hasFocus())
		{
			filterActive = false;
			refreshToggleAppearance();
			return;
		}

		filterActive = !filterActive;
		refreshToggleAppearance();
		if (filterActive)
		{
			applyFilter();
		}
		else
		{
			unhideAllItems();
		}
	}

	private void refreshToggleAppearance()
	{
		Widget widget = toggleWidget;
		if (widget == null)
		{
			return;
		}

		try
		{
			boolean canActivate = taskState.hasFocus();
			int opacity;
			String tooltip;
			if (!canActivate)
			{
				opacity = OPACITY_DISABLED;
				tooltip = "Slayer Codex: detect or pick a Slayer task to enable";
			}
			else if (filterActive)
			{
				opacity = OPACITY_ON;
				tooltip = "Slayer Codex filter ON — showing only items for " + taskState.getMonsterName();
			}
			else
			{
				opacity = OPACITY_OFF;
				tooltip = "Slayer Codex filter OFF — click to show only items for " + taskState.getMonsterName();
			}

			widget.setOpacity(opacity);
			widget.setAction(0, tooltip);
		}
		catch (Throwable ignored)
		{
		}
	}

	private void applyFilter()
	{
		try
		{
			Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
			if (container == null)
			{
				return;
			}

			java.util.Set<Integer> allowed = taskState.getRelevantItemIds();
			if (allowed.isEmpty())
			{
				return;
			}

			Widget[] children = container.getDynamicChildren();
			if (children == null)
			{
				return;
			}

			savedPositions.clear();

			List<Widget> kept = new ArrayList<>();
			int leftmostX = Integer.MAX_VALUE;
			int topmostY = Integer.MAX_VALUE;

			for (Widget child : children)
			{
				if (child == null)
				{
					continue;
				}

				int rawId = child.getItemId();
				if (rawId <= 0)
				{
					// Tab dividers / separators — hide while filter is on so kept items pack cleanly.
					savedPositions.put(child, new int[]{child.getOriginalX(), child.getOriginalY()});
					child.setHidden(true);
					continue;
				}

				savedPositions.put(child, new int[]{child.getOriginalX(), child.getOriginalY()});
				leftmostX = Math.min(leftmostX, child.getOriginalX());
				topmostY = Math.min(topmostY, child.getOriginalY());

				int canonicalId = itemManager.canonicalize(rawId);
				boolean keep = allowed.contains(canonicalId) || allowed.contains(rawId);
				if (keep)
				{
					kept.add(child);
					child.setHidden(false);
				}
				else
				{
					child.setHidden(true);
				}
			}

			if (kept.isEmpty() || leftmostX == Integer.MAX_VALUE)
			{
				return;
			}

			// Repack kept items into a contiguous grid anchored at the bank's top-left.
			for (int i = 0; i < kept.size(); i++)
			{
				Widget item = kept.get(i);
				int row = i / ITEMS_PER_ROW;
				int col = i % ITEMS_PER_ROW;
				item.setOriginalX(leftmostX + col * ITEM_STRIDE_X);
				item.setOriginalY(topmostY + row * ITEM_STRIDE_Y);
				item.revalidate();
			}
		}
		catch (Throwable ex)
		{
			log.debug("Slayer Codex bank filter render failed", ex);
		}
	}

	private void unhideAllItems()
	{
		try
		{
			Widget container = client.getWidget(InterfaceID.Bankmain.ITEMS);
			if (container == null)
			{
				savedPositions.clear();
				return;
			}

			// Restore original positions for any widget we moved or hid.
			for (Map.Entry<Widget, int[]> entry : savedPositions.entrySet())
			{
				Widget w = entry.getKey();
				int[] xy = entry.getValue();
				try
				{
					w.setOriginalX(xy[0]);
					w.setOriginalY(xy[1]);
					w.setHidden(false);
					w.revalidate();
				}
				catch (Throwable ignored)
				{
				}
			}
			savedPositions.clear();

			// Catch any children that weren't tracked (e.g. added after filter applied).
			Widget[] children = container.getDynamicChildren();
			if (children == null)
			{
				return;
			}
			for (Widget child : children)
			{
				if (child == null || child.getItemId() <= 0)
				{
					continue;
				}
				child.setHidden(false);
			}
		}
		catch (Throwable ex)
		{
			log.debug("Slayer Codex bank unhide failed", ex);
		}
	}
}
