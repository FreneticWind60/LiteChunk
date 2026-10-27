package com.frenetic.litechunk;

import android.os.Bundle;

import com.frenetic.litechunk.api.LevelField;
import com.frenetic.litechunk.api.LevelInfo;
import com.frenetic.litechunk.api.LevelNote;

import java.util.ArrayList;
import java.util.List;

/**
 * Wire protocol between the UI process and the isolated {@code :chunker} process.
 * <p>
 * The engine lives in its own process so a conversion that exhausts the heap cannot take the UI down with it, and so
 * a cancelled job can be terminated by killing only that process.
 */
final class Job {

    private Job() {
    }

    // Actions
    static final String ACTION_INFO = "com.frenetic.litechunk.action.INFO";
    static final String ACTION_DETECT = "com.frenetic.litechunk.action.DETECT";
    static final String ACTION_CONVERT = "com.frenetic.litechunk.action.CONVERT";
    static final String ACTION_CANCEL = "com.frenetic.litechunk.action.CANCEL";
    /** Read {@code level.dat} for the details screen. */
    static final String ACTION_INSPECT = "com.frenetic.litechunk.action.INSPECT";
    /** Write edited {@code level.dat} fields back. */
    static final String ACTION_APPLY_LEVEL = "com.frenetic.litechunk.action.APPLY_LEVEL";

    // Intent extras
    static final String EXTRA_RECEIVER = "receiver";
    static final String EXTRA_PATH = "path";
    static final String EXTRA_ITEM = "item";
    static final String EXTRA_ITEMS = "items";
    static final String EXTRA_FORMAT = "format";
    static final String EXTRA_PACK_MCWORLD = "packMcworld";
    static final String EXTRA_DELETE_SOURCE = "deleteSource";
    /** Scratch directory the UI staged a world into; the service deletes it once detection has read it. */
    static final String EXTRA_FLUSH = "flush";
    /** Keep the scratch directory after detection instead of deleting it, so the conversion can reuse it. */
    static final String EXTRA_KEEP_FLUSH = "keepFlush";
    /**
     * Naming settings, shipped with the batch rather than read from SharedPreferences: the service runs in the
     * {@code :chunker} process, where a preference the UI wrote seconds ago is not guaranteed to be visible yet.
     */
    static final String EXTRA_NAMING_ORDER = "namingOrder";
    static final String EXTRA_NAMING_ENABLED = "namingEnabled";
    static final String EXTRA_NAMING_SEPARATOR = "namingSeparator";
    /** Field ids and their new values, as parallel arrays: a bundle handed to a receiver has to stay primitive. */
    static final String EXTRA_LEVEL_IDS = "levelIds";
    static final String EXTRA_LEVEL_VALUES = "levelValues";

    // Result codes
    static final int CODE_INFO = 1;
    static final int CODE_DETECTED = 2;
    static final int CODE_PROGRESS = 3;
    static final int CODE_ITEM_START = 4;
    static final int CODE_ITEM_DONE = 5;
    static final int CODE_ITEM_ERROR = 6;
    static final int CODE_QUEUE_DONE = 7;
    static final int CODE_FATAL = 8;
    static final int CODE_STAGE = 9;
    static final int CODE_LEVEL_INFO = 10;
    static final int CODE_LEVEL_SAVED = 11;

    // Bundle keys
    static final String KEY_ERROR = "error";
    static final String KEY_PROGRESS = "progress";
    static final String KEY_STAGE = "stage";
    static final String KEY_ELAPSED = "elapsed";
    static final String KEY_OUTPUT = "output";
    static final String KEY_INDEX = "index";
    static final String KEY_COUNT = "count";
    static final String KEY_NAME = "name";
    static final String KEY_SUCCEEDED = "succeeded";
    static final String KEY_FAILED = "failed";
    static final String KEY_SIZE = "size";
    static final String KEY_FORMAT_ID = "formatId";
    static final String KEY_FORMAT_LABEL = "formatLabel";
    static final String KEY_VERSION = "version";
    static final String KEY_ITEM_ID = "itemId";

    // Engine format list, shipped as parallel arrays because a ResultReceiver bundle has to stay primitive-only.
    static final String KEY_ENGINE_VERSION = "engineVersion";
    static final String KEY_GROUP_COUNT = "groupCount";
    static final String KEY_GROUP_ID = "groupId";
    static final String KEY_GROUP_LABEL = "groupLabel";
    static final String KEY_GROUP_VERSIONS = "groupVersions";
    static final String KEY_GROUP_FORMAT_IDS = "groupFormatIds";

