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
		keyName = "showSlayerXp",
		name = "Show Slayer XP per kill",
		description = "Display the expected Slayer XP per kill in the monster header.",
		section = displaySection,
		position = 0
	)
	default boolean showSlayerXp()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTaskCountdown",
		name = "Show task remaining count",
		description = "Show how many kills are left when a task is detected.",
		section = displaySection,
		position = 1
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
		position = 2
	)
	default boolean compactGearTable()
	{
		return false;
	}

	@ConfigItem(
		keyName = "showBossVariantBadge",
		name = "Show boss variant badge",
		description = "Show a badge in the monster header when a boss variant of the current task exists.",
		section = displaySection,
		position = 3
	)
	default boolean showBossVariantBadge()
	{
		return true;
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
		name = "Highlight owned items",
		description = "Show a green tint on items in Your Best column if you own them.",
		section = recommendSection,
		position = 1
	)
	default boolean highlightOwnedItems()
	{
		return true;
	}

	enum StylePreference
	{
		AUTO, MELEE, RANGED, MAGIC
	}
}
