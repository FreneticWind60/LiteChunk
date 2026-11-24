package com.frenetic.litechunk;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.util.Log;

import com.frenetic.litechunk.sdk.ChunkBridge;
import com.frenetic.litechunk.sdk.ChunkBridgeException;
import com.frenetic.litechunk.sdk.ConversionCallback;
import com.frenetic.litechunk.sdk.ConversionHandle;
import com.frenetic.litechunk.sdk.ConversionRequest;
import com.frenetic.litechunk.sdk.ConversionResult;
import com.frenetic.litechunk.sdk.DetectionResult;
import com.frenetic.litechunk.sdk.Stage;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Owns the conversion state for the UI process.
 * <p>
 * It lives outside the fragments on purpose: switching pages, rotating the screen or having the activity recreated
 * while a 3 minute conversion runs must not lose the progress bar or the fact that a job is in flight.
 * <p>
 * The engine itself is the ChunkBridge SDK: a foreground service in its own process, driven item by item from here.
 * The batch is therefore a chain, not a queue inside the service - the next item only starts from the previous
 * item's completion callback, so a failure cannot take the whole list down and the notification never has to guess
 * which entry it is showing.
 */
final class Controller {

    private static final String TAG = "Controller";

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
    private final Set<String> completedIds = new LinkedHashSet<>();

    /** Written on the main thread, read from the detection thread, so the runner state has to be visible. */
    private volatile State state = State.IDLE;
    private List<QueueItem> batch = new ArrayList<>();
    private int index;
    private int total;
    private String currentName = "";
    private String stage = "";
    private double progress;
    private long startedAt;
    private int succeeded;
    private int failed;
    private String lastError;

    private String targetFormatId;
    private boolean packMcworld;
    private boolean deleteSource;
    private boolean convertEntities;
    /** The SDK cancels by killing its process, so the handle is the only way to reach the running item. */
    private ConversionHandle handle;

    private Formats formats;
    private FormatsListener formatsListener;

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

    /**
     * Drop one entry from the list of files this round produced.
     * <p>
     * The list is not a folder scan, so deleting the file from disk leaves the row behind - which is exactly what
     * happened when an output was deleted from the conversion page: it stayed on screen until the next conversion.
     *
     * @param file the file that was just deleted.
     */
    void forgetRoundOutput(File file) {
        roundOutputs.removeIf(entry -> entry.getAbsolutePath().equals(file.getAbsolutePath()));
    }

    long elapsedSeconds() {
        return startedAt == 0L ? 0L : Math.max(0L, (SystemClock.elapsedRealtime() - startedAt) / 1000L);
    }

    // ---------------------------------------------------------------------------------------------
    // Formats
    // ---------------------------------------------------------------------------------------------

    Formats formats() {
        return formats;
    }

    /**
     * Hand the UI the formats the SDK advertises.
     * <p>
     * This used to be a round trip into the {@code :chunker} process. The catalog now ships as an asset the SDK
     * reads synchronously, so the list is available before the engine is ever loaded - and a missing asset can no
     * longer leave the conversion page spinning forever.
     */
    void loadFormats(FormatsListener listener) {
        formatsListener = listener;
        if (formats != null) {
            listener.onFormats(formats);
            return;
        }
        try {
            formats = Formats.load();
            if (formatsListener != null) {
                formatsListener.onFormats(formats);
            }
        } catch (RuntimeException failure) {
            lastError = Job.describe(failure);
            Log.e(TAG, "could not read the format catalog", failure);
        }
        notifyChanged();
    }

    // ---------------------------------------------------------------------------------------------
    // Detection
    // ---------------------------------------------------------------------------------------------

    /**
     * Detect an entry and write the result back into it.
     * <p>
     * Detection needs real files for a folder pick; a single archive is read straight from its SAF stream by the
     * SDK. It runs right after the user adds an item, which is while they are still reading the list, rather than
     * at conversion time.
     */
    void detect(Context context, QueueItem item) {
        if (isRunning()) {
            // One conversion at a time; leave the entry undetected. detectPending() catches it up when the batch
            // ends, so it never surfaces as an error message in the middle of a conversion.
            return;
        }
        final Context appContext = context.getApplicationContext();
        new Thread(() -> {
            try {
                if (item.kind == QueueItem.Kind.TREE) {
                    // A picked folder has no stream; it has to exist as files before level.dat can be sniffed.
                    ChunkBridge.detect(stageTree(appContext, item), detectionCallback(item));
                } else if (item.kind == QueueItem.Kind.LOCAL) {
                    ChunkBridge.detect(new File(item.path), detectionCallback(item));
                } else {
                    ChunkBridge.detect(Uri.parse(item.uri), detectionCallback(item));
                }
            } catch (Throwable failure) {
                Log.w(TAG, "detection failed for " + item.name, failure);
                WorldFiles.delete(Paths.stageFor(appContext, item.id));
                main.post(() -> {
                    clearDetection(item);
                    Store.get(app).notifyQueue();
                });
            }
        }, "litechunk-detect").start();
    }

