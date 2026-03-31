package com.slayercodex;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

	private Map<String, List<Integer>> itemIdsByName = Collections.emptyMap();
	private boolean indexed;

	@Inject
	public SlayerCodexItemResolver(Client client, ItemManager itemManager)
	{
		this.client = client;
		this.itemManager = itemManager;
	}

	public synchronized List<Integer> resolveItemIds(String itemName, String altName)
	{
		ensureIndex();

		LinkedHashSet<Integer> ids = new LinkedHashSet<>();
		addMatches(ids, itemName);
		addMatches(ids, altName);
		return ids.isEmpty() ? Collections.emptyList() : new ArrayList<>(ids);
	}

	public int resolveDisplayItemId(String itemName, String altName)
	{
		List<Integer> ids = resolveItemIds(itemName, altName);
		return ids.isEmpty() ? -1 : ids.get(0);
	}

	private void ensureIndex()
	{
		if (indexed)
		{
			return;
		}

		Map<String, LinkedHashSet<Integer>> resolved = new LinkedHashMap<>();
		for (int itemId = 0; itemId < client.getItemCount(); itemId++)
		{
			int canonicalId = itemManager.canonicalize(itemId);
			ItemComposition item = itemManager.getItemComposition(canonicalId);
			if (item == null)
			{
				continue;
			}

			registerName(resolved, item.getName(), canonicalId);
			registerName(resolved, item.getMembersName(), canonicalId);
		}

		Map<String, List<Integer>> immutable = new LinkedHashMap<>();
		for (Map.Entry<String, LinkedHashSet<Integer>> entry : resolved.entrySet())
		{
			immutable.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
		}

		itemIdsByName = Collections.unmodifiableMap(immutable);
		indexed = true;
	}

	private void addMatches(Collection<Integer> out, String rawName)
	{
		for (String variant : getLookupVariants(rawName))
		{
			List<Integer> ids = itemIdsByName.get(variant);
			if (ids != null)
			{
				out.addAll(ids);
			}
		}
	}

	private void registerName(Map<String, LinkedHashSet<Integer>> target, String rawName, int itemId)
	{
		for (String variant : getLookupVariants(rawName))
		{
			target.computeIfAbsent(variant, ignored -> new LinkedHashSet<>()).add(itemId);
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