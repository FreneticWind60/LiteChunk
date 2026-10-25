package com.frenetic.litechunk;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.frenetic.litechunk.api.ChunkerEngine;
import com.frenetic.litechunk.api.Detection;
import com.frenetic.litechunk.api.FormatGroup;
import com.frenetic.litechunk.api.FormatVersion;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Runs the real engine on a real device/ART, which is the only place the platform specific patches
 * (LevelDB mmap teardown, lz4 {@code safeInstance}, VarHandle-free caches) can actually be proven.
 * <p>
 * The world fixture lives in the androidTest assets so no storage permission is required.
 */
@RunWith(AndroidJUnit4.class)
public class EngineDeviceTest {

    private static final String TAG = "EngineDeviceTest";
    private static final String FIXTURE = "test_world.zip";
    private static final String REAL_WORLD = "real_world.zip";

    @Test
    public void engineLoadsAndReportsFormats() throws Exception {
        ChunkerEngine engine = loadEngine();
        Log.i(TAG, "engineVersion=" + engine.engineVersion());

        List<FormatGroup> groups = engine.formats();
        assertTrue("engine reported no output formats", groups.size() > 0);

        int versions = 0;
        for (FormatGroup group : groups) {
            versions += group.versions.length;
            Log.i(TAG, "group " + group.id + " / " + group.label + " -> " + group.versions.length + " versions");
        }
        Log.i(TAG, "total versions=" + versions + " groups=" + groups.size());
        assertTrue("no versions available", versions > 0);
    }

    /**
     * A real player world: 491 files, ~54 MB extracted, region/entities/poi plus two custom dimensions.
     * This is the case that actually stresses block entity mapping and chunk shape conversion.
     */
    @Test
    public void convertsRealWorldToBedrock() throws Exception {
        ChunkerEngine engine = loadEngine();
        Context context = context();

        File workspace = new File(context.getCacheDir(), "real-" + System.currentTimeMillis());
        File source = new File(workspace, "source");
        unzip(REAL_WORLD, source);
        File worldRoot = findHistoryRoot(source);
        assertNotNull("real world fixture has no level.dat", worldRoot);

        Detection detection = engine.detect(worldRoot);
        assertNotNull("detect returned null for real world", detection);
        Log.i(TAG, "real world detected as " + detection.formatId + " / " + detection.version);

        String bedrockId = pickTarget(engine, "BEDROCK");
        File output = new File(workspace, "bedrock");
        Stats stats = convert(engine, worldRoot, output, bedrockId);

        assertTrue("no db/ folder for real world", new File(output, "db").isDirectory());
        assertTrue("level.dat missing for real world", findFile(output, "level.dat") != null);
        Log.i(TAG, "REAL WORLD " + detection.version + " -> " + bedrockId + " ok in " + stats.millis
                + "ms, " + stats.callbacks.get() + " callbacks, peak=" + String.format("%.2f", stats.peak.get()));

        WorldFiles.delete(workspace);
    }

    @Test
    public void convertsJavaToBedrockAndBack() throws Exception {
        ChunkerEngine engine = loadEngine();
        Context context = context();

        File workspace = new File(context.getCacheDir(), "devtest-" + System.currentTimeMillis());
        File source = new File(workspace, "source");
        unzip(FIXTURE, source);

        File worldRoot = findHistoryRoot(source);
        assertNotNull("fixture does not contain level.dat", worldRoot);
        Log.i(TAG, "fixture world root=" + worldRoot.getAbsolutePath());

        String bedrockId = pickTarget(engine, "BEDROCK");
        String javaId = pickTarget(engine, "JAVA");
        Log.i(TAG, "targets: bedrock=" + bedrockId + " java=" + javaId);

        // ---- Java -> Bedrock ----
        File bedrockOut = new File(workspace, "bedrock");
        Stats bedrockStats = convert(engine, worldRoot, bedrockOut, bedrockId);
        assertTrue("no db/ folder in bedrock output", new File(bedrockOut, "db").isDirectory());
        assertTrue("level.dat missing in bedrock output", findFile(bedrockOut, "level.dat") != null);
        Log.i(TAG, "JAVA->BEDROCK ok in " + bedrockStats.millis + "ms, "
                + bedrockStats.callbacks.get() + " progress callbacks, peak="
                + String.format("%.2f", bedrockStats.peak.get()));

        // ---- Bedrock -> Java (round trip through the same engine) ----
        File bedrockRoot = findHistoryRoot(bedrockOut);
        assertNotNull("bedrock output has no level.dat", bedrockRoot);

        File javaOut = new File(workspace, "java");
        Stats javaStats = convert(engine, bedrockRoot, javaOut, javaId);
        assertTrue("level.dat missing in java output", findFile(javaOut, "level.dat") != null);
        assertTrue("no region folder in java output", findRegion(javaOut) != null);
        Log.i(TAG, "BEDROCK->JAVA ok in " + javaStats.millis + "ms, "
                + javaStats.callbacks.get() + " progress callbacks, peak="
                + String.format("%.2f", javaStats.peak.get()));

        WorldFiles.delete(workspace);
        Log.i(TAG, "ROUND TRIP ON DEVICE OK");
    }