    private com.frenetic.litechunk.sdk.DetectionCallback detectionCallback(QueueItem item) {
        return (result, error) -> main.post(() -> {
            if (error != null) {
                Log.w(TAG, "detection failed for " + item.name + ": " + error.getCode(), error);
                clearDetection(item);
            } else {
                applyDetection(item, result);
            }
            Store.get(app).notifyQueue();
            notifyChanged();
        });
    }

    private void applyDetection(QueueItem item, DetectionResult result) {
        if (result == null) {
            clearDetection(item);
            return;
        }
        Store.get(app).markDetected(item.id, result.getEditionId(), result.getEditionLabel(), result.getVersion());
        notifyChanged();
    }

    private void clearDetection(QueueItem item) {
        item.formatId = null;
        item.formatLabel = null;
        item.version = null;
    }

    // ---------------------------------------------------------------------------------------------
    // World details
    // ---------------------------------------------------------------------------------------------

    /**
     * Describe the {@code level.dat} inside a directory that holds one.
     * <p>
     * The caller owns the receiver rather than using the batch state: the details screen is a separate activity
     * with its own lifecycle, and routing its answers through the conversion state machine would make a details
     * refresh look like a job event.
     *
     * @param directory a directory containing {@code level.dat}.
     * @param receiver  receives {@link Job#CODE_LEVEL_INFO} or {@link Job#CODE_FATAL}.
     */
    void inspectLevel(File directory, ResultReceiver receiver) {
        ChunkBridge.inspectLevel(directory, (info, error) -> main.post(() -> {
            if (error != null) {
                sendFatal(receiver, error);
            } else {
                receiver.send(Job.CODE_LEVEL_INFO, Job.packLevel(info));
            }
        }));
    }

    /**
     * Write edited {@code level.dat} fields back.
     *
     * @param directory a directory containing {@code level.dat}.
     * @param changes   field id to new value.
     * @param receiver  receives {@link Job#CODE_LEVEL_SAVED} or {@link Job#CODE_FATAL}.
     */
    void applyLevel(File directory, Map<String, String> changes, ResultReceiver receiver) {
        ChunkBridge.applyLevel(directory, changes, (info, error) -> main.post(() -> {
            if (error != null) {
                sendFatal(receiver, error);
            } else {
                receiver.send(Job.CODE_LEVEL_SAVED, null);
            }
        }));
    }

    private void sendFatal(ResultReceiver receiver, ChunkBridgeException error) {
        android.os.Bundle data = new android.os.Bundle();
        data.putString(Job.KEY_ERROR, describeFailure(error));
        receiver.send(Job.CODE_FATAL, data);
    }

    // ---------------------------------------------------------------------------------------------
    // Conversion
    // ---------------------------------------------------------------------------------------------

    void start(List<QueueItem> items, String formatId, boolean packMcworld, boolean deleteSource,
               boolean convertEntities) {
        if (state == State.RUNNING || items.isEmpty()) {
            return;
        }
        state = State.RUNNING;
        batch = new ArrayList<>(items);
        targetFormatId = formatId;
        this.packMcworld = packMcworld;
        this.deleteSource = deleteSource;
        this.convertEntities = convertEntities;
        index = 0;
        total = batch.size();
        currentName = batch.get(0).name;
        stage = Text.get(R.string.Controller_03);
        progress = 0D;
        succeeded = 0;
        failed = 0;
        lastError = null;
        startedAt = SystemClock.elapsedRealtime();
        roundOutputs.clear();
        notifyChanged();
        convertNext();
    }

