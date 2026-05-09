package com.slayercodex;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup("slayercodex")
public interface SlayerCodexConfig extends Config
{
	@ConfigSection(
		name = "Task Detection",
		description = "Settings related to Slayer task auto-detection",
		position = 0
	)
	String taskSection = "task";

	@ConfigSection(
		name = "Display",
		description = "What to show in the panel",
		position = 1
	)
	String displaySection = "display";

	@ConfigSection(
		name = "Recommendations",
		description = "How gear recommendations are determined",
		position = 2
	)
	String recommendSection = "recommend";

	@ConfigSection(
		name = "Bank & In-game",
		description = "Filter the bank and highlight task-relevant items in the game world",
		position = 3
	)
	String bankSection = "bank";

	// ── Task Detection ─────────────────────────────────────────────────────────

	@ConfigItem(
		keyName = "autoDetectTask",
		name = "Auto-detect Slayer task",
		description = "Automatically switch to the matching strategy when a Slayer assignment is detected in chat.",
		section = taskSection,
		position = 0
	)
	default boolean autoDetectTask()
	{
		return true;
	}

	@ConfigItem(
		keyName = "preferBossVariant",
		name = "Prefer boss variant",
		description = "When a task has a boss variant (e.g. Hellhounds → Cerberus), auto-select the boss page instead.",
		section = taskSection,
		position = 1
	)
	default boolean preferBossVariant()
	{
		return true;
	}

	// ── Display ────────────────────────────────────────────────────────────────

	@ConfigItem(
		keyName = "showTaskCountdown",
		name = "Show task remaining count",
		description = "Show how many kills are left when a task is detected.",
		section = displaySection,
		position = 0
	)
	default boolean showTaskCountdown()
	{
		return true;
	}

	@ConfigItem(
		keyName = "compactGearTable",
		name = "Compact gear table rows",
		description = "Reduces the row height in the gear table so more slots are visible without scrolling.",
		section = displaySection,
		position = 1
	)
	default boolean compactGearTable()
	{
		return false;
	}

	// ── Recommendations ────────────────────────────────────────────────────────

	@ConfigItem(
		keyName = "defaultCombatStyle",
		name = "Preferred combat style",
		description = "Which style tab to auto-select first when opening a monster (Auto = first available).",
		section = recommendSection,
		position = 0
	)
	default StylePreference defaultCombatStyle()
	{
		return StylePreference.AUTO;
	}

	@ConfigItem(
		keyName = "highlightOwnedItems",
		name = "Highlight owned items in panel",
		description = "Color items in the Your Best column based on whether they are equipped, in your inventory, or in your bank.",
		section = recommendSection,
		position = 1
	)
	default boolean highlightOwnedItems()
	{
		return true;
	}

	// ── Bank & In-game ─────────────────────────────────────────────────────────

	@ConfigItem(
		keyName = "bankFilterEnabled",
		name = "Show bank filter button",
		description = "Adds a 'Slayer' toggle to the bank UI that hides items not relevant to your current task.",
		section = bankSection,
		position = 0
	)
	default boolean bankFilterEnabled()
	{
		return true;
	}

	@ConfigItem(
		keyName = "autoApplyBankFilter",
		name = "Auto-filter on bank open",
		description = "Automatically activate the bank filter when you open the bank with a task focused. Toggle off any time via the bank button.",
		section = bankSection,
		position = 1
	)
	default boolean autoApplyBankFilter()
	{
		return false;
	}

	@ConfigItem(
		keyName = "highlightItemsInGame",
		name = "Highlight task items in-game",
		description = "Outlines task-relevant items in your bank, inventory, and equipment so they're easier to spot.",
		section = bankSection,
		position = 2
	)
	default boolean highlightItemsInGame()
	{
		return true;
	}

	enum StylePreference
	{
		AUTO, MELEE, RANGED, MAGIC
	}
}
