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

    private State state = State.IDLE;
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
     */
    void detect(Context context, QueueItem item) {
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            File flush = Paths.newFlushDirectory(appContext);
            File staged = new File(flush, "in");
            try {
                File root = ConversionService.materialize(appContext, item, staged, null);
                if (root == null) {
                    throw new IllegalStateException("没有找到世界数据（缺少 level.dat）");
                }
                // The service owns the scratch directory from here: it deletes it after the reader has finished
                // with it. Deleting it here would race the detection that was just dispatched.
                start(new Intent(app, ConversionService.class)
                        .setAction(Job.ACTION_DETECT)
                        .putExtra(Job.EXTRA_PATH, root.getAbsolutePath())
                        .putExtra(Job.EXTRA_FLUSH, flush.getAbsolutePath())
                        .putExtra(Job.KEY_ITEM_ID, item.id)
                        .putExtra(Job.EXTRA_RECEIVER, callback));
            } catch (Throwable failure) {
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

    private void onDetected(Bundle data) {
        Store.get(app).markDetected(data.getString(Job.KEY_ITEM_ID),
                data.getString(Job.KEY_FORMAT_ID),
                data.getString(Job.KEY_FORMAT_LABEL),
                data.getString(Job.KEY_VERSION));
    }
}
