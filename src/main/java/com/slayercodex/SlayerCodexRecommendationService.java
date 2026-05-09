package com.slayercodex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
public class SlayerCodexRecommendationService
{
	private final SlayerCodexItemResolver itemResolver;
	private final SlayerCodexOwnershipTracker ownershipTracker;

	@Inject
	public SlayerCodexRecommendationService(
		SlayerCodexItemResolver itemResolver,
		SlayerCodexOwnershipTracker ownershipTracker)
	{
		this.itemResolver = itemResolver;
		this.ownershipTracker = ownershipTracker;
	}

	public List<RecommendationRow> buildRows(
		String monsterKey,
		SlayerCodexDataStore.CombatStyleDetails style)
	{
		if (style == null)
		{
			return Collections.emptyList();
		}

		Map<String, SlotAccumulator> slotMap = new LinkedHashMap<>();
		List<Integer> orderedTiers = new ArrayList<>(style.getTierRows().keySet());
		orderedTiers.sort(Integer::compareTo);

		for (Integer tier : orderedTiers)
		{
			for (SlayerCodexDataStore.GearRow row : style.getTierRows().getOrDefault(tier, Collections.emptyList()))
			{
				slotMap.computeIfAbsent(row.getSlot(), ignored -> new SlotAccumulator())
					.rowsByTier.putIfAbsent(tier, row);
			}
		}

		List<String> orderedSlots = new ArrayList<>(slotMap.keySet());
		orderedSlots.sort(this::compareSlot);

		List<RecommendationRow> rows = new ArrayList<>();
		for (String slot : orderedSlots)
		{
			SlotAccumulator acc = slotMap.get(slot);
			RecommendationCell wiki = buildWikiCell(acc);
			RecommendationCell bestOwned = buildBestOwnedCell(acc);
			rows.add(new RecommendationRow(slot, wiki, bestOwned, acc.collectNotes()));
		}
		return rows;
	}

	public String buildBestSetupStatus()
	{
		return ownershipTracker.isBankKnown()
			? "Your Best uses equipped, inventory and every bank item seen this session."
			: "Your Best uses equipped and inventory items until you open the bank once this session.";
	}

	/**
	 * Collects every resolvable item ID across every build/style/tier/slot for a monster.
	 * Used by the bank filter and item overlay to know which items belong to a task.
	 */
	public Set<Integer> collectAllRelevantItemIds(SlayerCodexDataStore.MonsterDetails details)
	{
		if (details == null)
		{
			return Collections.emptySet();
		}

		LinkedHashSet<Integer> ids = new LinkedHashSet<>();
		for (SlayerCodexDataStore.CombatStyleDetails style : details.getCombatStyles())
		{
			if (style == null)
			{
				continue;
			}
			for (List<SlayerCodexDataStore.GearRow> tierRows : style.getTierRows().values())
			{
				if (tierRows == null)
				{
					continue;
				}
				for (SlayerCodexDataStore.GearRow row : tierRows)
				{
					if (row == null)
					{
						continue;
					}
					ids.addAll(itemResolver.resolveItemIds(row.getItemName(), row.getAltName()));
				}
			}
		}
		return ids.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(ids);
	}

	private RecommendationCell buildWikiCell(SlotAccumulator acc)
	{
		SlayerCodexDataStore.GearRow row = acc.rowsByTier.values().stream().findFirst().orElse(null);
		if (row == null)
		{
			return RecommendationCell.placeholder("-");
		}
		return buildCell(row, false);
	}

	private RecommendationCell buildBestOwnedCell(SlotAccumulator acc)
	{
		for (SlayerCodexDataStore.GearRow row : acc.rowsByTier.values())
		{
			RecommendationCell candidate = buildCell(row, true);
			if (candidate.isOwned())
			{
				return candidate;
			}
		}

		SlayerCodexDataStore.GearRow fallback = acc.rowsByTier.values().stream().findFirst().orElse(null);
		if (fallback == null)
		{
			return RecommendationCell.placeholder("No matching gear");
		}

		RecommendationCell cell = buildCell(fallback, true);
		return RecommendationCell.unavailable(cell.getLabel(), cell.getItemId());
	}

