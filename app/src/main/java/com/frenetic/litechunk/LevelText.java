package com.frenetic.litechunk;

import com.frenetic.litechunk.api.LevelField;

import java.util.HashMap;
import java.util.Map;

/**
 * Localised wording for the {@code level.dat} screen.
 * <p>
 * The engine hands over bilingual literals ({@code labelZh} + {@code labelEn}) because it cannot see resources, which
 * is fine for English and Chinese but leaves a Japanese build showing Chinese headings. Rather than teach the engine
 * about languages, the field's NBT key - a stable code, exactly like the progress stage codes - is looked up here and
 * turned into a string resource, with the engine's literal kept as the fallback for a key this table does not know.
 * <p>
 * The second line of every row still shows the raw key ({@code Level name / LevelName}): that line is documentation
 * for people who came here after reading about the field elsewhere, not translation.
 */
final class LevelText {

    private static final Map<String, Integer> LABELS = new HashMap<>();
    private static final Map<String, Integer> HINTS = new HashMap<>();
    private static final Map<String, int[]> OPTIONS = new HashMap<>();

    static {
        LABELS.put("LevelName", R.string.level_label_name);
        LABELS.put("GameType", R.string.level_label_gametype);
        LABELS.put("Difficulty", R.string.level_label_difficulty);
        // Java spells the cheat flag allowCommands, Bedrock spells it commandsEnabled.
        LABELS.put("allowCommands", R.string.level_label_commands);
        LABELS.put("commandsEnabled", R.string.level_label_commands);
        LABELS.put("hardcore", R.string.level_label_hardcore);
        LABELS.put("RandomSeed", R.string.level_label_seed);
        LABELS.put("Generator", R.string.level_label_generator);
        LABELS.put("SpawnX", R.string.level_label_spawn_x);
        LABELS.put("SpawnY", R.string.level_label_spawn_y);
        LABELS.put("SpawnZ", R.string.level_label_spawn_z);
        // Java stores the clock in DayTime, Bedrock in Time.
        LABELS.put("DayTime", R.string.level_label_time);
        LABELS.put("Time", R.string.level_label_time);
        LABELS.put("DataVersion", R.string.level_label_dataversion);
        LABELS.put("StorageVersion", R.string.level_label_storageversion);
        LABELS.put("NetworkVersion", R.string.level_label_networkversion);

        HINTS.put(LevelField.HINT_MISSING, R.string.level_hint_missing);
        HINTS.put(LevelField.HINT_SEED, R.string.level_hint_seed);
        HINTS.put(LevelField.HINT_GENERATOR, R.string.level_hint_generator);
        HINTS.put(LevelField.HINT_DATA_VERSION, R.string.level_hint_dataversion);
        HINTS.put(LevelField.HINT_STORAGE_VERSION, R.string.level_hint_storageversion);
        HINTS.put(LevelField.HINT_NETWORK_VERSION, R.string.level_hint_networkversion);

        OPTIONS.put("GameType", new int[]{
                R.string.level_option_survival, R.string.level_option_creative,
                R.string.level_option_adventure, R.string.level_option_spectator});
        OPTIONS.put("Difficulty", new int[]{
                R.string.level_option_peaceful, R.string.level_option_easy,
                R.string.level_option_normal, R.string.level_option_hard});
        OPTIONS.put("Generator", new int[]{
                R.string.level_option_legacy, R.string.level_option_infinite,
                R.string.level_option_flat, R.string.level_option_void});
    }

    private LevelText() {
    }

    /**
     * @param field the field being rendered.
     * @return the heading in the current language, falling back to what the engine sent.
     */
    static String label(LevelField field) {
        Integer id = LABELS.get(field.id);
        return id == null ? field.labelZh : Text.get(id);
    }

    /**
     * @param field the field being rendered.
     * @param index position in {@link LevelField#optionIds}.
     * @return the option in the current language, falling back to the bilingual text the engine built.
     */
    static String option(LevelField field, int index) {
        int[] ids = OPTIONS.get(field.id);
        if (ids == null || index < 0 || index >= ids.length) {
            return field.optionLabel(index);
        }
        return Text.get(ids[index]);
    }

    /**
     * @param field the field being rendered.
     * @return the hint in the current language, or null when the field has none.
     */
    static String hint(LevelField field) {
        if (field.hintCode != null) {
            Integer id = HINTS.get(field.hintCode);
            if (id != null) {
                return Text.get(id);
            }
        }
        return field.hintZh != null ? field.hintZh : field.hintEn;
    }
}
