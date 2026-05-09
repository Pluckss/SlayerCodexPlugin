package com.slayercodex;

import java.util.Collections;
import java.util.Set;
import javax.inject.Singleton;

/**
 * Shared singleton snapshot of the currently focused monster's recommended items,
 * read by the bank filter and item overlay. The plugin pushes updates whenever the
 * panel's selection or auto-detected task changes.
 */
@Singleton
public class SlayerCodexTaskState
{
	private volatile String monsterKey;
	private volatile String monsterName;
	private volatile Set<Integer> relevantItemIds = Collections.emptySet();

	public synchronized void update(String monsterKey, String monsterName, Set<Integer> relevantItemIds)
	{
		this.monsterKey = monsterKey;
		this.monsterName = monsterName;
		this.relevantItemIds = relevantItemIds == null ? Collections.emptySet() : relevantItemIds;
	}

	public synchronized void clear()
	{
		this.monsterKey = null;
		this.monsterName = null;
		this.relevantItemIds = Collections.emptySet();
	}

	public String getMonsterKey()
	{
		return monsterKey;
	}

	public String getMonsterName()
	{
		return monsterName;
	}

	public Set<Integer> getRelevantItemIds()
	{
		return relevantItemIds;
	}

	public boolean hasFocus()
	{
		return monsterKey != null;
	}

	public boolean hasResolvedItems()
	{
		return !relevantItemIds.isEmpty();
	}

	public boolean isRelevant(int itemId)
	{
		return itemId > 0 && relevantItemIds.contains(itemId);
	}
}
