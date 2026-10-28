package com.frenetic.litechunk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.Process;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.provider.DocumentsContract;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.frenetic.litechunk.api.ChunkerEngine;
import com.frenetic.litechunk.api.Detection;
import com.frenetic.litechunk.api.LevelInfo;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Runs conversions inside a dedicated {@code :chunker} process.
 * <p>
 * Isolation buys three things: a second heap budget for large worlds, a crash boundary that keeps the UI alive, and a
 * cancel that actually works (Chunker has no abort API, so cancelling terminates this process).
 * <p>
 * A batch is one service session: every item is materialised into flush space, converted, packed into
 * {@code output/} and its flush directory deleted before the next item starts, so peak disk usage is one item's worth
 * rather than the sum of the list.
 */
public class ConversionService extends Service {

    private static final String TAG = "ConversionService";
    private static final String CHANNEL_ID = "conversion";
    private static final int NOTIFICATION_ID = 4711;

    // The engine survives across service instances so a second conversion does not pay for class loading again.
    private static final Object ENGINE_LOCK = new Object();
    private static ChunkerEngine engine;

    /**
     * One job at a time, in arrival order.
     * <p>
     * A plain "is a worker alive?" check used to reject the newcomer, and that lost work: the conversion page asks
     * for the format list ({@code INFO}) the moment it is created, which on a cold start lands within the same
     * millisecond as a detection ({@code DETECT}) triggered by a shared archive — the detection was then refused and
     * the entry stayed "未识别版本" forever. Serialising instead keeps the engine single-threaded (it has per-instance
     * state) without ever dropping a request.
     */
    private final java.util.concurrent.ExecutorService jobs =
            java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "chunker-job");
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            });
    private volatile boolean foreground;
    /** A conversion is in flight; a second one is refused (that is a real conflict, unlike two detections). */
    private volatile boolean converting;
    /** Last numeric fraction the engine reported, so a stage-only update can carry it along. */
    private volatile float lastFraction;
    private volatile ResultReceiver currentReceiver;

    /** Reported while a world is being unpacked, which has no numeric progress of its own. */
    interface StageListener {
        void onStage(String stage);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null && manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID, "存档转换", NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("显示转换进度");
            channel.setShowBadge(false);
            manager.createNotificationChannel(channel);
        }
    }

    @Override
    public void onDestroy() {
        jobs.shutdown();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        ResultReceiver receiver = readReceiver(intent);

        if (Job.ACTION_CANCEL.equals(action)) {
            Log.w(TAG, "cancel requested, terminating :chunker");
            stopForegroundCompat();
            Process.killProcess(Process.myPid());
            return START_NOT_STICKY;
        }

        if (Job.ACTION_CONVERT.equals(action)) {
            if (converting) {
                Log.w(TAG, "refused a second conversion");
                sendError(receiver, Job.CODE_FATAL, "已有转换任务正在进行");
                stopSelfResult(startId);
                return START_NOT_STICKY;
            }
            converting = true;
            startForeground(NOTIFICATION_ID, buildNotification("准备转换…", 0F, true));
            foreground = true;
        }

        final String path = intent.getStringExtra(Job.EXTRA_PATH);
        final String format = intent.getStringExtra(Job.EXTRA_FORMAT);
        final String itemId = intent.getStringExtra(Job.KEY_ITEM_ID);
        final String flushPath = intent.getStringExtra(Job.EXTRA_FLUSH);
        final boolean keepFlush = intent.getBooleanExtra(Job.EXTRA_KEEP_FLUSH, false);
        final List<QueueItem> items = readItems(intent);
        final boolean packMcworld = intent.getBooleanExtra(Job.EXTRA_PACK_MCWORLD, true);
        final boolean deleteSource = intent.getBooleanExtra(Job.EXTRA_DELETE_SOURCE, false);
        final NamingSettings naming = new NamingSettings(
                intent.getStringExtra(Job.EXTRA_NAMING_ORDER),
                intent.getStringExtra(Job.EXTRA_NAMING_ENABLED),
                intent.getStringExtra(Job.EXTRA_NAMING_SEPARATOR));
        final String[] levelIds = intent.getStringArrayExtra(Job.EXTRA_LEVEL_IDS);
        final String[] levelValues = intent.getStringArrayExtra(Job.EXTRA_LEVEL_VALUES);

        jobs.execute(() -> {
            Log.i(TAG, "job start " + action);
            try {
                if (Job.ACTION_INFO.equals(action)) {
                    sendInfo(receiver);
                } else if (Job.ACTION_DETECT.equals(action)) {
                    sendDetection(receiver, path, itemId, flushPath, keepFlush);
                } else if (Job.ACTION_CONVERT.equals(action)) {
                    runQueue(receiver, items, format, packMcworld, deleteSource, naming);
                } else if (Job.ACTION_INSPECT.equals(action)) {
                    sendLevelInfo(receiver, path);
                } else if (Job.ACTION_APPLY_LEVEL.equals(action)) {
                    applyLevelChanges(receiver, path, levelIds, levelValues);
                }
            } catch (Throwable throwable) {
                Log.e(TAG, "job failed", throwable);
                sendError(receiver, Job.CODE_FATAL, Job.describe(throwable));
            } finally {
                if (Job.ACTION_CONVERT.equals(action)) {
                    converting = false;
                    stopForegroundCompat();
                }
                // Keeps the service alive while a newer startId is still queued behind this job.
                stopSelfResult(startId);
            }
        });

        return START_NOT_STICKY;
    }

    @SuppressWarnings("unchecked")
    private static List<QueueItem> readItems(Intent intent) {
        java.io.Serializable serializable = intent.getSerializableExtra(Job.EXTRA_ITEMS);
        if (serializable instanceof ArrayList<?>) {
            try {
                return (List<QueueItem>) serializable;
            } catch (ClassCastException ignored) {
                // Fall through to an empty batch.
            }
        }
        return new ArrayList<>();
    }

    // ---------------------------------------------------------------------------------------------
    // Engine handshake
    // ---------------------------------------------------------------------------------------------

    private void sendInfo(ResultReceiver receiver) throws Exception {
        if (receiver == null) {
            return;
        }
        receiver.send(Job.CODE_INFO, Formats.of(engine()).toBundle());
    }

    /**
     * Detect one world and report the result.
     * <p>
     * The caller stages the world into flush space first, so it also hands over that directory. Deleting it has to
     * happen here, after the reader is done with it: the UI used to delete it right after dispatching this intent,
     * which raced the service and made every archive come back as "unrecognised". When {@code keepFlush} is set the
     * directory is left in place instead — it is keyed by entry id, so the conversion of that same entry finds it and
     * skips unpacking the world again.
     */
    private void sendDetection(ResultReceiver receiver, String path, String itemId, String flushPath,
                               boolean keepFlush) throws Exception {
        try {
            if (receiver == null) {
                Log.w(TAG, "no receiver for detection of " + path);
                return;
            }
            Detection detection = engine().detect(new File(path));
            Log.i(TAG, "detected " + path + " -> "
                    + (detection == null ? "null" : detection.formatId + " " + detection.version));
            Bundle bundle = new Bundle();
            if (detection != null) {
                bundle.putString(Job.KEY_FORMAT_ID, detection.formatId);
                bundle.putString(Job.KEY_FORMAT_LABEL, detection.formatLabel);
                bundle.putString(Job.KEY_VERSION, detection.version);
            }
            bundle.putString(Job.KEY_ITEM_ID, itemId);
            receiver.send(Job.CODE_DETECTED, bundle);
        } finally {
            if (!keepFlush && flushPath != null && !flushPath.isEmpty()) {
                WorldFiles.delete(new File(flushPath));
            }
        }
    }

    /**
     * Describe the {@code level.dat} in a directory and send it back.
     * <p>
     * Runs in the engine process for the same reason the format list does: the UI process never loads Chunker, so
     * anything that needs to parse a world happens here and crosses the boundary as primitives.
     */
    private void sendLevelInfo(ResultReceiver receiver, String path) throws Exception {
        if (receiver == null) {
            Log.w(TAG, "no receiver for inspection of " + path);
            return;
        }
        LevelInfo info = engine().inspectLevel(new File(path));
        receiver.send(Job.CODE_LEVEL_INFO, Job.packLevel(info));
    }

    /** Write edited fields back, and report the backup suffix so the UI can say where the old copy is. */
    private void applyLevelChanges(ResultReceiver receiver, String path, String[] ids, String[] values) throws Exception {
        if (receiver == null) {
            Log.w(TAG, "no receiver for level write of " + path);
            return;
        }
        java.util.Map<String, String> changes = new java.util.LinkedHashMap<>();
        if (ids != null && values != null) {
            for (int i = 0; i < ids.length && i < values.length; i++) {
                changes.put(ids[i], values[i]);
            }
        }
        // The directory the UI hands over only holds level.dat; it exists because the world itself is an archive.
        // No backup is produced: the details screen offers "save as" instead, and writes the archive through a
        // temporary file, so this step only has to be atomic, not recoverable.
        engine().applyLevelChanges(new File(path), changes);
        receiver.send(Job.CODE_LEVEL_SAVED, new Bundle());
    }

    // ---------------------------------------------------------------------------------------------
    // Batch conversion
    // ---------------------------------------------------------------------------------------------
    private void runQueue(ResultReceiver receiver, List<QueueItem> items, String format,
                          boolean packMcworld, boolean deleteSource, NamingSettings naming) {
        if (items.isEmpty()) {
            sendQueueDone(receiver, 0, 0);
            return;
        }

        boolean bedrockTarget = format != null && format.toUpperCase(Locale.ROOT).startsWith("BEDROCK");
        int succeeded = 0;
        int failed = 0;
        currentReceiver = receiver;

        // Stages are the unpacked copies detection left behind. Anything not in this batch is dead weight, and this
        // worker is the only reader of those directories, so it is the only safe place to remove them.
        java.util.Set<String> batchIds = new java.util.HashSet<>();
        for (QueueItem item : items) {
            batchIds.add(item.id);
        }
        Paths.pruneStagesExcept(this, batchIds);

        for (int index = 0; index < items.size(); index++) {
            QueueItem item = items.get(index);
            Bundle started = new Bundle();
            started.putInt(Job.KEY_INDEX, index);
            started.putInt(Job.KEY_COUNT, items.size());
            started.putString(Job.KEY_NAME, item.name);
            started.putString(Job.KEY_ITEM_ID, item.id);
            if (receiver != null) {
                receiver.send(Job.CODE_ITEM_START, started);
            }

            // Detection already unpacked this entry into a directory named after it; using that copy here is the
            // difference between one and two full unzips of the source. It is rebuilt when missing or incomplete
            // (entry converted before, cache cleared, detection refused because another batch was running).
            File flush = Paths.stageFor(this, item.id);
            File worldRoot = Paths.isStageReady(flush) ? WorldFiles.locateWorldRoot(new File(flush, "in")) : null;
            boolean staged = worldRoot != null;
            if (!staged) {
                WorldFiles.delete(flush);
                flush = Paths.newFlushDirectory(this);
            }

            long itemStarted = SystemClock.elapsedRealtime();
            // The per-item number used to be a single total, which made every performance question a guess. The batch
            // has exactly three phases (stage the source, run the engine, pack the result), so each one gets a mark;
            // WorldFiles.zip logs through the same line, which is how the packing change is judged.
            long mark = itemStarted;
            long readMs = 0L;
            long convertMs = 0L;
            long packMs = 0L;
            long inputBytes = 0L;
            lastFraction = 0F;
            try {
                if (!staged) {
                    notifyStage("正在读取存档…");
                    File incoming = new File(flush, "in");
                    worldRoot = materialize(this, item, incoming, this::notifyStage);
                    if (worldRoot == null) {
                        throw new IllegalStateException("没有找到世界数据（缺少 level.dat），请确认选中的是世界根目录");
                    }
                    readMs = SystemClock.elapsedRealtime() - mark;
                    mark += readMs;
                }
                inputBytes = WorldFiles.size(worldRoot);

                File output = new File(flush, "out");
                notifyStage("已读取存档，正在启动引擎…");
                convert(receiver, worldRoot, output, format);
                convertMs = SystemClock.elapsedRealtime() - mark;
                mark += convertMs;

                notifyStage("正在打包…");
                File archive = pack(output, item, bedrockTarget, packMcworld, naming);
                packMs = SystemClock.elapsedRealtime() - mark;

                Bundle done = new Bundle();
                done.putInt(Job.KEY_INDEX, index);
                done.putString(Job.KEY_ITEM_ID, item.id);
                done.putString(Job.KEY_OUTPUT, archive.getAbsolutePath());
                done.putLong(Job.KEY_ELAPSED, SystemClock.elapsedRealtime() - itemStarted);
                done.putLong(Job.KEY_SIZE, archive.length());
                if (receiver != null) {
                    receiver.send(Job.CODE_ITEM_DONE, done);
                }
                Log.i(TAG, describeTiming(item.name, inputBytes, archive.length(), readMs, convertMs, packMs,
                        SystemClock.elapsedRealtime() - itemStarted, staged));
                succeeded++;

                if (deleteSource) {
                    deleteSource(item);
                }
                // A shared-in archive is our own copy of somebody else's file, not a file the user saved; leaving it
                // behind after a successful conversion turns every share into a permanent duplicate.
                if (item.kind == QueueItem.Kind.LOCAL && Inbox.isInside(this, item.path)) {
                    WorldFiles.delete(new File(item.path));
                }
            } catch (Throwable throwable) {
                Log.e(TAG, "item failed: " + item.name, throwable);
                failed++;
                Bundle error = new Bundle();
                error.putInt(Job.KEY_INDEX, index);
                error.putString(Job.KEY_ITEM_ID, item.id);
                error.putString(Job.KEY_NAME, item.name);
                error.putString(Job.KEY_ERROR, Job.describe(throwable));
                if (receiver != null) {
                    receiver.send(Job.CODE_ITEM_ERROR, error);
                }
            } finally {
                WorldFiles.delete(flush);
            }
        }

        sendQueueDone(receiver, succeeded, failed);
    }

    private void sendQueueDone(ResultReceiver receiver, int succeeded, int failed) {
        Bundle bundle = new Bundle();
        bundle.putInt(Job.KEY_SUCCEEDED, succeeded);
        bundle.putInt(Job.KEY_FAILED, failed);
        if (receiver != null) {
            receiver.send(Job.CODE_QUEUE_DONE, bundle);
        }
    }

    /**
     * Copy a queue entry into real files under {@code destination}.
     * <p>
     * The world list only stores where a world came from, never a copy of it, so this is where the bytes are actually
     * read: a picked archive is unzipped, a picked folder is copied tree by tree.
     *
     * @return the directory holding {@code level.dat}, or null when the source is not a world.
     */
    static File materialize(Context context, QueueItem item, File destination, StageListener stage) throws Exception {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IllegalStateException("无法创建临时目录");
        }
        switch (item.kind) {
            case DOCUMENT: {
                if (stage != null) {
                    stage.onStage("正在解压存档…");
                }
                Uri uri = Uri.parse(item.uri);
                try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                    if (input == null) {
                        throw new IllegalStateException("无法读取所选文件");
                    }
                    WorldFiles.unzip(input, destination);
                }
                break;
            }
            case TREE: {
                if (stage != null) {
                    stage.onStage("正在复制世界文件夹…");
                }
                WorldFiles.copyTree(context, Uri.parse(item.uri), destination);
                break;
            }
            case LOCAL:
            default: {
                File source = new File(item.path);
                if (source.isDirectory()) {
                    if (stage != null) {
                        stage.onStage("正在复制世界文件夹…");
                    }
                    WorldFiles.copyTree(source, destination);
                } else {
                    if (stage != null) {
                        stage.onStage("正在解压存档…");
                    }
                    WorldFiles.unzip(source, destination);
                }
                break;
            }
        }
        return WorldFiles.locateWorldRoot(destination);
    }

    /**
     * Pack the converted folder into {@code output/}.
     * <p>
     * Bedrock archives get the {@code .mcworld} extension unless the user turned that off, in which case they get a
     * plain {@code .zip} of the same contents; Java archives are always {@code .zip}. The base name comes from the
     * user's naming template, and {@link WorldFiles#unique} still runs afterwards so a template that produces the
     * same name twice keeps both archives instead of overwriting the first.
     */
    private File pack(File outputDirectory, QueueItem item, boolean bedrockTarget, boolean packMcworld,
                      NamingSettings naming) throws Exception {
        File directory = Paths.output(this);
        String extension = bedrockTarget && packMcworld ? ".mcworld" : ".zip";
        String base = Naming.render(naming.order, naming.enabled, naming.separator,
                item.name, bedrockTarget, System.currentTimeMillis());
        // Already sanitised and extension-free by Naming.render(): running baseName() again would cut the name at the
        // last dot, and a world called "我的世界 v1.2" would lose everything after it.
        File target = WorldFiles.unique(directory, base + extension);
        WorldFiles.zip(outputDirectory, target);
        return target;
    }

    /**
     * One log line per converted world with the phase split.
     * <p>
     * Times are wall clock, so they include waiting on storage and on the collector. Throughput is measured against
     * the staged source rather than the output: the output size changes with the target format, which would make two
     * different targets look like two different speeds.
     *
     * @param name        the world name, for matching the line against the queue.
     * @param inputBytes  size of the staged source world.
     * @param outputBytes size of the produced archive.
     * @param readMs      staging (unzip / copy) time.
     * @param convertMs   engine time.
     * @param packMs      packing time.
     * @param totalMs     wall clock for the whole item.
     * @param staged      whether the copy made for detection was reused, i.e. whether staging was skipped.
     * @return a single line suitable for logcat.
     */
    private static String describeTiming(String name, long inputBytes, long outputBytes,
                                         long readMs, long convertMs, long packMs, long totalMs, boolean staged) {
        double seconds = totalMs / 1000D;
        double megabytesPerSecond = seconds <= 0 ? 0 : inputBytes / 1048576D / seconds;
        return String.format(Locale.ROOT,
                "timing %s: read=%.1fs%s convert=%.1fs pack=%.1fs total=%.1fs in=%.1fMB out=%.1fMB %.2fMB/s",
                name, readMs / 1000D, staged ? "(staged)" : "",
                convertMs / 1000D, packMs / 1000D, seconds,
                inputBytes / 1048576D, outputBytes / 1048576D, megabytesPerSecond);
    }

    /** Naming settings travelling with the batch; see {@link Job#EXTRA_NAMING_ORDER}. */
    private static final class NamingSettings {
        final String order;
        final String enabled;
        final String separator;

        NamingSettings(String order, String enabled, String separator) {
            this.order = order == null ? Naming.DEFAULT_ORDER : order;
            this.enabled = enabled == null ? Naming.DEFAULT_ENABLED : enabled;
            this.separator = separator == null ? Naming.DEFAULT_SEPARATOR : separator;
        }
    }

    private void deleteSource(QueueItem item) {
        try {
            if (item.kind == QueueItem.Kind.DOCUMENT) {
                DocumentsContract.deleteDocument(getContentResolver(), Uri.parse(item.uri));
            } else if (item.kind == QueueItem.Kind.LOCAL && item.path != null) {
                WorldFiles.delete(new File(item.path));
            }
            // A picked tree is the user's own folder: deleting it is not ours to do, so it is left alone.
        } catch (Exception failure) {
            Log.w(TAG, "could not delete source " + item.name, failure);
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Engine invocation
    // ---------------------------------------------------------------------------------------------

    private void convert(ResultReceiver receiver, File inputDirectory, File outputDirectory, String format) throws Exception {
        ChunkerEngine chunker = engine();

        // Decide the memory profile from the heap this process was actually granted. largeHeap is the ceiling and it
        // differs per device, so the number cannot be a constant.
        MemoryPlan plan = MemoryPlan.forCurrentProcess();
        Log.i(TAG, "memory plan: " + plan.describe());

        try {
            convert(chunker, receiver, inputDirectory, outputDirectory, format, plan);
        } catch (Throwable failure) {
            if (!MemoryPlan.isOutOfMemory(failure)) {
                throw failure;
            }
            if (plan.lean) {
                // Already at the floor: nothing left to give up without producing a broken world.
                Log.e(TAG, "out of memory even in lean mode", failure);
                throw failure;
            }

            Log.w(TAG, "out of memory at " + plan.describe() + ", retrying lean", failure);
            MemoryPlan lean = plan.leanRetry();
            notifyStage("内存不足，正在降低画质重试（" + lean.threads + " 线程 / 精简模式）");

            // The failed run left a partial world behind; clear it and give the heap a chance to compact before the
            // retry, otherwise the retry starts from an already fragmented heap.
            WorldFiles.delete(outputDirectory);
            if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
                throw new IllegalStateException("无法创建输出目录");
            }
            System.gc();
            convert(chunker, receiver, inputDirectory, outputDirectory, format, lean);
        }
    }

    /**
     * Run one conversion, reporting progress.
     *
     * @throws Exception when the engine fails, including {@link OutOfMemoryError} wrapped by the task pipeline.
     */
    private void convert(ChunkerEngine chunker, ResultReceiver receiver, File inputDirectory, File outputDirectory,
                         String format, MemoryPlan plan) throws Exception {
        chunker.setConcurrency(plan.threads);
        chunker.setLeanMode(plan.lean);

        if (!outputDirectory.isDirectory() && !outputDirectory.mkdirs()) {
            throw new IllegalStateException("无法创建输出目录");
        }

        final long[] lastEmit = {0L};
        chunker.convert(inputDirectory, outputDirectory, format, (fraction, stage) -> {
            long now = SystemClock.elapsedRealtime();
            if (now - lastEmit[0] < 180L && fraction < 0.999D) {
                return;
            }
            lastEmit[0] = now;
            lastFraction = (float) fraction;

            Bundle bundle = new Bundle();
            bundle.putFloat(Job.KEY_PROGRESS, (float) fraction);
            bundle.putString(Job.KEY_STAGE, stage);
            if (receiver != null) {
                try {
                    receiver.send(Job.CODE_PROGRESS, bundle);
                } catch (RuntimeException ignored) {
                    // UI process went away, keep converting into the output folder.
                }
            }
            notifyProgress(stage, fraction);
        });
    }

    /** Report a stage that has no meaningful percentage of its own, e.g. unpacking or packing. */
    private void notifyStage(String stage) {
        ResultReceiver receiver = currentReceiver;
        if (receiver != null) {
            Bundle bundle = new Bundle();
            bundle.putFloat(Job.KEY_PROGRESS, lastFraction);
            bundle.putString(Job.KEY_STAGE, stage);
            try {
                receiver.send(Job.CODE_PROGRESS, bundle);
            } catch (RuntimeException ignored) {
                // UI gone.
            }
        }
        notifyProgress(stage, -1D);
    }

    private ChunkerEngine engine() throws Exception {
        ChunkerEngine local = engine;
        if (local != null) {
            return local;
        }
        synchronized (ENGINE_LOCK) {
            if (engine == null) {
                engine = EngineLoader.load(getApplicationContext());
            }
            return engine;
        }
    }

    private static void sendError(ResultReceiver receiver, int code, String message) {
        if (receiver == null) {
            return;
        }
        Bundle bundle = new Bundle();
        bundle.putString(Job.KEY_ERROR, message);
        try {
            receiver.send(code, bundle);
        } catch (RuntimeException ignored) {
            // The UI is gone, the failure is already logged.
        }
    }

    private static ResultReceiver readReceiver(Intent intent) {
        Parcelable parcelable = intent.getParcelableExtra(Job.EXTRA_RECEIVER);
        return parcelable instanceof ResultReceiver ? (ResultReceiver) parcelable : null;
    }

    // ---------------------------------------------------------------------------------------------
    // Notification
    // ---------------------------------------------------------------------------------------------

    private void notifyProgress(String stage, double fraction) {
        if (!foreground) {
            return;
        }
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            boolean indeterminate = fraction < 0D;
            manager.notify(NOTIFICATION_ID,
                    buildNotification(stage, indeterminate ? 0F : (float) fraction, indeterminate));
        }
    }

    private Notification buildNotification(String text, float fraction, boolean indeterminate) {
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("LiteChunk 正在转换")
                .setContentText(text)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setProgress(1000, Math.round(fraction * 1000F), indeterminate)
                .build();
    }

    private void stopForegroundCompat() {
        if (foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);
            foreground = false;
        }
    }
}
