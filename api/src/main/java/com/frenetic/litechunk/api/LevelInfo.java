package com.frenetic.litechunk.api;

import java.util.Collections;
import java.util.List;

/**
 * Everything the world details screen shows about one {@code level.dat}.
 * <p>
 * Produced by {@link ChunkerEngine#inspectLevel(java.io.File)} without touching the world: the file is read, parsed
 * and thrown away. Nothing here is cached, so the screen always reflects what is on disk.
 * <p>
 * The directory passed to the engine only has to contain {@code level.dat}. Extracting it from an archive, and
 * putting it back afterwards, is the caller's job: worlds are stored as {@code .zip} / {@code .mcworld} here, and
 * rewriting a multi-hundred-megabyte archive is an I/O concern that does not belong in the parser.
 */
public final class LevelInfo {

    /** {@code JAVA} or {@code BEDROCK}. */
    public final String formatId;
    /** User facing edition label, e.g. {@code Java 版}. */
    public final String formatLabel;
    /**
     * Game version derived from the data version inside the file, e.g. {@code 1.21.6}, or {@code null} when the
     * number does not map to any known release.
     */
    public final String version;
    /** Fields, ordered from most to least likely to be edited. */
    public final List<LevelField> fields;
    /** Findings, most severe first. */
    public final List<LevelNote> notes;

    public LevelInfo(String formatId, String formatLabel, String version,
                     List<LevelField> fields, List<LevelNote> notes) {
        this.formatId = formatId;
        this.formatLabel = formatLabel;
        this.version = version;
        this.fields = fields == null ? Collections.emptyList() : fields;
        this.notes = notes == null ? Collections.emptyList() : notes;
    }

    /**
     * @param id a field id.
     * @return the field, or {@code null} when this world does not have it.
     */
    public LevelField field(String id) {
        for (LevelField field : fields) {
            if (field.id.equals(id)) {
                return field;
            }
        }
        return null;
    }
}
