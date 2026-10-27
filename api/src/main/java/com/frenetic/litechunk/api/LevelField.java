package com.frenetic.litechunk.api;

/**
 * One entry of {@code level.dat}, as shown on the world details screen.
 * <p>
 * A field is deliberately <em>not</em> a free-form NBT node: the engine only ever exposes the keys on its allow list,
 * and every value is carried as a string the UI can render without knowing the tag type. Editing a tag whose type
 * the game validates (a {@code byte} written as an {@code int}, a list where a compound belongs) corrupts the world
 * in a way that looks like a conversion bug, so the surface is kept narrow on purpose.
 * <p>
 * Labels are bilingual because the English NBT key is what every wiki and save editor refers to, while the Chinese
 * label is what makes the screen readable. Both are shown; neither alone is enough.
 */
public final class LevelField {

    /** Free text, e.g. the world name. */
    public static final int KIND_TEXT = 0;
    /** Whole number, edited as digits. */
    public static final int KIND_INT = 1;
    /** 64 bit number, edited as digits (seed, game time). */
    public static final int KIND_LONG = 2;
    /** On/off, edited with a switch. */
    public static final int KIND_BOOL = 3;
    /** One of {@link #optionIds}; edited with a list, never typed by hand. */
    public static final int KIND_ENUM = 4;
    /** Displayed but never written, usually because changing it would break the world. */
    public static final int KIND_READ_ONLY = 5;

    /** The NBT key, which is also the name the field is addressed by when saving. */
    public final String id;
    /** Chinese label, shown large. */
    public final String labelZh;
    /** English label, shown as the subtitle together with {@link #id}. */
    public final String labelEn;
    /** Current value, already formatted for display. */
    public final String value;
    /** One of the {@code KIND_*} constants. */
    public final int kind;
    /** Allowed values for {@link #KIND_ENUM}, stored in {@link #id} space. */
    public final String[] optionIds;
    /** Chinese text for each entry of {@link #optionIds}. */
    public final String[] optionZh;
    /** English text for each entry of {@link #optionIds}. */
    public final String[] optionEn;
    /** Short Chinese note about the risk or meaning, or {@code null}. */
    public final String hintZh;
    /** Short English note about the risk or meaning, or {@code null}. */
    public final String hintEn;

    public LevelField(String id, String labelZh, String labelEn, String value, int kind,
                      String[] optionIds, String[] optionZh, String[] optionEn,
                      String hintZh, String hintEn) {
        this.id = id;
        this.labelZh = labelZh;
        this.labelEn = labelEn;
        this.value = value;
        this.kind = kind;
        this.optionIds = optionIds;
        this.optionZh = optionZh;
        this.optionEn = optionEn;
        this.hintZh = hintZh;
        this.hintEn = hintEn;
    }

    /** Plain read-only or free text field without options or hints. */
    public LevelField(String id, String labelZh, String labelEn, String value, int kind) {
        this(id, labelZh, labelEn, value, kind, null, null, null, null, null);
    }

    /** @return true when the value may be written back. */
    public boolean isEditable() {
        return kind != KIND_READ_ONLY;
    }

    /**
     * Localised text for an enum option.
     *
     * @param index the option index, already resolved.
     * @return Chinese and English joined by a slash, e.g. {@code 创造 / Creative}.
     */
    public String optionLabel(int index) {
        if (optionZh == null || optionEn == null || index < 0 || index >= optionZh.length) {
            return optionIds == null || index < 0 || index >= optionIds.length ? "" : optionIds[index];
        }
        return optionZh[index] + " / " + optionEn[index];
    }

    /**
     * @param candidate a value in {@link #id} space.
     * @return the matching option index, or -1.
     */
    public int optionIndex(String candidate) {
        if (optionIds == null || candidate == null) {
            return -1;
        }
        for (int i = 0; i < optionIds.length; i++) {
            if (optionIds[i].equals(candidate)) {
                return i;
            }
        }
        return -1;
    }
}