    // World details, also parallel arrays. Option lists are joined with UNIT_SEPARATOR because a bundle cannot
    // carry a nested array and the values never contain control characters.
    private static final char UNIT_SEPARATOR = '\u0001';
    static final String KEY_LEVEL_FORMAT_ID = "levelFormatId";
    static final String KEY_LEVEL_FORMAT_LABEL = "levelFormatLabel";
    static final String KEY_LEVEL_VERSION = "levelVersion";
    static final String KEY_LEVEL_FIELD_IDS = "levelFieldIds";
    static final String KEY_LEVEL_FIELD_ZH = "levelFieldZh";
    static final String KEY_LEVEL_FIELD_EN = "levelFieldEn";
    static final String KEY_LEVEL_FIELD_VALUES = "levelFieldValues";
    static final String KEY_LEVEL_FIELD_KINDS = "levelFieldKinds";
    static final String KEY_LEVEL_FIELD_OPTIONS = "levelFieldOptions";
    static final String KEY_LEVEL_FIELD_OPTIONS_ZH = "levelFieldOptionsZh";
    static final String KEY_LEVEL_FIELD_OPTIONS_EN = "levelFieldOptionsEn";
    static final String KEY_LEVEL_FIELD_HINT_ZH = "levelFieldHintZh";
    static final String KEY_LEVEL_FIELD_HINT_EN = "levelFieldHintEn";
    static final String KEY_LEVEL_NOTE_SEVERITY = "levelNoteSeverity";
    static final String KEY_LEVEL_NOTE_ZH = "levelNoteZh";
    static final String KEY_LEVEL_NOTE_EN = "levelNoteEn";
    /** Backup file suffix created by the save, so the UI can tell the user where the previous copy went. */
    static final String KEY_LEVEL_BACKUP = "levelBackup";

    /**
     * Flatten a {@link LevelInfo} into a bundle.
     * <p>
     * Parallel arrays rather than parcelable objects: {@code api} is a plain Java library with no Android
     * dependency, and the details screen runs in the UI process while the engine lives in {@code :chunker}, so the
     * description has to cross a process boundary as primitives.
     */
    static Bundle packLevel(LevelInfo info) {
        Bundle bundle = new Bundle();
        bundle.putString(KEY_LEVEL_FORMAT_ID, info.formatId);
        bundle.putString(KEY_LEVEL_FORMAT_LABEL, info.formatLabel);
        bundle.putString(KEY_LEVEL_VERSION, info.version);

        int count = info.fields.size();
        String[] ids = new String[count];
        String[] zh = new String[count];
        String[] en = new String[count];
        String[] values = new String[count];
        int[] kinds = new int[count];
        String[] options = new String[count];
        String[] optionsZh = new String[count];
        String[] optionsEn = new String[count];
        String[] hintsZh = new String[count];
        String[] hintsEn = new String[count];

        for (int i = 0; i < count; i++) {
            LevelField field = info.fields.get(i);
            ids[i] = field.id;
            zh[i] = field.labelZh;
            en[i] = field.labelEn;
            values[i] = field.value;
            kinds[i] = field.kind;
            options[i] = join(field.optionIds);
            optionsZh[i] = join(field.optionZh);
            optionsEn[i] = join(field.optionEn);
            hintsZh[i] = field.hintZh;
            hintsEn[i] = field.hintEn;
        }

        bundle.putStringArray(KEY_LEVEL_FIELD_IDS, ids);
        bundle.putStringArray(KEY_LEVEL_FIELD_ZH, zh);
        bundle.putStringArray(KEY_LEVEL_FIELD_EN, en);
        bundle.putStringArray(KEY_LEVEL_FIELD_VALUES, values);
        bundle.putIntArray(KEY_LEVEL_FIELD_KINDS, kinds);
        bundle.putStringArray(KEY_LEVEL_FIELD_OPTIONS, options);
        bundle.putStringArray(KEY_LEVEL_FIELD_OPTIONS_ZH, optionsZh);
        bundle.putStringArray(KEY_LEVEL_FIELD_OPTIONS_EN, optionsEn);
        bundle.putStringArray(KEY_LEVEL_FIELD_HINT_ZH, hintsZh);
        bundle.putStringArray(KEY_LEVEL_FIELD_HINT_EN, hintsEn);

        int noteCount = info.notes.size();
        int[] severities = new int[noteCount];
        String[] noteZh = new String[noteCount];
        String[] noteEn = new String[noteCount];
        for (int i = 0; i < noteCount; i++) {
            LevelNote note = info.notes.get(i);
            severities[i] = note.severity;
            noteZh[i] = note.textZh;
            noteEn[i] = note.textEn;
        }
        bundle.putIntArray(KEY_LEVEL_NOTE_SEVERITY, severities);
        bundle.putStringArray(KEY_LEVEL_NOTE_ZH, noteZh);
        bundle.putStringArray(KEY_LEVEL_NOTE_EN, noteEn);
        return bundle;
    }