    /**
     * Start the item at {@link #index}, or finish the batch.
     * <p>
     * Every call to this runs on the main thread, from {@link #start} or from an item's completion callback, which
     * is what makes the batch a chain: the SDK only ever has one request in flight.
     */
    private void convertNext() {
        if (state != State.RUNNING) {
            return;
        }
        if (index >= batch.size()) {
            finishBatch();
            return;
        }
        final QueueItem item = batch.get(index);
        currentName = item.name;
        progress = total == 0 ? 0D : (double) index / total;
        stage = Text.get(R.string.Controller_04);
        notifyChanged();

        ConversionRequest request;
        try {
            request = requestFor(item);
        } catch (Throwable failure) {
            // The source could not even be staged (a removed SAF grant, a folder without level.dat).
            itemFailed(item, failure);
            return;
        }
        handle = ChunkBridge.convert(request, new ConversionCallback() {
            @Override
            public void onStarted(String requestId) {
                main.post(() -> {
                    if (state != State.RUNNING) {
                        return;
                    }
                    stage = Text.get(R.string.Controller_04);
                    notifyChanged();
                });
            }

            @Override
            public void onProgress(double fraction, Stage stageValue) {
                main.post(() -> {
                    if (state != State.RUNNING) {
                        return;
                    }
                    double clamped = Double.isNaN(fraction) ? 0D : Math.max(0D, Math.min(1D, fraction));
                    progress = total == 0 ? clamped : (index + clamped) / total;
                    stage = stageKey(stageValue);
                    notifyChanged();
                });
            }

            @Override
            public void onCompleted(ConversionResult result) {
                main.post(() -> itemCompleted(item, result));
            }

            @Override
            public void onFailed(ChunkBridgeException error) {
                main.post(() -> itemFailed(item, error));
            }

            @Override
            public void onCancelled(String requestId) {
                main.post(Controller.this::finishCancelled);
            }
        });
    }

    private ConversionRequest requestFor(QueueItem item) throws Exception {
        ConversionRequest.Builder builder = new ConversionRequest.Builder()
                .targetFormatId(targetFormatId)
                .convertEntities(convertEntities)
                .outputFile(outputFor(item))
                .notificationTitle(Text.get(R.string.app_name))
                .notificationText(item.name);
        switch (item.kind) {
            case TREE:
                builder.inputFile(stageTree(app, item));
                break;
            case LOCAL:
                builder.inputFile(new File(item.path));
                break;
            case DOCUMENT:
            default:
                builder.inputUri(Uri.parse(item.uri));
                // A shared-in archive is our own copy of somebody else's file, not a file the user saved; the SDK
                // deletes the document after a successful conversion so every share does not leave a duplicate.
                builder.deleteSourceArchiveAfterSuccess(deleteSource && Inbox.isInside(app, item.path));
                break;
        }
        return builder.build();
    }

    /** The archive this item is about to produce, named and de-duplicated like every other output. */
    private File outputFor(QueueItem item) {
        boolean bedrock = targetFormatId != null
                && targetFormatId.toUpperCase(Locale.US).startsWith("BEDROCK");
        String base = Naming.render(Naming.order(app), Naming.enabled(app), Naming.separator(app),
                item.name, bedrock, System.currentTimeMillis());
        String extension = bedrock && packMcworld ? ".mcworld" : ".zip";
        return WorldFiles.unique(Paths.output(app), base + extension);
    }

    /**
     * Copy a picked folder into the app cache and return the directory that holds {@code level.dat}.
     * <p>
     * A tree URI has no stream, so this is the one source kind that must exist as real files. The copy is kept
     * keyed by entry id and reused by the conversion, so a folder world is walked once instead of twice.
     */
    private File stageTree(Context context, QueueItem item) throws Exception {
        File stage = Paths.stageFor(context, item.id);
        File root = Paths.isStageReady(stage) ? WorldFiles.locateWorldRoot(new File(stage, "in")) : null;
        if (root != null) {
            return root;
        }
        WorldFiles.delete(stage);
        File incoming = new File(stage, "in");
        if (!incoming.isDirectory() && !incoming.mkdirs()) {
            throw new IllegalStateException(Text.get(R.string.Controller_06));
        }
        WorldFiles.copyTree(context, Uri.parse(item.uri), incoming);
        root = WorldFiles.locateWorldRoot(incoming);
        if (root == null) {
            WorldFiles.delete(stage);
            throw new IllegalStateException(Text.get(R.string.Controller_02));
        }
        Paths.markStageReady(stage);
        return root;
    }

    private void itemCompleted(QueueItem item, ConversionResult result) {
        if (state != State.RUNNING) {
            return;
        }
        File output = result == null ? null : result.getOutputFile();
        if (output != null) {
            roundOutputs.add(output);
        }
        completedIds.add(item.id);
        succeeded++;
        deleteSourceAfterSuccess(item);
        cleanupStage(item);
        index++;
        notifyChanged();
        convertNext();
    }

    private void itemFailed(QueueItem item, Throwable failure) {
        if (state != State.RUNNING) {
            return;
        }
        failed++;
        lastError = describeFailure(failure);
        Log.e(TAG, "item failed: " + item.name, failure);
        cleanupStage(item);
        index++;
        notifyChanged();
        convertNext();
    }

