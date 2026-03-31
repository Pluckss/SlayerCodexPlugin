package com.slayercodex;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class SlayerCodexDataStoreTest
{
	@Test
	public void loadSupportsBuildBasedSchema() throws IOException
	{
		SlayerCodexDataStore store = new SlayerCodexDataStore();
		store.load();

		Assert.assertTrue("Expected monsters to be loaded", store.getMonsterCount() > 0);

		SlayerCodexDataStore.MonsterDetails hydra = store.getMonsterDetails("alchemical_hydra");
		Assert.assertNotNull("Expected alchemical_hydra details", hydra);
		Assert.assertFalse("Expected combat styles from builds", hydra.getCombatStyles().isEmpty());
	}

	@Test
	public void buildNotesAndSlotNotesResolveToFootnotes() throws IOException
	{
		SlayerCodexDataStore store = new SlayerCodexDataStore();
		store.load();

		SlayerCodexDataStore.MonsterDetails kbd = store.getMonsterDetails("king_black_dragon");
		Assert.assertNotNull("Expected king_black_dragon details", kbd);
		Assert.assertFalse("Expected at least one style", kbd.getCombatStyles().isEmpty());

		SlayerCodexDataStore.CombatStyleDetails firstStyle = kbd.getCombatStyles().get(0);
		Assert.assertFalse("Expected build-level notes for style", firstStyle.getBuildNoteIds().isEmpty());

		List<SlayerCodexDataStore.GearRow> rows = new ArrayList<>();
		for (List<SlayerCodexDataStore.GearRow> tierRows : firstStyle.getTierRows().values())
		{
			rows.addAll(tierRows);
		}

		Assert.assertFalse("Expected gear rows for style", rows.isEmpty());

		List<String> noteIds = new ArrayList<>();
		for (SlayerCodexDataStore.GearRow row : rows)
		{
			noteIds.addAll(row.getNoteIds());
		}

		Assert.assertFalse("Expected item/slot note references", noteIds.isEmpty());

		for (String noteId : noteIds)
		{
			Assert.assertTrue(
				"Missing resolved note text for note id: " + noteId,
				kbd.getFootnotes().containsKey(noteId));
		}
	}

	@Test
	public void allMonstersAndStylesParseWithoutBrokenReferences() throws IOException
	{
		SlayerCodexDataStore store = new SlayerCodexDataStore();
		store.load();

		List<String> failures = new ArrayList<>();
		for (SlayerCodexDataStore.MonsterSummary summary : store.getMonsterSummaries())
		{
			SlayerCodexDataStore.MonsterDetails details = store.getMonsterDetails(summary.getKey());
			if (details == null)
			{
				failures.add(summary.getKey() + ": missing details");
				continue;
			}

			if (details.getCombatStyles().isEmpty())
			{
				failures.add(summary.getKey() + ": no combat styles");
				continue;
			}

			for (SlayerCodexDataStore.CombatStyleDetails style : details.getCombatStyles())
			{
				if (style.getName() == null || style.getName().trim().isEmpty())
				{
					failures.add(summary.getKey() + ": blank style name");
					continue;
				}

				for (String buildNoteId : style.getBuildNoteIds())
				{
					if (!details.getFootnotes().containsKey(buildNoteId))
					{
						failures.add(summary.getKey() + " / " + style.getName() + ": missing build note " + buildNoteId);
					}
				}

				for (Map.Entry<Integer, List<SlayerCodexDataStore.GearRow>> tierEntry : style.getTierRows().entrySet())
				{
					for (SlayerCodexDataStore.GearRow row : tierEntry.getValue())
					{
						if (row.getSlot() == null || row.getSlot().trim().isEmpty())
						{
							failures.add(summary.getKey() + " / " + style.getName() + ": row with blank slot");
						}

						if (row.getItemName() == null || row.getItemName().trim().isEmpty())
						{
							failures.add(summary.getKey() + " / " + style.getName() + ": row with blank item name");
						}

						for (String noteId : row.getNoteIds())
						{
							if (!details.getFootnotes().containsKey(noteId))
							{
								failures.add(summary.getKey() + " / " + style.getName() + ": missing item note " + noteId);
							}
						}
					}
				}
			}
		}

		Assert.assertTrue("Dataset QA failures:\n" + String.join("\n", failures), failures.isEmpty());
	}
}