	private RecommendationCell buildCell(SlayerCodexDataStore.GearRow row, boolean includeOwnership)
	{
		List<Integer> candidateIds = itemResolver.resolveItemIds(row.getItemName(), row.getAltName());
		int itemId = candidateIds.isEmpty() ? itemResolver.resolveDisplayItemId(row.getItemName(), row.getAltName()) : candidateIds.get(0);
		String label = buildLabel(row);

		if (!includeOwnership)
		{
			return RecommendationCell.available(label, itemId, false, false, false);
		}

		Optional<Integer> ownedMatch = candidateIds.stream()
			.filter(id -> ownershipTracker.getOwnedQuantity(id) > 0)
			.findFirst();

		if (ownedMatch.isPresent())
		{
			int matchedId = ownedMatch.get();
			return RecommendationCell.available(
				label,
				matchedId,
				true,
				ownershipTracker.isEquipped(matchedId),
				ownershipTracker.isInBank(matchedId));
		}

		return RecommendationCell.available(label, itemId, false, false, false);
	}

	private String buildLabel(SlayerCodexDataStore.GearRow row)
	{
		if (row.getAltName() == null || row.getAltName().trim().isEmpty())
		{
			return row.getItemName();
		}
		return row.getItemName() + " (" + row.getAltName() + ")";
	}

	private int compareSlot(String left, String right)
	{
		List<String> order = List.of("HEAD", "NECK", "CAPE", "BODY", "LEGS", "WEAPON", "SHIELD", "AMMO", "HANDS", "FEET", "RING", "SPECIAL");
		int leftIdx = order.indexOf(left);
		int rightIdx = order.indexOf(right);
		if (leftIdx == -1 && rightIdx == -1)
		{
			return left.compareTo(right);
		}
		if (leftIdx == -1)
		{
			return 1;
		}
		if (rightIdx == -1)
		{
			return -1;
		}
		return Integer.compare(leftIdx, rightIdx);
	}

	public static final class RecommendationRow
	{
		private final String slot;
		private final RecommendationCell wiki;
		private final RecommendationCell yourBest;
		private final List<String> noteIds;

		private RecommendationRow(
			String slot,
			RecommendationCell wiki,
			RecommendationCell yourBest,
			List<String> noteIds)
		{
			this.slot = slot;
			this.wiki = wiki;
			this.yourBest = yourBest;
			this.noteIds = noteIds;
		}

		public String getSlot()
		{
			return slot;
		}

		public RecommendationCell getWiki()
		{
			return wiki;
		}

		public RecommendationCell getYourBest()
		{
			return yourBest;
		}

		public List<String> getNoteIds()
		{
			return noteIds;
		}
	}

	public static final class RecommendationCell
	{
		private final String label;
		private final int itemId;
		private final boolean owned;
		private final boolean equipped;
		private final boolean banked;
		private final boolean unavailableFallback;

		private RecommendationCell(String label, int itemId, boolean owned, boolean equipped, boolean banked, boolean unavailableFallback)
		{
			this.label = label;
			this.itemId = itemId;
			this.owned = owned;
			this.equipped = equipped;
			this.banked = banked;
			this.unavailableFallback = unavailableFallback;
		}

		public static RecommendationCell available(String label, int itemId, boolean owned, boolean equipped, boolean banked)
		{
			return new RecommendationCell(label, itemId, owned, equipped, banked, false);
		}

		public static RecommendationCell unavailable(String label, int itemId)
		{
			return new RecommendationCell(label, itemId, false, false, false, true);
		}

		public static RecommendationCell placeholder(String label)
		{
			return new RecommendationCell(label, -1, false, false, false, false);
		}

		public String getLabel()
		{
			return label;
		}

		public int getItemId()
		{
			return itemId;
		}

		public boolean isOwned()
		{
			return owned;
		}

		public boolean isEquipped()
		{
			return equipped;
		}

		public boolean isBanked()
		{
			return banked;
		}

		public boolean isUnavailableFallback()
		{
			return unavailableFallback;
		}

		@Override
		public String toString()
		{
			return label;
		}
	}

	private static final class SlotAccumulator
	{
		private final Map<Integer, SlayerCodexDataStore.GearRow> rowsByTier = new LinkedHashMap<>();

		private List<String> collectNotes()
		{
			TreeSet<String> noteIds = new TreeSet<>();
			for (SlayerCodexDataStore.GearRow row : rowsByTier.values())
			{
				noteIds.addAll(row.getNoteIds());
			}
			return new ArrayList<>(noteIds);
		}
	}
}