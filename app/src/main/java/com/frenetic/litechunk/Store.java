package com.frenetic.litechunk;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-memory state shared by both pages: the conversion list and the contents of {@code output/}.
 * <p>
 * The list is mirrored to {@code input.json} asynchronously. Writing a few hundred bytes cannot possibly be the thing
 * that makes a conversion slow, but it does mean the list survives the activity being killed while a conversion runs,
 * which on a phone with a small heap is a real possibility.
 */
final class Store {

    /** One finished archive in {@code output/}. */
    static final class OutputFile {
        final File file;
        final String name;
        final long size;
        final boolean bedrock;
        final long modified;

        OutputFile(File file, long size, boolean bedrock) {
            this.file = file;
            this.name = file.getName();
            this.size = size;
            this.bedrock = bedrock;
            this.modified = file.lastModified();
        }

        String editionLabel() {
            return bedrock ? "基岩版" : "Java 版";
        }
    }

    interface Listener {
        /** Queue contents or per-item detection changed. */
        void onQueueChanged();

        /** {@code output/} was rescanned or files were deleted. */
        void onOutputsChanged();
    }

    private static Store instance;

    static synchronized Store get(Context context) {
        if (instance == null) {
            instance = new Store(context.getApplicationContext());
        }
        return instance;
    }

    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "litechunk-store");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    final List<QueueItem> queue = new ArrayList<>();
    final List<OutputFile> outputs = new ArrayList<>();

    private Store(Context app) {
        this.app = app;
    }

    // ---------------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------------

    void start() {
        loadQueue();
        refreshOutputs();
    }

    void addListener(Listener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void notifyQueue() {
        main.post(() -> {
            for (Listener listener : listeners) {
                listener.onQueueChanged();
            }
        });
    }

    void notifyOutputs() {
        main.post(() -> {
            for (Listener listener : listeners) {
                listener.onOutputsChanged();
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Conversion list
    // ---------------------------------------------------------------------------------------------

    /** @return true when the item was added, false when the same source is already listed. */
    boolean add(QueueItem item) {
        for (QueueItem existing : queue) {
            if (existing.sameSource(item)) {
                return false;
            }
        }
        queue.add(item);
        saveQueue();
        notifyQueue();
        return true;
    }

    void addAll(List<QueueItem> items) {
        boolean changed = false;
        for (QueueItem item : items) {
            boolean duplicate = false;
            for (QueueItem existing : queue) {
                if (existing.sameSource(item)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                queue.add(item);
                changed = true;
            }
        }
        if (changed) {
            saveQueue();
            notifyQueue();
        }
    }

    QueueItem removeAt(int index) {
        if (index < 0 || index >= queue.size()) {
            return null;
        }
        QueueItem removed = queue.remove(index);
        saveQueue();
        notifyQueue();
        return removed;
    }

    void clearQueue() {
        if (queue.isEmpty()) {
            return;
        }
        queue.clear();
        saveQueue();
        notifyQueue();
    }

    /** Remove every entry whose id is in {@code ids}; used when a batch finishes. */
    void removeIds(java.util.Set<String> ids) {
        boolean changed = false;
        for (int position = queue.size() - 1; position >= 0; position--) {
            if (ids.contains(queue.get(position).id)) {
                queue.remove(position);
                changed = true;
            }
        }
        if (changed) {
            saveQueue();
            notifyQueue();
        }
    }

    /**
     * Move an output archive back into the conversion list.
     * <p>
     * {@code bedrock} comes from the output scan, so the row can show "基岩版" straight away instead of "未识别版本"
     * — the exact version is not known without reading the world, and re-reading a 100MB archive just to fill in a
     * label would make the button feel broken. The converter detects the real source format on its own.
     */
    boolean enqueue(File archive, boolean bedrock) {
        QueueItem item = QueueItem.fromLocal(archive);
        item.formatId = bedrock ? "BEDROCK" : "JAVA";
        item.formatLabel = bedrock ? "基岩版" : "Java 版";
        return add(item);
    }

    /**
     * Record what a source turned out to be.
     * <p>
     * Saved immediately: detection runs in a second process and its result only lived in memory, so a process
     * restart (or the system reclaiming the app while the user is elsewhere) turned a freshly detected row back
     * into "未识别版本" and made the conversion look like it had lost its input.
     */
    void markDetected(String itemId, String formatId, String formatLabel, String version) {
        if (itemId == null) {
            return;
        }
        for (QueueItem item : queue) {
            if (item.id.equals(itemId)) {
                item.formatId = formatId;
                item.formatLabel = formatLabel;
                item.version = version;
                saveQueue();
                break;
            }
        }
        notifyQueue();
    }

    // ---------------------------------------------------------------------------------------------
    // Output folder
    // ---------------------------------------------------------------------------------------------

    void refreshOutputs() {
        File directory = Paths.output(app);
        File[] files = directory.listFiles((dir, name) -> {
            String lower = name.toLowerCase(java.util.Locale.ROOT);
            return lower.endsWith(".mcworld") || lower.endsWith(".zip");
        });
        List<OutputFile> scanned = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                if (!file.isFile()) {
                    continue;
                }
                String lower = file.getName().toLowerCase(java.util.Locale.ROOT);
                boolean bedrock = lower.endsWith(".mcworld") || WorldFiles.isBedrockArchive(file);
                scanned.add(new OutputFile(file, file.length(), bedrock));
            }
        }
        scanned.sort((left, right) -> Long.compare(right.modified, left.modified));
        outputs.clear();
        outputs.addAll(scanned);
        notifyOutputs();
    }

    long outputTotalSize() {
        long total = 0L;
        for (OutputFile output : outputs) {
            total += output.size;
        }
        return total;
    }

    void deleteOutputs(List<OutputFile> targets) {
        List<File> files = new ArrayList<>();
        for (OutputFile target : targets) {
            files.add(target.file);
        }
        io.execute(() -> {
            for (File file : files) {
                WorldFiles.delete(file);
            }
            refreshOutputs();
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------------------------------

    private void loadQueue() {
        File state = Paths.inputState(app);
        if (!state.isFile()) {
            return;
        }
        try (Reader reader = new InputStreamReader(new FileInputStream(state), StandardCharsets.UTF_8)) {
            StringBuilder text = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) > 0) {
                text.append(buffer, 0, read);
            }
            JSONObject root = new JSONObject(text.toString());
            JSONArray items = root.optJSONArray("items");
            if (items != null) {
                for (int index = 0; index < items.length(); index++) {
                    JSONObject json = items.optJSONObject(index);
                    if (json != null) {
                        queue.add(QueueItem.fromJson(json));
                    }
                }
            }
        } catch (Exception failure) {
            // A corrupt list must never stop the app from starting; the worst case is an empty list.
            queue.clear();
        }
        notifyQueue();
    }

    private void saveQueue() {
        List<QueueItem> snapshot = new ArrayList<>(queue);
        io.execute(() -> {
            try {
                JSONArray items = new JSONArray();
                for (QueueItem item : snapshot) {
                    items.put(item.toJson());
                }
                JSONObject root = new JSONObject();
                root.put("version", 1);
                root.put("items", items);

                File state = Paths.inputState(app);
                File temporary = new File(state.getAbsolutePath() + ".tmp");
                try (Writer writer = new OutputStreamWriter(new FileOutputStream(temporary), StandardCharsets.UTF_8)) {
                    writer.write(root.toString());
                }
                if (!temporary.renameTo(state)) {
                    WorldFiles.delete(temporary);
                }
            } catch (Exception ignored) {
                // Losing the persisted list is not worth interrupting the UI for.
            }
        });
    }

    List<QueueItem> snapshotQueue() {
        return Collections.unmodifiableList(new ArrayList<>(Arrays.asList(queue.toArray(new QueueItem[0]))));
    }
}
