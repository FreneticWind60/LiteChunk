package com.frenetic.litechunk;

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