    /**
     * Turn a thrown failure into one line the UI can show.
     *
     * <p>The SDK reports stable error codes and English messages; the messages are meant for logs, so the
     * codes are mapped to the localized strings here. Anything that is not a SDK failure keeps the older
     * {@link Job#describe(Throwable)} behaviour.
     */
    private static String describeFailure(Throwable failure) {
        if (!(failure instanceof ChunkBridgeException)) {
            return Job.describe(failure);
        }
        ChunkBridgeException error = (ChunkBridgeException) failure;
        switch (error.getCode()) {
            case INPUT_UNREADABLE:
                return Text.get(R.string.Job_06);
            case ARCHIVE_UNSUPPORTED:
                return Text.get(R.string.Job_07);
            case ARCHIVE_DAMAGED:
                return Text.get(R.string.Job_08);
            case WORLD_NOT_FOUND:
                return Text.get(R.string.Job_09);
            case FORMAT_UNSUPPORTED:
                return Text.get(R.string.Job_10);
            case OUTPUT_UNWRITABLE:
                return Text.get(R.string.Job_11);
            case OUTPUT_EXISTS:
                return Text.get(R.string.Job_12);
            case INSUFFICIENT_STORAGE:
                return Text.get(R.string.Job_13);
            case BUSY:
                return Text.get(R.string.Job_14);
            case CANCELLED:
                return Text.get(R.string.Job_15);
            case ENGINE_UNAVAILABLE:
                return Text.get(R.string.Job_16);
            case FOREGROUND_NOT_ALLOWED:
                return Text.get(R.string.Job_17);
            case OUT_OF_MEMORY:
                return Text.get(R.string.Job_02);
            case INTERNAL:
            default:
                String message = error.getMessage();
                return message == null || message.trim().isEmpty() ? Text.get(R.string.Job_01) : message;
        }
    }

    private void finishBatch() {
        state = State.IDLE;
        stage = "";
        progress = 1D;
        startedAt = 0L;
        handle = null;
        removeCompleted();
        Store.get(app).refreshOutputs();
        Paths.pruneStagesExcept(app, Collections.emptySet());
        detectPending();
        notifyChanged();
    }

    /** Cancellation reached us without the batch being reset locally (the service died first). */
    private void finishCancelled() {
        if (state != State.RUNNING) {
            return;
        }
        state = State.IDLE;
        progress = 0D;
        startedAt = 0L;
        stage = "";
        handle = null;
        batch.clear();
        notifyChanged();
    }

    void cancel() {
        if (state != State.RUNNING) {
            return;
        }
        // Chunker has no abort API: the SDK kills its own process and reports CANCELLED, so the UI resets here
        // instead of waiting for a callback that may never arrive.
        state = State.IDLE;
        progress = 0D;
        startedAt = 0L;
        stage = "";
        ConversionHandle current = handle;
        handle = null;
        batch.clear();
        try {
            if (current != null) {
                current.cancel();
            }
        } catch (RuntimeException failure) {
            Log.w(TAG, "could not cancel the conversion", failure);
        }
        notifyChanged();
    }

    /** Remove the scratch copy once the item is done; archives never create one. */
    private void cleanupStage(QueueItem item) {
        if (item.kind == QueueItem.Kind.TREE) {
            Paths.deleteStage(app, item.id);
        }
    }

    private void deleteSourceAfterSuccess(QueueItem item) {
        if (!deleteSource) {
            return;
        }
        try {
            if (item.kind == QueueItem.Kind.LOCAL && item.path != null) {
                WorldFiles.delete(new File(item.path));
            }
            // A picked tree is the user's own folder: deleting it is not ours to do, so it is left alone.
            // A DOCUMENT is deleted by the SDK, which holds the persistable grant.
        } catch (RuntimeException failure) {
            Log.w(TAG, "could not delete source " + item.name, failure);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Batch bookkeeping
    // ---------------------------------------------------------------------------------------------

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
     * Detection is skipped during a conversion, so anything shared in from another app would otherwise sit in the
     * list as an unrecognised version until it was removed and added again.
     */
    private void detectPending() {
        for (QueueItem item : new ArrayList<>(Store.get(app).queue)) {
            if (!item.isDetected()) {
                detect(app, item);
            }
        }
    }

    /** Stable keys the conversion page translates; the SDK reports an enum, not display text. */
    private static String stageKey(Stage stage) {
        if (stage == null) {
            return "terrain";
        }
        switch (stage) {
            case ENTITIES:
                return "entities";
            case COMPACT:
                return "compact";
            case FINISH:
                return "finish";
            case TERRAIN:
            case UNKNOWN:
            default:
                return "terrain";
        }
    }
}
