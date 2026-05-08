package com.slayercodex;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.inject.Singleton;

@Singleton
public class SlayerCodexDataStore
{
	private JsonObject root;
	private JsonObject monsters;
	private List<MonsterSummary> monsterSummaries = Collections.emptyList();
	private Map<String, String> taskNameToMonsterKey = Collections.emptyMap();
	private Map<String, String> baseTaskToBossKey = Collections.emptyMap();

	public void load() throws IOException
	{
		try (InputStream in = SlayerCodexDataStore.class.getResourceAsStream("/monster_strategies.json"))
		{
			if (in == null)
			{
				throw new IOException("Resource not found: /monster_strategies.json");
			}

			root = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
			monsters = root.getAsJsonObject("monsters");
			monsterSummaries = buildSummaries(monsters);
			taskNameToMonsterKey = buildTaskLookup(monsterSummaries);
			baseTaskToBossKey = buildBossLookup(root);
		}
	}

	public int getMonsterCount()
	{
		return monsterSummaries.size();
	}

	public String getCrawlDate()
	{
		if (root == null)
		{
			return "unknown";
		}

		JsonObject meta = root.getAsJsonObject("_meta");
		if (meta == null || !meta.has("crawl_date"))
		{
			return "unknown";
		}

		return meta.get("crawl_date").getAsString();
	}

	public List<MonsterSummary> getMonsterSummaries()
	{
		return Collections.unmodifiableList(monsterSummaries);
	}

	/**
	 * Returns the boss variant key for a base task key, or null if none exists.
	 * e.g. "gargoyles" -> "grotesque_guardians"
	 */
	public String getBossKeyForBase(String baseKey)
	{
		if (baseKey == null)
		{
			return null;
		}
		String bossKey = baseTaskToBossKey.get(baseKey);
		if (bossKey != null && monsters != null && monsters.has(bossKey))
		{
			return bossKey;
		}
		return null;
	}

	public MonsterDetails getMonsterDetails(String key)
	{
		if (monsters == null || key == null || !monsters.has(key))
		{
			return null;
		}

		JsonObject monster = monsters.getAsJsonObject(key);
		MonsterSummary summary = createSummary(key, monster);
		JsonObject meta = getObject(monster, "meta");

		Map<String, String> footnotes = new LinkedHashMap<>();
		readLegacyFootnotes(monster, footnotes);

		List<CombatStyleDetails> styles = new ArrayList<>();
		JsonArray builds = getArray(monster, "builds");
		if (builds != null && builds.size() > 0)
		{
			for (JsonElement buildElement : builds)
			{
				if (!buildElement.isJsonObject())
				{
					continue;
				}

				JsonObject build = buildElement.getAsJsonObject();
				String styleName = firstNonBlank(
					getString(build, "label"),
					getString(build, "style"),
					getString(build, "id"),
					"Setup");

				String noteNamespace = "build:" + normalize(styleName);
				BuildNoteIndex noteIndex = buildBuildNoteIndex(build, noteNamespace, footnotes);
				Map<Integer, List<GearRow>> tierRows = parseBuildTierRows(build, noteIndex);

				styles.add(new CombatStyleDetails(
					styleName,
					Collections.unmodifiableMap(tierRows),
					noteIndex.getBuildNoteIds()));
			}
		}

		if (styles.isEmpty())
		{
			JsonObject gear = getObject(monster, "gear");
			if (gear != null)
			{
				for (String styleName : summary.getCombatStyles())
				{
					JsonObject styleObject = getObject(gear, styleName);
					if (styleObject == null)
					{
						continue;
					}

					Map<Integer, List<GearRow>> tierToRows = new HashMap<>();
					for (Map.Entry<String, JsonElement> slotEntry : styleObject.entrySet())
					{
						String slot = slotEntry.getKey();
						JsonArray itemArray = getArray(styleObject, slot);
						if (itemArray == null)
						{
							continue;
						}

						for (JsonElement itemElement : itemArray)
						{
							if (!itemElement.isJsonObject())
							{
								continue;
							}

							JsonObject item = itemElement.getAsJsonObject();
							int tier = getInt(item, "tier", 1);
							String itemName = getString(item, "name");
							if (itemName == null)
							{
								continue;
							}

							String altName = getString(item, "alt_name");
							List<String> noteIds = getStringArray(item, "notes");

							GearRow row = new GearRow(
								normalizeSlot(slot),
								itemName,
								altName == null ? "" : altName,
								noteIds
							);

							tierToRows.computeIfAbsent(tier, ignored -> new ArrayList<>()).add(row);
						}
					}

					styles.add(new CombatStyleDetails(
						styleName,
						Collections.unmodifiableMap(sortAndFreezeTierRows(tierToRows)),
						Collections.emptyList()));
				}
			}
		}

		styles.sort(Comparator.comparing(CombatStyleDetails::getName));

		return new MonsterDetails(
			summary,
			Collections.unmodifiableList(styles),
			Collections.unmodifiableMap(footnotes),
			getString(meta, "base_task"),
			getBoolean(meta, "is_boss_variant", false),
			getBoolean(meta, "requires_slayer_task", false)
		);
	}

