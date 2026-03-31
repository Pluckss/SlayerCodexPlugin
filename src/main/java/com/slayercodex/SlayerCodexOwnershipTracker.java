package com.slayercodex;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.game.ItemManager;

@Singleton
public class SlayerCodexOwnershipTracker
{
	private final Client client;
	private final ItemManager itemManager;

	private Map<Integer, Integer> inventoryCounts = Collections.emptyMap();
	private Map<Integer, Integer> equippedCounts = Collections.emptyMap();
	private Map<Integer, Integer> bankCounts = Collections.emptyMap();
	private boolean bankKnown;

	@Inject
	public SlayerCodexOwnershipTracker(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	public void initializeFromClient()
	{
		capture(InventoryID.INV, client.getItemContainer(InventoryID.INV));
		capture(InventoryID.WORN, client.getItemContainer(InventoryID.WORN));
		capture(InventoryID.BANK, client.getItemContainer(InventoryID.BANK));
	}

	public void capture(int containerId, ItemContainer container)
	{
		Map<Integer, Integer> snapshot = snapshot(container);
		if (containerId == InventoryID.INV)
		{
			inventoryCounts = snapshot;
		}
		else if (containerId == InventoryID.WORN)
		{
			equippedCounts = snapshot;
		}
		else if (containerId == InventoryID.BANK)
		{
			bankCounts = snapshot;
			bankKnown = container != null;
		}
	}

	public int getOwnedQuantity(int itemId)
	{
		return inventoryCounts.getOrDefault(itemId, 0)
			+ equippedCounts.getOrDefault(itemId, 0)
			+ bankCounts.getOrDefault(itemId, 0);
	}

	public boolean isEquipped(int itemId)
	{
		return equippedCounts.getOrDefault(itemId, 0) > 0;
	}

	public boolean isInBank(int itemId)
	{
		return bankCounts.getOrDefault(itemId, 0) > 0;
	}

	public boolean isInInventory(int itemId)
	{
		return inventoryCounts.getOrDefault(itemId, 0) > 0;
	}

	public boolean isBankKnown()
	{
		return bankKnown;
	}

	private Map<Integer, Integer> snapshot(ItemContainer container)
	{
		if (container == null)
		{
			return Collections.emptyMap();
		}

		Map<Integer, Integer> counts = new LinkedHashMap<>();
		for (Item item : container.getItems())
		{
			if (item == null || item.getId() <= 0)
			{
				continue;
			}

			int canonicalId = itemManager.canonicalize(item.getId());
			if (canonicalId <= 0)
			{
				continue;
			}

			counts.merge(canonicalId, Math.max(item.getQuantity(), 1), Integer::sum);
		}
		return Collections.unmodifiableMap(counts);
	}
}