    /** Inverse of {@link #packLevel(LevelInfo)}; tolerates a partial bundle from an older build. */
    static LevelInfo unpackLevel(Bundle bundle) {
        if (bundle == null) {
            return null;
        }
        String[] ids = bundle.getStringArray(KEY_LEVEL_FIELD_IDS);
        if (ids == null) {
            return null;
        }
        String[] zh = orEmpty(bundle.getStringArray(KEY_LEVEL_FIELD_ZH), ids.length);
        String[] en = orEmpty(bundle.getStringArray(KEY_LEVEL_FIELD_EN), ids.length);
        String[] values = orEmpty(bundle.getStringArray(KEY_LEVEL_FIELD_VALUES), ids.length);
        String[] options = orEmpty(bundle.getStringArray(KEY_LEVEL_FIELD_OPTIONS), ids.length);
        String[] optionsZh = orEmpty(bundle.getStringArray(KEY_LEVEL_FIELD_OPTIONS_ZH), ids.length);
        String[] optionsEn = orEmpty(bundle.getStringArray(KEY_LEVEL_FIELD_OPTIONS_EN), ids.length);
        String[] hintsZh = orEmpty(bundle.getStringArray(KEY_LEVEL_FIELD_HINT_ZH), ids.length);
        String[] hintsEn = orEmpty(bundle.getStringArray(KEY_LEVEL_FIELD_HINT_EN), ids.length);
        int[] kinds = bundle.getIntArray(KEY_LEVEL_FIELD_KINDS);
        if (kinds == null) {
            kinds = new int[ids.length];
        }

        List<LevelField> fields = new ArrayList<>(ids.length);
        for (int i = 0; i < ids.length; i++) {
            String[] optionIds = split(options[i]);
            fields.add(new LevelField(ids[i], zh[i], en[i], values[i], kinds[i],
                    optionIds, split(optionsZh[i]), split(optionsEn[i]), hintsZh[i], hintsEn[i]));
        }

        int[] severities = bundle.getIntArray(KEY_LEVEL_NOTE_SEVERITY);
        String[] noteZh = bundle.getStringArray(KEY_LEVEL_NOTE_ZH);
        String[] noteEn = bundle.getStringArray(KEY_LEVEL_NOTE_EN);
        List<LevelNote> notes = new ArrayList<>();
        if (severities != null && noteZh != null && noteEn != null) {
            for (int i = 0; i < severities.length && i < noteZh.length && i < noteEn.length; i++) {
                notes.add(new LevelNote(severities[i], noteZh[i], noteEn[i]));
            }
        }

        return new LevelInfo(
                bundle.getString(KEY_LEVEL_FORMAT_ID, "JAVA"),
                bundle.getString(KEY_LEVEL_FORMAT_LABEL, ""),
                bundle.getString(KEY_LEVEL_VERSION),
                fields, notes);
    }

    private static String join(String[] values) {
        if (values == null || values.length == 0) {
            return null;
        }
        return String.join(String.valueOf(UNIT_SEPARATOR), values);
    }

    private static String[] split(String joined) {
        if (joined == null || joined.isEmpty()) {
            return null;
        }
        return joined.split(String.valueOf(UNIT_SEPARATOR), -1);
    }

    private static String[] orEmpty(String[] values, int length) {
        if (values != null && values.length == length) {
            return values;
        }
        String[] filled = new String[length];
        java.util.Arrays.fill(filled, "");
        if (values != null) {
            System.arraycopy(values, 0, filled, 0, Math.min(values.length, length));
        }
        return filled;
    }

    /**
     * Turns a throwable into a short, user readable message that still hints at the failing frame.
     */
    static String describe(Throwable throwable) {
        if (throwable == null) {
            return "未知错误";
        }
        // An out-of-memory failure ends in a raw "Failed to allocate a 12345678 byte allocation with 4194304 free
        // bytes" message, which tells the user nothing they can act on.
        if (MemoryPlan.isOutOfMemory(throwable)) {
            return "内存不足，转换未能完成。\n建议：关闭其他后台应用后重试；存档特别大时可以先裁剪一部分区域再转换。";
        }
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        if (message == null || message.trim().isEmpty()) {
            message = cause.getClass().getSimpleName();
        }
        // "open failed: EACCES (Permission denied)" is the raw form of a storage problem, and it shows up in one
        // concrete situation that the user can actually fix: the data directory left over from a previous install
        // still belongs to the old uid, so it cannot be written to.
        if (message.contains("EACCES") || message.contains("Permission denied")) {
            return "无法写入应用的数据目录。\n"
                + "建议：到系统设置里清除 LiteChunk 的应用数据后重试（不会删除你的原存档）。\n"
                + "如果仍然失败，请检查存储空间是否已满、外置存储是否已挂载。";
        }
        StackTraceElement[] trace = cause.getStackTrace();
        if (trace.length > 0) {
            StackTraceElement frame = trace[0];
            message = message + "\n(" + frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber() + ")";
        }
        return message;
    }
}