	private void readLegacyFootnotes(JsonObject monster, Map<String, String> footnotes)
	{
		JsonObject notesObject = getObject(monster, "notes");
		if (notesObject == null)
		{
			return;
		}

		List<String> noteIds = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : notesObject.entrySet())
		{
			if (entry.getValue().isJsonPrimitive())
			{
				noteIds.add(entry.getKey());
			}
		}
		noteIds.sort(String::compareTo);

		for (String noteId : noteIds)
		{
			String text = getString(notesObject, noteId);
			if (text != null && !text.trim().isEmpty())
			{
				footnotes.put(noteId, text);
			}
		}
	}

	private BuildNoteIndex buildBuildNoteIndex(JsonObject build, String namespace, Map<String, String> footnotes)
	{
		BuildNoteIndex index = new BuildNoteIndex();
		JsonObject notesObject = getObject(build, "notes");
		if (notesObject == null)
		{
			return index;
		}

		JsonArray buildNotes = getArray(notesObject, "build_notes");
		if (buildNotes != null)
		{
			int noteCounter = 1;
			for (JsonElement noteElement : buildNotes)
			{
				if (noteElement.isJsonNull())
				{
					continue;
				}

				String text = noteElement.getAsString();
				if (text == null || text.trim().isEmpty())
				{
					continue;
				}

				String noteId = namespace + ":build:" + noteCounter++;
				footnotes.put(noteId, text);
				index.addBuildNoteId(noteId);
			}
		}

		JsonArray itemOrSlotNotes = getArray(notesObject, "by_item_or_slot");
		if (itemOrSlotNotes != null)
		{
			int generated = 1;
			for (JsonElement noteElement : itemOrSlotNotes)
			{
				if (!noteElement.isJsonObject())
				{
					continue;
				}

				JsonObject noteObject = noteElement.getAsJsonObject();
				String noteText = getString(noteObject, "note_text");
				if (noteText == null || noteText.trim().isEmpty())
				{
					continue;
				}

				String rawId = firstNonBlank(getString(noteObject, "note_id"), "n" + generated++);
				String noteId = namespace + ":" + rawId;
				footnotes.put(noteId, noteText);
				index.addTargetNote(
					getString(noteObject, "target_type"),
					getString(noteObject, "target_key"),
					noteId);
			}
		}

		return index;
	}

	private Map<Integer, List<GearRow>> parseBuildTierRows(JsonObject build, BuildNoteIndex noteIndex)
	{
		Map<Integer, List<GearRow>> tierToRows = new HashMap<>();
		JsonObject gearObject = getObject(build, "gear");
		JsonObject bySlot = getObject(gearObject, "by_slot");
		if (bySlot == null)
		{
			return Collections.emptyMap();
		}

		for (Map.Entry<String, JsonElement> slotEntry : bySlot.entrySet())
		{
			String slot = normalizeSlot(slotEntry.getKey());
			JsonArray itemArray = getArray(bySlot, slotEntry.getKey());
			if (itemArray == null)
			{
				continue;
			}

			for (JsonElement itemElement : itemArray)
			{
				if (!itemElement.isJsonObject())
				{
					continue;
				}

				JsonObject item = itemElement.getAsJsonObject();
				String itemName = getString(item, "name");
				if (itemName == null || itemName.trim().isEmpty())
				{
					continue;
				}

				int tier = getInt(item, "tier", 1);
				String altName = getString(item, "alt_name");

				List<String> noteIds = new ArrayList<>();
				noteIds.addAll(noteIndex.getNotesForSlot(slot));
				noteIds.addAll(noteIndex.getNotesForItem(itemName));
				if (altName != null && !altName.trim().isEmpty())
				{
					noteIds.addAll(noteIndex.getNotesForItem(altName));
				}

				for (String noteRef : getStringArray(item, "notes_refs"))
				{
					String resolved = noteIndex.resolveByRawRef(noteRef);
					noteIds.add(resolved == null ? noteRef : resolved);
				}

				List<String> uniqueNoteIds = dedupeInOrder(noteIds);

				GearRow row = new GearRow(
					slot,
					itemName,
					altName == null ? "" : altName,
					uniqueNoteIds);

				tierToRows.computeIfAbsent(tier, ignored -> new ArrayList<>()).add(row);
			}
		}

		return sortAndFreezeTierRows(tierToRows);
	}

	private Map<Integer, List<GearRow>> sortAndFreezeTierRows(Map<Integer, List<GearRow>> tierToRows)
	{
		Map<Integer, List<GearRow>> sortedTierRows = new LinkedHashMap<>();
		List<Integer> tiers = new ArrayList<>(tierToRows.keySet());
		tiers.sort(Integer::compareTo);
		for (Integer tier : tiers)
		{
			List<GearRow> rows = tierToRows.get(tier);
			rows.sort(Comparator.comparing(GearRow::getSlot).thenComparing(GearRow::getItemName));
			sortedTierRows.put(tier, Collections.unmodifiableList(rows));
		}
		return sortedTierRows;
	}

	public String findBestMonsterKeyForTask(String taskName, boolean preferBossVariant)
	{
		if (taskName == null || taskName.trim().isEmpty())
		{
			return null;
		}

		String normalized = normalize(taskName);
		String singularNormalized = toSingular(normalized);
		String key = taskNameToMonsterKey.get(normalized);
		if (key == null)
		{
			key = taskNameToMonsterKey.get(singularNormalized);
		}

		if (key == null)
		{
			for (MonsterSummary summary : monsterSummaries)
			{
				String summaryNorm = normalize(summary.getName());
				String summarySingular = toSingular(summaryNorm);
				if (summaryNorm.contains(normalized)
					|| summaryNorm.contains(singularNormalized)
					|| summarySingular.contains(singularNormalized)
					|| normalized.contains(summaryNorm)
					|| singularNormalized.contains(summarySingular))
				{
					key = summary.getKey();
					break;
				}
			}
		}

		if (key == null)
		{
			return null;
		}

		if (preferBossVariant)
		{
			String bossKey = baseTaskToBossKey.get(key);
			if (bossKey != null && monsters != null && monsters.has(bossKey))
			{
				return bossKey;
			}
		}

		return key;
	}

	private List<MonsterSummary> buildSummaries(JsonObject monstersObject)
	{
		if (monstersObject == null)
		{
			return Collections.emptyList();
		}

		List<MonsterSummary> summaries = new ArrayList<>();
		for (Map.Entry<String, JsonElement> entry : monstersObject.entrySet())
		{
			if (!entry.getValue().isJsonObject())
			{
				continue;
			}

			MonsterSummary summary = createSummary(entry.getKey(), entry.getValue().getAsJsonObject());
			if (summary != null)
			{
				summaries.add(summary);
			}
		}

		summaries.sort(Comparator.comparing(MonsterSummary::getName, String.CASE_INSENSITIVE_ORDER));
		return summaries;
	}

	private Map<String, String> buildTaskLookup(List<MonsterSummary> summaries)
	{
		Map<String, String> lookup = new LinkedHashMap<>();
		for (MonsterSummary summary : summaries)
		{
			String key = summary.getKey();
			String normalizedName = normalize(summary.getName());
			String normalizedKey = normalize(key.replace('_', ' '));

			lookup.putIfAbsent(normalizedName, key);
			lookup.putIfAbsent(toSingular(normalizedName), key);
			lookup.putIfAbsent(normalizedKey, key);
			lookup.putIfAbsent(toSingular(normalizedKey), key);

			String[] words = summary.getName().toLowerCase().split("\\s+");
			if (words.length > 1)
			{
				String lastWord = normalize(words[words.length - 1]);
				lookup.putIfAbsent(lastWord, key);
				lookup.putIfAbsent(toSingular(lastWord), key);
			}
		}
		return Collections.unmodifiableMap(lookup);
	}

	private Map<String, String> buildBossLookup(JsonObject rootObject)
	{
		JsonObject bossVariants = getObject(rootObject, "boss_variants");
		if (bossVariants == null)
		{
			return Collections.emptyMap();
		}

		Map<String, String> lookup = new LinkedHashMap<>();
		for (Map.Entry<String, JsonElement> entry : bossVariants.entrySet())
		{
			if (!entry.getValue().isJsonObject())
			{
				continue;
			}

			JsonObject mapping = entry.getValue().getAsJsonObject();
			String base = entry.getKey();
			String boss = getString(mapping, "boss");
			if (boss != null)
			{
				lookup.put(base, boss);
			}
		}

		return Collections.unmodifiableMap(lookup);
	}

	private static String normalize(String input)
	{
		String lowered = input.toLowerCase();
		StringBuilder builder = new StringBuilder(lowered.length());
		for (int i = 0; i < lowered.length(); i++)
		{
			char ch = lowered.charAt(i);
			if (Character.isLetterOrDigit(ch))
			{
				builder.append(ch);
			}
		}
		return builder.toString();
	}

	private static String toSingular(String value)
	{
		if (value.endsWith("ies") && value.length() > 3)
		{
			return value.substring(0, value.length() - 3) + "y";
		}
		if (value.endsWith("ses") && value.length() > 3)
		{
			return value.substring(0, value.length() - 2);
		}
		if (value.endsWith("s") && value.length() > 1)
		{
			return value.substring(0, value.length() - 1);
		}
		return value;
	}

	private MonsterSummary createSummary(String key, JsonObject monster)
	{
		if (monster == null)
		{
			return null;
		}

		String name = getString(monster, "name");
		if (name == null)
		{
			name = key;
		}

		JsonObject meta = getObject(monster, "meta");

		List<String> combatStyles = getStringArray(monster, "combat_styles");
		if (combatStyles.isEmpty())
		{
			combatStyles = getBuildStyleNames(monster);
		}

		return new MonsterSummary(
			key,
			name,
			getString(monster, "wiki_page"),
			getIntOrNull(meta, "slayer_level"),
			getIntOrNull(meta, "combat_level"),
			getIntOrNull(meta, "task_weight"),
			Collections.unmodifiableList(combatStyles)
		);
	}

	private List<String> getBuildStyleNames(JsonObject monster)
	{
		JsonArray builds = getArray(monster, "builds");
		if (builds == null)
		{
			return Collections.emptyList();
		}

		List<String> names = new ArrayList<>();
		for (JsonElement buildElement : builds)
		{
			if (!buildElement.isJsonObject())
			{
				continue;
			}

			JsonObject build = buildElement.getAsJsonObject();
			String styleName = firstNonBlank(
				getString(build, "label"),
				getString(build, "style"),
				getString(build, "id"));
			if (styleName != null)
			{
				names.add(styleName);
			}
		}
		return names;
	}

	private static String normalizeSlot(String slot)
	{
		if (slot == null)
		{
			return "";
		}
		return slot.trim().toUpperCase();
	}

	private static String firstNonBlank(String... values)
	{
		for (String value : values)
		{
			if (value != null && !value.trim().isEmpty())
			{
				return value.trim();
			}
		}
		return null;
	}

	private static List<String> dedupeInOrder(List<String> values)
	{
		LinkedHashMap<String, Boolean> seen = new LinkedHashMap<>();
		for (String value : values)
		{
			if (value == null || value.trim().isEmpty())
			{
				continue;
			}
			seen.putIfAbsent(value, Boolean.TRUE);
		}
		return new ArrayList<>(seen.keySet());
	}

	private static JsonObject getObject(JsonObject object, String key)
	{
		if (object == null || !object.has(key) || !object.get(key).isJsonObject())
		{
			return null;
		}
		return object.getAsJsonObject(key);
	}

	private static JsonArray getArray(JsonObject object, String key)
	{
		if (object == null || !object.has(key) || !object.get(key).isJsonArray())
		{
			return null;
		}
		return object.getAsJsonArray(key);
	}

	private static String getString(JsonObject object, String key)
	{
		if (object == null || !object.has(key) || object.get(key).isJsonNull())
		{
			return null;
		}
		return object.get(key).getAsString();
	}

	private static List<String> getStringArray(JsonObject object, String key)
	{
		JsonArray array = getArray(object, key);
		if (array == null)
		{
			return Collections.emptyList();
		}

		List<String> result = new ArrayList<>();
		for (JsonElement element : array)
		{
			if (!element.isJsonNull())
			{
				result.add(element.getAsString());
			}
		}
		return result;
	}

	private static Integer getIntOrNull(JsonObject object, String key)
	{
		if (object == null || !object.has(key) || object.get(key).isJsonNull())
		{
			return null;
		}
		return object.get(key).getAsInt();
	}

	private static int getInt(JsonObject object, String key, int fallback)
	{
		Integer value = getIntOrNull(object, key);
		return value == null ? fallback : value;
	}

	private static boolean getBoolean(JsonObject object, String key, boolean fallback)
	{
		if (object == null || !object.has(key) || object.get(key).isJsonNull())
		{
			return fallback;
		}
		return object.get(key).getAsBoolean();
	}

	public static final class MonsterSummary
	{
		private final String key;
		private final String name;
		private final String wikiPage;
		private final Integer slayerLevel;
		private final Integer combatLevel;
		private final Integer taskWeight;
		private final List<String> combatStyles;

		private MonsterSummary(
			String key,
			String name,
			String wikiPage,
			Integer slayerLevel,
			Integer combatLevel,
			Integer taskWeight,
			List<String> combatStyles)
		{
			this.key = key;
			this.name = name;
			this.wikiPage = wikiPage;
			this.slayerLevel = slayerLevel;
			this.combatLevel = combatLevel;
			this.taskWeight = taskWeight;
			this.combatStyles = combatStyles;
		}

		public String getKey()
		{
			return key;
		}

		public String getName()
		{
			return name;
		}

		public String getWikiPage()
		{
			return wikiPage;
		}

		public Integer getSlayerLevel()
		{
			return slayerLevel;
		}

		public Integer getCombatLevel()
		{
			return combatLevel;
		}

		public Integer getTaskWeight()
		{
			return taskWeight;
		}

		public List<String> getCombatStyles()
		{
			return combatStyles;
		}

		@Override
		public String toString()
		{
			return name;
		}

		@Override
		public boolean equals(Object other)
		{
			if (this == other)
			{
				return true;
			}
			if (!(other instanceof MonsterSummary))
			{
				return false;
			}
			MonsterSummary that = (MonsterSummary) other;
			return Objects.equals(key, that.key);
		}

		@Override
		public int hashCode()
		{
			return Objects.hash(key);
		}
	}

	public static final class MonsterDetails
	{
		private final MonsterSummary summary;
		private final List<CombatStyleDetails> combatStyles;
		private final Map<String, String> footnotes;
		private final String baseTask;
		private final boolean bossVariant;
		private final boolean requiresSlayerTask;

		private MonsterDetails(
			MonsterSummary summary,
			List<CombatStyleDetails> combatStyles,
			Map<String, String> footnotes,
			String baseTask,
			boolean bossVariant,
			boolean requiresSlayerTask)
		{
			this.summary = summary;
			this.combatStyles = combatStyles;
			this.footnotes = footnotes;
			this.baseTask = baseTask;
			this.bossVariant = bossVariant;
			this.requiresSlayerTask = requiresSlayerTask;
		}

		public MonsterSummary getSummary()
		{
			return summary;
		}

		public List<CombatStyleDetails> getCombatStyles()
		{
			return combatStyles;
		}

		public Map<String, String> getFootnotes()
		{
			return footnotes;
		}

		public String getBaseTask()
		{
			return baseTask;
		}

		public boolean isBossVariant()
		{
			return bossVariant;
		}

		public boolean isRequiresSlayerTask()
		{
			return requiresSlayerTask;
		}
	}

	public static final class CombatStyleDetails
	{
		private final String name;
		private final Map<Integer, List<GearRow>> tierRows;
		private final List<String> buildNoteIds;

		private CombatStyleDetails(String name, Map<Integer, List<GearRow>> tierRows, List<String> buildNoteIds)
		{
			this.name = name;
			this.tierRows = tierRows;
			this.buildNoteIds = Collections.unmodifiableList(new ArrayList<>(buildNoteIds));
		}

		public String getName()
		{
			return name;
		}

		public Map<Integer, List<GearRow>> getTierRows()
		{
			return tierRows;
		}

		public List<String> getBuildNoteIds()
		{
			return buildNoteIds;
		}
	}

	public static final class GearRow
	{
		private final String slot;
		private final String itemName;
		private final String altName;
		private final List<String> noteIds;

		private GearRow(String slot, String itemName, String altName, List<String> noteIds)
		{
			this.slot = slot;
			this.itemName = itemName;
			this.altName = altName;
			this.noteIds = Collections.unmodifiableList(new ArrayList<>(noteIds));
		}

		public String getSlot()
		{
			return slot;
		}

		public String getItemName()
		{
			return itemName;
		}

		public String getAltName()
		{
			return altName;
		}

		public List<String> getNoteIds()
		{
			return noteIds;
		}
	}

	private static final class BuildNoteIndex
	{
		private final Map<String, List<String>> notesByItem = new HashMap<>();
		private final Map<String, List<String>> notesBySlot = new HashMap<>();
		private final Map<String, String> fullRefByRawRef = new HashMap<>();
		private final List<String> buildNoteIds = new ArrayList<>();

		private void addBuildNoteId(String noteId)
		{
			buildNoteIds.add(noteId);
		}

		private List<String> getBuildNoteIds()
		{
			return Collections.unmodifiableList(buildNoteIds);
		}

		private void addTargetNote(String targetType, String targetKey, String noteId)
		{
			if (targetKey == null || targetKey.trim().isEmpty() || noteId == null)
			{
				return;
			}

			String key = normalize(targetKey);
			if (key.isEmpty())
			{
				return;
			}

			String rawRef = rawRefFromNamespacedId(noteId);
			if (rawRef != null)
			{
				fullRefByRawRef.putIfAbsent(rawRef, noteId);
			}

			if ("slot".equalsIgnoreCase(targetType))
			{
				notesBySlot.computeIfAbsent(key, ignored -> new ArrayList<>()).add(noteId);
			}
			else
			{
				notesByItem.computeIfAbsent(key, ignored -> new ArrayList<>()).add(noteId);
			}
		}

		private String resolveByRawRef(String rawRef)
		{
			if (rawRef == null)
			{
				return null;
			}
			return fullRefByRawRef.get(rawRef);
		}

		private List<String> getNotesForItem(String itemName)
		{
			if (itemName == null)
			{
				return Collections.emptyList();
			}
			return notesByItem.getOrDefault(normalize(itemName), Collections.emptyList());
		}

		private List<String> getNotesForSlot(String slot)
		{
			if (slot == null)
			{
				return Collections.emptyList();
			}
			return notesBySlot.getOrDefault(normalize(slot), Collections.emptyList());
		}

		private static String rawRefFromNamespacedId(String noteId)
		{
			int idx = noteId.lastIndexOf(':');
			if (idx < 0 || idx + 1 >= noteId.length())
			{
				return null;
			}
			return noteId.substring(idx + 1);
		}
	}
}
