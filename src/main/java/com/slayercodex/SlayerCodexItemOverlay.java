package com.slayercodex;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;

/**
 * Outlines items relevant to the currently focused Slayer monster wherever they appear
 * in the UI: bank, inventory, equipped slots. Equipped items get a green tint, banked or
 * inventory items get a blue tint, so the player can spot useful gear without toggling
 * the bank filter.
 */
@Singleton
public class SlayerCodexItemOverlay extends WidgetItemOverlay
{
	private static final Color OUTLINE_RELEVANT = new Color(132, 192, 255, 220);
	private static final Color OUTLINE_EQUIPPED = new Color(120, 223, 145, 220);
	private static final Stroke OUTLINE_STROKE = new BasicStroke(1.4f);

	private final ItemManager itemManager;
	private final SlayerCodexTaskState taskState;
	private final SlayerCodexOwnershipTracker ownershipTracker;
	private final SlayerCodexConfig config;

	@Inject
	public SlayerCodexItemOverlay(
		ItemManager itemManager,
		SlayerCodexTaskState taskState,
		SlayerCodexOwnershipTracker ownershipTracker,
		SlayerCodexConfig config)
	{
		this.itemManager = itemManager;
		this.taskState = taskState;
		this.ownershipTracker = ownershipTracker;
		this.config = config;

		showOnInventory();
		showOnEquipment();
		showOnBank();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!config.highlightItemsInGame())
		{
			return;
		}

		if (!taskState.hasFocus())
		{
			return;
		}

		if (itemId <= 0)
		{
			return;
		}

		int canonicalId = itemManager.canonicalize(itemId);
		if (!taskState.isRelevant(canonicalId) && !taskState.isRelevant(itemId))
		{
			return;
		}

		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}

		Stroke previousStroke = graphics.getStroke();
		Color previousColor = graphics.getColor();
		try
		{
			graphics.setStroke(OUTLINE_STROKE);
			graphics.setColor(ownershipTracker.isEquipped(canonicalId) ? OUTLINE_EQUIPPED : OUTLINE_RELEVANT);
			graphics.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);
		}
		finally
		{
			graphics.setStroke(previousStroke);
			graphics.setColor(previousColor);
		}
	}
}
