package com.slayercodex;

import org.junit.Assert;
import org.junit.Test;

/**
 * Regression tests for the chat/dialog task parser using realistic in-game messages.
 */
public class SlayerCodexTaskParserTest
{
	@Test
	public void parsesSlayerMasterAssignment()
	{
		SlayerCodexPlugin.TaskUpdate update =
			SlayerCodexPlugin.parseTaskUpdate("Your new task is to kill 231 Suqahs.");
		Assert.assertNotNull(update);
		Assert.assertEquals("Suqahs", update.taskName);
		Assert.assertEquals(Integer.valueOf(231), update.remaining);
	}

	@Test
	public void parsesAssignedToSlayVariant()
	{
		SlayerCodexPlugin.TaskUpdate update =
			SlayerCodexPlugin.parseTaskUpdate("You have been assigned to slay 100 trolls.");
		Assert.assertNotNull(update);
		Assert.assertEquals("Trolls", update.taskName);
		Assert.assertEquals(Integer.valueOf(100), update.remaining);
	}

	@Test
	public void parsesGemCheckRemainingCount()
	{
		SlayerCodexPlugin.TaskUpdate update =
			SlayerCodexPlugin.parseTaskUpdate("You're assigned to kill Abyssal demons; only 45 more to go.");
		Assert.assertNotNull(update);
		Assert.assertEquals("Abyssal Demons", update.taskName);
		Assert.assertEquals(Integer.valueOf(45), update.remaining);
	}

	@Test
	public void stripsLocationSuffixFromTaskName()
	{
		SlayerCodexPlugin.TaskUpdate update =
			SlayerCodexPlugin.parseTaskUpdate("You're assigned to kill fossil island wyverns in the Wyvern Cave, 62 to go.");
		Assert.assertNotNull(update);
		Assert.assertEquals("Fossil Island Wyverns", update.taskName);
		Assert.assertEquals(Integer.valueOf(62), update.remaining);
	}

	@Test
	public void parsesNewSlayerAssignmentColonFormat()
	{
		SlayerCodexPlugin.TaskUpdate update =
			SlayerCodexPlugin.parseTaskUpdate("New Slayer assignment: Kalphite (40)");
		Assert.assertNotNull(update);
		Assert.assertEquals("Kalphite", update.taskName);
		Assert.assertEquals(Integer.valueOf(40), update.remaining);
	}

	@Test
	public void ignoresUnrelatedMessages()
	{
		Assert.assertNull(SlayerCodexPlugin.parseTaskUpdate("You need level 85 Slayer to harm these creatures."));
		Assert.assertNull(SlayerCodexPlugin.parseTaskUpdate("Welcome to Old School RuneScape."));
	}

	@Test
	public void cleanupTitleCasesAndTrimsPunctuation()
	{
		Assert.assertEquals("Black Demons", SlayerCodexPlugin.cleanupTaskName("black demons."));
		Assert.assertEquals("Blue Dragons", SlayerCodexPlugin.cleanupTaskName("Blue dragons at Taverley Dungeon"));
		Assert.assertNull(SlayerCodexPlugin.cleanupTaskName("   "));
	}
}