    @Test
    public void detectsJavaWorld() throws Exception {
        ChunkerEngine engine = loadEngine();
        Context context = context();
        File workspace = new File(context.getCacheDir(), "detect-" + System.currentTimeMillis());
        unzip(FIXTURE, workspace);
        File root = findHistoryRoot(workspace);
        assertNotNull(root);

        Detection detection = engine.detect(root);
        assertNotNull("detect returned null", detection);
        Log.i(TAG, "detected " + detection.formatId + " / " + detection.formatLabel + " / " + detection.version);
        assertTrue("expected a Java format, got " + detection.formatId,
                detection.formatId.toUpperCase().contains("JAVA"));

        WorldFiles.delete(workspace);
    }

    // ---------------------------------------------------------------- helpers

    private static Context context() {
        return InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    private static ChunkerEngine loadEngine() throws Exception {
        return EngineLoader.load(context());
    }

    private static final class Stats {
        final AtomicInteger callbacks = new AtomicInteger();
        final AtomicReference<Double> peak = new AtomicReference<>(0d);
        long millis;
    }

    private static Stats convert(ChunkerEngine engine, File in, File out, String formatId) throws Exception {
        Stats stats = new Stats();
        long started = System.currentTimeMillis();
        engine.convert(in, out, formatId, (fraction, stage) -> {
            stats.callbacks.incrementAndGet();
            double value = Double.isNaN(fraction) ? 0d : fraction;
            stats.peak.updateAndGet(current -> Math.max(current, value));
        });
        stats.millis = System.currentTimeMillis() - started;
        return stats;
    }

    /** Picks the newest version whose group id starts with the given prefix. */
    private static String pickTarget(ChunkerEngine engine, String prefix) {
        for (FormatGroup group : engine.formats()) {
            if (group.id.toUpperCase().startsWith(prefix)) {
                if (group.versions.length == 0) {
                    continue;
                }
                FormatVersion first = group.versions[0];
                return first.id;
            }
        }
        fail("no format group starting with " + prefix);
        return null;
    }

    private static void unzip(String asset, File destination) throws Exception {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IllegalStateException("cannot create " + destination);
        }
        // The fixture ships inside the *test* APK, not the app APK.
        Context fixtureContext = InstrumentationRegistry.getInstrumentation().getContext();
        try (InputStream raw = fixtureContext.getAssets().open(asset);
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            byte[] buffer = new byte[64 * 1024];
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(destination, entry.getName());
                String canonical = target.getCanonicalPath();
                if (!canonical.startsWith(destination.getCanonicalPath())) {
                    throw new IllegalStateException("zip slip: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IllegalStateException("cannot create " + parent);
                }
                try (OutputStream out = new FileOutputStream(target)) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    /** Depth-first search for the folder that directly holds level.dat. */
    private static File findHistoryRoot(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        if (new File(directory, "level.dat").isFile()) {
            return directory;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            File found = findHistoryRoot(child);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static File findFile(File directory, String name) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        File direct = new File(directory, name);
        if (direct.isFile()) {
            return direct;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            File found = findFile(child, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Accepts both the legacy {@code region/} and the newer {@code dimensions/&#47;**&#47;region} layouts. */
    private static File findRegion(File directory) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        File[] children = directory.listFiles();
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if ("region".equals(child.getName())) {
                    File[] files = child.listFiles();
                    if (files != null && files.length > 0) {
                        return child;
                    }
                }
                File nested = findRegion(child);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unused")
    private static List<String> names(File[] files) {
        List<String> result = new ArrayList<>();
        if (files != null) {
            for (File file : files) {
                result.add(file.getName());
            }
        }
        return result;
    }
}
