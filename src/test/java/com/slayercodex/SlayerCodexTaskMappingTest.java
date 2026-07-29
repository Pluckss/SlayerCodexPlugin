package com.slayercodex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Locks down task-name → monster-key resolution against the full list of Slayer tasks that
 * masters can assign (including Mortimer's superior-variant list).
 *
 * <p>The rule this suite enforces: resolving to the WRONG monster is a hard failure, while
 * resolving to null is acceptable — the panel handles "no strategy bundled" gracefully by
 * showing the task name and a wiki link. Bad gear advice is worse than no gear advice.
 *
 * <p>Every entry below is an exact expectation. When new monster data is crawled, entries
 * flip from null to a key and this test is the place that records it.
 */
public class SlayerCodexTaskMappingTest
{
	private static SlayerCodexDataStore store;

	/** Task name → expected monster key with the "prefer boss variant" setting OFF. */
	private static final Map<String, String> EXPECTED_BASE = buildExpectedBase();

	@BeforeClass
	public static void loadStore() throws IOException
	{
		store = new SlayerCodexDataStore();
		store.load();
	}

	private static Map<String, String> buildExpectedBase()
	{
		Map<String, String> e = new LinkedHashMap<>();

		// --- Tasks with a directly matching strategy page ---------------------------------
		e.put("Abyssal demons", "abyssal_demons");
		e.put("Aquanites", "aquanites");
		e.put("Aviansie", "aviansie");
		e.put("Custodian stalkers", "custodian_stalker");
		e.put("Dark beasts", "dark_beast");
		e.put("Drakes", "drake");
		e.put("Dust devils", "dust_devils");
		e.put("Fossil Island Wyverns", "fossil_island_wyverns");
		e.put("Frost dragons", "frost_dragon");
		e.put("Gargoyles", "gargoyles");
		e.put("Green dragons", "green_dragon");
		e.put("Jellies", "jellies");
		e.put("Kurasks", "kurasks");
		e.put("Lava dragons", "lava_dragon");
		e.put("Metal dragons", "metal_dragons");
		e.put("Nechryael", "nechryael");
		e.put("Revenants", "revenants");
		e.put("Skeletal Wyverns", "skeletal_wyvern");
		e.put("Smoke devils", "smoke_devil");
		e.put("Suqahs", "suqah");
		e.put("Trolls", "trolls");
		e.put("Waterfiends", "waterfiends");
		e.put("Wyrms", "wyrms");
		// Added by the Slayer task/* crawl pass (slayer_task_crawler.py).
		e.put("Cave horrors", "cave_horrors");
		e.put("Gryphons", "gryphons");
		e.put("Turoth", "turoth");
		e.put("Warped creatures", "warped_creatures");

		// --- Explicit aliases (different page name, same task) ----------------------------
		e.put("Basilisks", "basilisk_knight");

		// --- No base strategy, but an on-task boss exists ---------------------------------
		e.put("Bears", "callisto");
		e.put("Black dragons", "king_black_dragon");
		e.put("Blue dragons", "vorkath");
		e.put("Cave kraken", "kraken");
		e.put("Dagannoth", "dagannoth_kings");
		e.put("Greater demons", "kril_tsutsaroth");
		e.put("Hellhounds", "cerberus");
		e.put("Kalphites", "kalphite_queen");
		e.put("Lizardmen", "lizardman_shaman");
		e.put("Scorpions", "scorpia");
		e.put("Skeletons", "vetion");
		e.put("Spiders", "sarachnis");
		e.put("Vampyres", "vyrewatch_sentinel");

		// --- Not bundled yet. MUST resolve to null, never to a lookalike. -----------------
		// Regression anchors for shipped mis-mappings:
		//   Hydras -> Alchemical Hydra, Rats -> Barbarian Assault,
		//   Birds -> Chompy bird hunting, Monkeys -> Maniacal monkey, Lizards -> Lizardman shaman
		for (String unmapped : new String[]{
			"Aberrant spectres", "Ankou", "Araxytes", "Bandits", "Banshees", "Bats",
			"Birds", "Black Knights", "Bloodvelds", "Brine rats", "Catablepon", "Cave bugs",
			"Cave crawlers", "Cave slimes", "Chaos druids", "Cockatrices",
			"Cows", "Crabs", "Crawling Hands", "Crocodiles", "Dark warriors", "Dogs",
			"Dwarves", "Earth Warriors", "Elves", "Ents", "Fever spiders", "Fire giants",
			"Flesh Crawlers", "Ghosts", "Ghouls", "Goblins", "Harpie bug swarms",
			"Hill Giants", "Hobgoblins", "Hydras", "Icefiends", "Ice giants", "Ice warriors",
			"Infernal Mages", "Jungle horrors", "Killerwatts", "Lesser demons",
			"Lesser Nagua", "Lizards", "Magic axes", "Mammoths", "Minotaurs", "Mogres",
			"Molanisks", "Monkeys", "Moss giants", "Ogres", "Otherworldly beings", "Pirates",
			"Pyrefiends", "Rats", "Red dragons", "Rockslugs", "Rogues", "Scabarites",
			"Sea snakes", "Shades", "Shadow warriors", "Sourhogs", "Spiritual creatures",
			"Terror dogs", "TzHaar", "Venators", "Wall Beasts",
			"Werewolves", "Wolves", "Zombies", "Zygomites"})
		{
			e.put(unmapped, null);
		}

		return e;
	}

	/**
	 * Mortimer's assignable list, spelled exactly as the game's own SlayerTask DB table spells
	 * it (table 113, referenced from SlayerMasterTask table 114, master id 10 — read out of the
	 * live cache on 2026-07-29). These are the literal strings COL_NAME_UPPERCASE hands to
	 * {@link SlayerCodexDataStore#findBestMonsterKeyForTask}, and several differ from the
	 * conventional wiki spelling: "Kurask", "Cockatrice" and "Bloodveld" are singular in-game.
	 */
	private static Map<String, String> buildMortimerTasks()
	{
		Map<String, String> e = new LinkedHashMap<>();
		e.put("Crawling Hands", null);
		e.put("Cave Crawlers", null);
		e.put("Banshees", null);
		e.put("Rockslugs", null);
		e.put("Cockatrice", null);
		e.put("Pyrefiends", null);
		e.put("Infernal Mages", null);
		e.put("Bloodveld", null);
		e.put("Gryphons", "gryphons");
		e.put("Jellies", "jellies");
		e.put("Custodian Stalkers", "custodian_stalker");
		e.put("Turoth", "turoth");
		e.put("Warped Creatures", "warped_creatures");
		e.put("Cave Horrors", "cave_horrors");
		e.put("Aberrant Spectres", null);
		e.put("Basilisks", "basilisk_knight");
		e.put("Wyrms", "wyrms");
		e.put("Dust Devils", "dust_devils");
		e.put("Kurask", "kurasks");
		e.put("Venators", null);
		e.put("Gargoyles", "gargoyles");
		e.put("Aquanites", "aquanites");
		e.put("Nechryael", "nechryael");
		e.put("Drakes", "drake");
		e.put("Abyssal Demons", "abyssal_demons");
		e.put("Dark Beasts", "dark_beast");
		e.put("Araxytes", null);
		e.put("Smoke Devils", "smoke_devil");
		e.put("Hydras", null);
		return e;
	}

	/**
	 * Mortimer (added 2026-07-29) is the only master that assigns Venators, and he spells
	 * several long-standing tasks differently to the wiki. Detection feeds these exact strings
	 * in, so they get their own lock separate from the wiki-spelled list above.
	 */
	@Test
	public void mortimerTaskListResolvesUsingTheGamesOwnSpellings()
	{
		List<String> failures = new ArrayList<>();
		for (Map.Entry<String, String> entry : buildMortimerTasks().entrySet())
		{
			String actual = store.findBestMonsterKeyForTask(entry.getKey(), false);
			String expected = entry.getValue();
			if (expected == null ? actual != null : !expected.equals(actual))
			{
				failures.add(String.format(
					"  %-24s expected %-24s but got %s",
					entry.getKey(), String.valueOf(expected), String.valueOf(actual)));
			}
		}

		Assert.assertTrue(
			"Mortimer task mapping regressions (" + failures.size() + "):\n"
				+ String.join("\n", failures),
			failures.isEmpty());
	}

	@Test
	public void everySlayerTaskResolvesToTheExpectedMonsterOrNull()
	{
		List<String> failures = new ArrayList<>();
		for (Map.Entry<String, String> entry : EXPECTED_BASE.entrySet())
		{
			String task = entry.getKey();
			String expected = entry.getValue();
			String actual = store.findBestMonsterKeyForTask(task, false);

			if (expected == null ? actual != null : !expected.equals(actual))
			{
				failures.add(String.format(
					"  %-24s expected %-24s but got %s",
					task, String.valueOf(expected), String.valueOf(actual)));
			}
		}

		Assert.assertTrue(
			"Task mapping regressions (" + failures.size() + "):\n" + String.join("\n", failures),
			failures.isEmpty());
	}

	@Test
	public void resolvedMonsterKeysAlwaysHaveLoadableDetails()
	{
		List<String> failures = new ArrayList<>();
		for (String task : EXPECTED_BASE.keySet())
		{
			for (boolean preferBoss : new boolean[]{false, true})
			{
				String key = store.findBestMonsterKeyForTask(task, preferBoss);
				if (key != null && store.getMonsterDetails(key) == null)
				{
					failures.add(task + " (preferBoss=" + preferBoss + ") -> dangling key " + key);
				}
			}
		}

		Assert.assertTrue("Dangling keys:\n" + String.join("\n", failures), failures.isEmpty());
	}

	/**
	 * With the setting on, tasks that have a boss variant must switch to the boss; tasks that
	 * do not must return exactly what they returned with the setting off.
	 */
	@Test
	public void preferBossVariantUpgradesOnlyTasksThatHaveABoss()
	{
		Map<String, String> bossUpgrades = new LinkedHashMap<>();
		bossUpgrades.put("Gargoyles", "grotesque_guardians");
		bossUpgrades.put("Abyssal demons", "abyssal_sire");
		bossUpgrades.put("Smoke devils", "thermonuclear_smoke_devil");
		bossUpgrades.put("Aviansie", "kreearra");

		List<String> failures = new ArrayList<>();
		for (Map.Entry<String, String> entry : bossUpgrades.entrySet())
		{
			String actual = store.findBestMonsterKeyForTask(entry.getKey(), true);
			if (!entry.getValue().equals(actual))
			{
				failures.add(entry.getKey() + " expected " + entry.getValue() + " but got " + actual);
			}
		}

		// Tasks with no boss variant must be unaffected by the toggle.
		for (String task : new String[]{"Kurasks", "Wyrms", "Jellies", "Hydras", "Rats"})
		{
			String off = store.findBestMonsterKeyForTask(task, false);
			String on = store.findBestMonsterKeyForTask(task, true);
			if (off == null ? on != null : !off.equals(on))
			{
				failures.add(task + " changed with preferBossVariant: " + off + " -> " + on);
			}
		}

		Assert.assertTrue("Boss variant failures:\n" + String.join("\n", failures), failures.isEmpty());
	}

	/**
	 * Guards the specific class of bug this suite was written for: a task must never resolve
	 * to a minigame, activity or guide page.
	 */
	@Test
	public void noTaskResolvesToANonCombatPage()
	{
		List<String> nonCombat = List.of(
			"abyss", "barbarian_assault", "barbarian_assault_advanced_strategies",
			"blast_furnace", "chompy_bird_hunting_strategy", "ecumenical_key",
			"fishing_trawler", "giants_foundry", "gout_tuber", "guardians_of_the_rift",
			"guide_forestry_events_meta", "hallowed_sepulchre", "hunters_rumours",
			"krystilia", "tempoross", "treasure_trails", "trouble_brewing", "vale_totems",
			"wintertodt");

		List<String> failures = new ArrayList<>();
		for (String task : EXPECTED_BASE.keySet())
		{
			for (boolean preferBoss : new boolean[]{false, true})
			{
				String key = store.findBestMonsterKeyForTask(task, preferBoss);
				if (key != null && nonCombat.contains(key))
				{
					failures.add(task + " -> " + key);
				}
			}
		}

		Assert.assertTrue(
			"Tasks resolving to non-combat pages:\n" + String.join("\n", failures),
			failures.isEmpty());
	}
}
