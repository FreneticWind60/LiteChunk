package com.frenetic.litechunk;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;

import androidx.core.content.ContextCompat;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the conversion state for the UI process.
 * <p>
 * It lives outside the fragments on purpose: switching pages, rotating the screen or having the activity recreated
 * while a 3 minute conversion runs must not lose the progress bar or the fact that a job is in flight. The engine
 * itself runs in {@link ConversionService}, this class only mirrors its events.
 */
final class Controller {

    enum State {IDLE, RUNNING}

    interface Listener {
        void onControllerChanged();
    }

    interface FormatsListener {
        void onFormats(Formats formats);
    }

    private static Controller instance;

    static synchronized Controller get(Context context) {
        if (instance == null) {
            instance = new Controller(context.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<File> roundOutputs = new ArrayList<>();
    /** Ids of queue entries that finished, so they can leave the list when the batch ends. */
    private final java.util.Set<String> completedIds = new java.util.LinkedHashSet<>();

    /** Written on the main thread, read from the detection thread, so the runner state has to be visible. */
    private volatile State state = State.IDLE;
    private int index;
    private int total;
    private String currentName = "";
    private String stage = "";
    private double progress;
    private long startedAt;
    private int succeeded;
    private int failed;
    private String lastError;

    private Formats formats;
    private FormatsListener formatsListener;
    private boolean loadingFormats;

    private Controller(Context app) {
        this.app = app;
    }

    // ---------------------------------------------------------------------------------------------
    // Observation
    // ---------------------------------------------------------------------------------------------

    void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void notifyChanged() {
        main.post(() -> {
            for (Listener listener : listeners) {
                listener.onControllerChanged();
            }
        });
    }

    State state() {
        return state;
    }

    boolean isRunning() {
        return state == State.RUNNING;
    }

    int index() {
        return index;
    }

    int total() {
        return total;
    }

    String currentName() {
        return currentName;
    }

    String stage() {
        return stage;
    }

    double progress() {
        return progress;
    }

    int succeeded() {
        return succeeded;
    }

    int failed() {
        return failed;
    }

    String lastError() {
        return lastError;
    }

    List<File> roundOutputs() {
        return new ArrayList<>(roundOutputs);
    }

    long elapsedSeconds() {
        return startedAt == 0L ? 0L : Math.max(0L, (SystemClock.elapsedRealtime() - startedAt) / 1000L);
    }

    // ---------------------------------------------------------------------------------------------
    // Engine handshake
    // ---------------------------------------------------------------------------------------------

    Formats formats() {
        return formats;
    }

    void loadFormats(FormatsListener listener) {
        formatsListener = listener;
        if (formats != null) {
            listener.onFormats(formats);
            return;
        }
        if (loadingFormats) {
            return;
        }
        loadingFormats = true;
        start(new Intent(app, ConversionService.class)
                .setAction(Job.ACTION_INFO)
                .putExtra(Job.EXTRA_RECEIVER, callback));
    }

    /** Run detection for one queue entry and write the result back into it. */
    void detect(QueueItem item, File worldDirectory) {
        start(new Intent(app, ConversionService.class)
                .setAction(Job.ACTION_DETECT)
                .putExtra(Job.EXTRA_PATH, worldDirectory.getAbsolutePath())
                .putExtra(Job.KEY_ITEM_ID, item.id)
                .putExtra(Job.EXTRA_RECEIVER, callback));
    }

    /**
     * Detect an entry by materialising it into flush space first.
     * <p>
     * Detection needs a real directory (the readers sniff level.dat and the database), so this costs a copy of the
     * world. It runs right after the user adds an item, which is while they are still reading the list, rather than
     * at conversion time.
     * <p>
     * The copy is kept, keyed by entry id, and the conversion picks it up instead of unpacking the world a second
     * time: on a 278 MB world that removes one full unzip from the pipeline. It is written only when the whole
     * archive has been unpacked ({@link Paths#markStageReady}) — the queue row appears as soon as the archive is
     * picked, and "add then convert immediately" must not read a half written copy.
     */
    void detect(Context context, QueueItem item) {
        if (isRunning()) {
            // The service runs one job thread at a time and refuses a second one, which would surface as
            // "已有转换任务正在进行" in the middle of a conversion. Leave the entry undetected: detectPending()
            // catches it up as soon as the batch ends.
            return;
        }
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            File flush = Paths.stageFor(appContext, item.id);
            File root = Paths.isStageReady(flush) ? WorldFiles.locateWorldRoot(new File(flush, "in")) : null;
            try {
                if (root == null) {
                    // No complete copy yet (first detection, or a previous one was interrupted): build it.
                    WorldFiles.delete(flush);
                    root = ConversionService.materialize(appContext, item, new File(flush, "in"), null);
                    if (root != null) {
                        Paths.markStageReady(flush);
                    }
                }
                if (root == null) {
                    throw new IllegalStateException("没有找到世界数据（缺少 level.dat）");
                }
                // The service owns the scratch directory from here: it keeps it for the conversion and deletes it
                // once that item is done. Deleting it here would race the detection that was just dispatched.
                start(new Intent(app, ConversionService.class)
                        .setAction(Job.ACTION_DETECT)
                        .putExtra(Job.EXTRA_PATH, root.getAbsolutePath())
                        .putExtra(Job.EXTRA_FLUSH, flush.getAbsolutePath())
                        .putExtra(Job.EXTRA_KEEP_FLUSH, true)
                        .putExtra(Job.KEY_ITEM_ID, item.id)
                        .putExtra(Job.EXTRA_RECEIVER, callback));
            } catch (Throwable failure) {
                android.util.Log.w("Controller", "detection failed for " + item.name, failure);
                WorldFiles.delete(flush);
                main.post(() -> {
                    item.formatId = null;
                    item.formatLabel = null;
                    item.version = null;
                    Store.get(app).notifyQueue();
                });
            }
        }, "litechunk-detect").start();
    }

    /**
     * Describe the {@code level.dat} inside a directory that holds one.
     * <p>
     * The caller owns the receiver rather than using {@link #callback}: the details screen is a separate activity
     * with its own lifecycle, and routing its answers through the conversion state machine would make a details
     * refresh look like a job event.
     *
     * @param directory a directory containing {@code level.dat}.
     * @param receiver  receives {@link Job#CODE_LEVEL_INFO} or {@link Job#CODE_FATAL}.
     */
    void inspectLevel(File directory, ResultReceiver receiver) {
        start(new Intent(app, ConversionService.class)
                .setAction(Job.ACTION_INSPECT)
                .putExtra(Job.EXTRA_PATH, directory.getAbsolutePath())
                .putExtra(Job.EXTRA_RECEIVER, receiver));
    }

    /**
     * Write edited {@code level.dat} fields back.
     *
     * @param directory a directory containing {@code level.dat}.
     * @param changes   field id to new value.
     * @param receiver  receives {@link Job#CODE_LEVEL_SAVED} or {@link Job#CODE_FATAL}.
     */
    void applyLevel(File directory, java.util.Map<String, String> changes, ResultReceiver receiver) {
        String[] ids = new String[changes.size()];
        String[] values = new String[changes.size()];
        int index = 0;
        for (java.util.Map.Entry<String, String> entry : changes.entrySet()) {
            ids[index] = entry.getKey();
            values[index] = entry.getValue();
            index++;
        }
        start(new Intent(app, ConversionService.class)
                .setAction(Job.ACTION_APPLY_LEVEL)
                .putExtra(Job.EXTRA_PATH, directory.getAbsolutePath())
                .putExtra(Job.EXTRA_LEVEL_IDS, ids)
                .putExtra(Job.EXTRA_LEVEL_VALUES, values)
                .putExtra(Job.EXTRA_RECEIVER, receiver));
    }

    // ---------------------------------------------------------------------------------------------
    // Conversion
    // ---------------------------------------------------------------------------------------------

    void start(List<QueueItem> items, String formatId, boolean packMcworld, boolean deleteSource) {
        if (state == State.RUNNING || items.isEmpty()) {
            return;
        }
        state = State.RUNNING;
        index = 0;
        total = items.size();
        currentName = items.get(0).name;
        stage = "正在准备…";
        progress = 0D;
        succeeded = 0;
        failed = 0;
        lastError = null;
        startedAt = SystemClock.elapsedRealtime();
        roundOutputs.clear();

        Intent intent = new Intent(app, ConversionService.class)
                .setAction(Job.ACTION_CONVERT)
                .putExtra(Job.EXTRA_ITEMS, new ArrayList<>(items))
                .putExtra(Job.EXTRA_FORMAT, formatId)
                .putExtra(Job.EXTRA_PACK_MCWORLD, packMcworld)
                .putExtra(Job.EXTRA_DELETE_SOURCE, deleteSource)
                .putExtra(Job.EXTRA_NAMING_ORDER, Naming.order(app))
                .putExtra(Job.EXTRA_NAMING_ENABLED, Naming.enabled(app))
                .putExtra(Job.EXTRA_NAMING_SEPARATOR, Naming.separator(app))
                .putExtra(Job.EXTRA_RECEIVER, callback);
        ContextCompat.startForegroundService(app, intent);
        notifyChanged();
    }

    void cancel() {
        if (state != State.RUNNING) {
            return;
        }
        // Chunker has no abort API, so the isolated process is killed; the UI resets immediately.
        state = State.IDLE;
        progress = 0D;
        startedAt = 0L;
        stage = "";
        notifyChanged();
        start(new Intent(app, ConversionService.class).setAction(Job.ACTION_CANCEL));
    }

    private void start(Intent intent) {
        try {
            app.startService(intent);
        } catch (RuntimeException failure) {
            lastError = Job.describe(failure);
            notifyChanged();
        }
    }

    private final ResultReceiver callback = new ResultReceiver(main) {
        @Override
        protected void onReceiveResult(int code, Bundle data) {
            if (data == null) {
                data = new Bundle();
            }
            switch (code) {
                case Job.CODE_INFO:
                    loadingFormats = false;
                    formats = Formats.fromBundle(data);
                    if (formatsListener != null) {
                        formatsListener.onFormats(formats);
                    }
                    notifyChanged();
                    break;
                case Job.CODE_DETECTED:
                    onDetected(data);
                    break;
                case Job.CODE_ITEM_START:
                    index = data.getInt(Job.KEY_INDEX, index);
                    total = data.getInt(Job.KEY_COUNT, total);
                    currentName = data.getString(Job.KEY_NAME, currentName);
                    stage = "正在准备存档…";
                    progress = 0D;
                    notifyChanged();
                    break;
                case Job.CODE_STAGE:
                    stage = data.getString(Job.KEY_STAGE, stage);
                    notifyChanged();
                    break;
                case Job.CODE_PROGRESS:
                    progress = data.getFloat(Job.KEY_PROGRESS, 0F);
                    stage = data.getString(Job.KEY_STAGE, stage);
                    notifyChanged();
                    break;
                case Job.CODE_ITEM_DONE:
                    succeeded++;
                    String output = data.getString(Job.KEY_OUTPUT);
                    if (output != null) {
                        roundOutputs.add(new File(output));
                    }
                    String doneId = data.getString(Job.KEY_ITEM_ID);
                    if (doneId != null) {
                        completedIds.add(doneId);
                    }
                    notifyChanged();
                    break;
                case Job.CODE_ITEM_ERROR:
                    failed++;
                    lastError = data.getString(Job.KEY_ERROR, "未知错误");
                    notifyChanged();
                    break;
                case Job.CODE_QUEUE_DONE:
                    state = State.IDLE;
                    stage = "";
                    progress = 1D;
                    startedAt = 0L;
                    removeCompleted();
                    Store.get(app).refreshOutputs();
                    detectPending();
                    notifyChanged();
                    break;
                case Job.CODE_FATAL:
                    state = State.IDLE;
                    startedAt = 0L;
                    lastError = data.getString(Job.KEY_ERROR, "未知错误");
                    notifyChanged();
                    break;
                default:
                    break;
            }
        }
    };

    /** Converted entries leave the list; failed ones stay so they can be retried without picking them again. */
    private void removeCompleted() {
        if (completedIds.isEmpty()) {
            return;
        }
        Store store = Store.get(app);
        store.removeIds(completedIds);
        completedIds.clear();
    }

    /**
     * Catch up on entries that arrived while a batch was running.
     * <p>
     * The service runs one job thread at a time, so a detection requested mid batch is refused — which used to leave
     * anything shared in from another app sitting in the list as "未识别版本" until it was removed and added again.
     */
    private void detectPending() {
        for (QueueItem item : new ArrayList<>(Store.get(app).queue)) {
            if (!item.isDetected()) {
                detect(app, item);
            }
        }
    }

    private void onDetected(Bundle data) {
        Store.get(app).markDetected(data.getString(Job.KEY_ITEM_ID),
                data.getString(Job.KEY_FORMAT_ID),
                data.getString(Job.KEY_FORMAT_LABEL),
                data.getString(Job.KEY_VERSION));
    }
}
