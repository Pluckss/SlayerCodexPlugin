package com.slayercodex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.client.game.ItemManager;

@Singleton
public class SlayerCodexItemResolver
{
	private final Client client;
	private final ItemManager itemManager;
	private final SlayerCodexDataStore dataStore;

	// Written once by the client thread in warmUp(), read lock-free from the EDT.
	// Volatile immutable-map swap means lookups never block while the index builds
	// and never trigger item-cache access from the wrong thread.
	private volatile Map<String, List<Integer>> itemIdsByName = Collections.emptyMap();
	private volatile boolean indexed;

	@Inject
	public SlayerCodexItemResolver(Client client, ItemManager itemManager, SlayerCodexDataStore dataStore)
	{
		this.client = client;
		this.itemManager = itemManager;
		this.dataStore = dataStore;
	}

	public List<Integer> resolveItemIds(String itemName, String altName)
	{
		Map<String, List<Integer>> index = itemIdsByName;
		LinkedHashSet<Integer> ids = new LinkedHashSet<>();
		addMatches(index, ids, itemName);
		addMatches(index, ids, altName);
		return ids.isEmpty() ? Collections.emptyList() : new ArrayList<>(ids);
	}

	public int resolveDisplayItemId(String itemName, String altName)
	{
		List<Integer> ids = resolveItemIds(itemName, altName);
		return ids.isEmpty() ? -1 : ids.get(0);
	}

	public boolean isIndexed()
	{
		return indexed;
	}

	/**
	 * Builds the name → item-id index if it hasn't been built yet. Must be called on the
	 * client thread (reads item compositions). Safe to call multiple times; skips the work
	 * once the index was built successfully. If the item cache is not yet loaded, leaves
	 * {@code indexed} false so a future call can retry.
	 *
	 * <p>Only names the bundled dataset can actually ask about are kept (~900 of the game's
	 * ~30k items). The full scan still has to happen — item compositions are the only way to
	 * map a name back to an id — but the retained map is a fraction of the size, and the
	 * lookups it serves are exactly the ones {@link #resolveItemIds} can issue.
	 */
	public synchronized void warmUp()
	{
		if (indexed)
		{
			return;
		}

		int itemCount = client.getItemCount();
		if (itemCount <= 0)
		{
			// Item cache not yet loaded (e.g. pre-login screen). Try again on the next call.
			return;
		}

		Set<String> wanted = buildWantedVariants();
		if (wanted.isEmpty())
		{
			// Strategy data hasn't loaded yet — retry once it has rather than caching an
			// index that would filter everything out.
			return;
		}

		Map<String, LinkedHashSet<Integer>> resolved = new LinkedHashMap<>();
		for (int itemId = 0; itemId < itemCount; itemId++)
		{
			int canonicalId = itemManager.canonicalize(itemId);
			ItemComposition item = itemManager.getItemComposition(canonicalId);
			if (item == null)
			{
				continue;
			}

			registerName(resolved, wanted, item.getName(), canonicalId);
			registerName(resolved, wanted, item.getMembersName(), canonicalId);
		}

		if (resolved.isEmpty())
		{
			// Nothing got registered (item compositions all returned null — likely wrong thread).
			// Don't mark indexed; let a later call try again.
			return;
		}

		Map<String, List<Integer>> immutable = new LinkedHashMap<>();
		for (Map.Entry<String, LinkedHashSet<Integer>> entry : resolved.entrySet())
		{
			immutable.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
		}

		itemIdsByName = Collections.unmodifiableMap(immutable);
		indexed = true;
	}

	private void addMatches(Map<String, List<Integer>> index, Collection<Integer> out, String rawName)
	{
		for (String variant : getLookupVariants(rawName))
		{
			List<Integer> ids = index.get(variant);
			if (ids != null)
			{
				out.addAll(ids);
			}
		}
	}

	/**
	 * The set of normalized names {@link #resolveItemIds} could ever be called with, derived
	 * from the dataset via the same variant expansion used at lookup time — so filtering the
	 * index by it cannot drop a name that would later be queried.
	 */
	private Set<String> buildWantedVariants()
	{
		Set<String> wanted = new LinkedHashSet<>();
		for (String name : dataStore.getAllGearItemNames())
		{
			wanted.addAll(getLookupVariants(name));
		}
		return wanted;
	}

	private void registerName(
		Map<String, LinkedHashSet<Integer>> target,
		Set<String> wanted,
		String rawName,
		int itemId)
	{
		for (String variant : getLookupVariants(rawName))
		{
			if (wanted.contains(variant))
			{
				target.computeIfAbsent(variant, ignored -> new LinkedHashSet<>()).add(itemId);
			}
		}
	}

	private List<String> getLookupVariants(String rawName)
	{
		if (rawName == null)
		{
			return Collections.emptyList();
		}

		LinkedHashSet<String> variants = new LinkedHashSet<>();
		variants.add(normalizeName(rawName));

		String withoutMembers = rawName.replace(" (Members)", "");
		variants.add(normalizeName(withoutMembers));

		int opening = rawName.indexOf('(');
		if (opening > 0)
		{
			variants.add(normalizeName(rawName.substring(0, opening).trim()));
		}

		for (String token : rawName.split("/|\\bor\\b|,"))
		{
			variants.add(normalizeName(token));
		}

		variants.remove("");
		return new ArrayList<>(variants);
	}

	private String normalizeName(String value)
	{
		String lower = value.toLowerCase(Locale.ENGLISH).trim();
		StringBuilder builder = new StringBuilder(lower.length());
		for (int i = 0; i < lower.length(); i++)
		{
			char ch = lower.charAt(i);
			if (Character.isLetterOrDigit(ch))
			{
				builder.append(ch);
			}
		}
		return builder.toString();
	}
}