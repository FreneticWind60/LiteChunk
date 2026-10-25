package com.frenetic.litechunk.engine;

import com.frenetic.litechunk.api.ChunkerEngine;
import com.frenetic.litechunk.api.Detection;
import com.frenetic.litechunk.api.FormatGroup;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * End to end conversion on a desktop JVM using the Chunker integration fixtures.
 * <p>
 * Converts a Java world to the newest Bedrock format and back again, which exercises the same code path the app uses:
 * format resolution, progress polling through {@code TrackedTask#getProgress()} and the resource hook.
 * <p>
 * {@code gradlew :engine:roundTrip}
 */
public final class EngineRoundTripTest {

    public static void main(String[] args) throws Exception {
        File fixtures = new File("../Chunker-main/cli/src/test/resources/integration/worlds");
        File source = new File(fixtures, "JAVA_1_20_5.zip");
        if (!source.isFile()) {
            throw new IllegalStateException("Missing fixture: " + source.getAbsolutePath());
        }

        File work = Files.createTempDirectory("chunker-roundtrip").toFile();
        File input = new File(work, "input");
        unzip(source, input);
        File world = findWorld(input);
        System.out.println("world = " + world);

        File resourceDir = new File(work, "engine-res");
        File javaResources = new File(resourceDir, "java");
        //noinspection ResultOfMethodCallIgnored
        javaResources.mkdirs();
        File assets = new File("../app/src/main/assets/engine-res/java");
        if (assets.isDirectory()) {
            for (File file : assets.listFiles()) {
                Files.copy(file.toPath(), new File(javaResources, file.getName()).toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }

        ChunkerEngine engine = new EngineImpl();
        engine.setResourceDirectory(resourceDir);

        String bedrockFormat = latest(engine, "BEDROCK");
        String javaFormat = latest(engine, "JAVA");
        System.out.println("targets: bedrock=" + bedrockFormat + " java=" + javaFormat);

        Detection detected = engine.detect(world);
        System.out.println("detect(java world) = " + detected);
        require(detected != null && "JAVA".equals(detected.formatId), "Expected the fixture to be detected as Java");

        // Java -> Bedrock
        File bedrockOut = new File(work, "bedrock");
        AtomicInteger updates = new AtomicInteger();
        long started = System.currentTimeMillis();
        engine.convert(world, bedrockOut, bedrockFormat, (fraction, stage) -> updates.incrementAndGet());
        long bedrockMs = System.currentTimeMillis() - started;
        System.out.println("java -> bedrock done in " + bedrockMs + " ms, progress callbacks = " + updates.get());
        require(updates.get() > 1, "Expected multiple progress callbacks");
        require(new File(bedrockOut, "level.dat").isFile(), "Bedrock output has no level.dat");
        require(new File(bedrockOut, "db").isDirectory(), "Bedrock output has no db folder");
        System.out.println("bedrock output files = " + count(bedrockOut) + ", size = " + bytes(bedrockOut));

        Detection bedrockDetected = engine.detect(bedrockOut);
        System.out.println("detect(bedrock output) = " + bedrockDetected);
        require(bedrockDetected != null && "BEDROCK".equals(bedrockDetected.formatId),
                "Converted world should be detected as Bedrock");

        // Bedrock -> Java
        File javaOut = new File(work, "java");
        started = System.currentTimeMillis();
        engine.convert(bedrockOut, javaOut, javaFormat, (fraction, stage) -> {
        });
        long javaMs = System.currentTimeMillis() - started;
        System.out.println("bedrock -> java done in " + javaMs + " ms");
        require(new File(javaOut, "level.dat").isFile(), "Java output has no level.dat");
        // Modern Java targets use the per-dimension layout, older ones keep a single region folder.
        boolean hasRegion = new File(javaOut, "region").isDirectory()
                || new File(javaOut, "dimensions/minecraft/overworld/region").isDirectory();
        require(hasRegion, "Java output has no region data");
        System.out.println("java output files = " + count(javaOut) + ", size = " + bytes(javaOut));

        Detection javaDetected = engine.detect(javaOut);
        System.out.println("detect(java output) = " + javaDetected);
        require(javaDetected != null && "JAVA".equals(javaDetected.formatId), "Converted world should be Java");

        System.out.println("ROUND TRIP OK  (work dir: " + work.getAbsolutePath() + ")");
    }

    private static String latest(ChunkerEngine engine, String groupId) {
        for (FormatGroup group : engine.formats()) {
            if (group.id.equals(groupId) && group.versions.length > 0) {
                return group.versions[0].id;
            }
        }
        throw new IllegalStateException("No formats for " + groupId);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static File findWorld(File directory) {
        if (new File(directory, "level.dat").isFile()) {
            return directory;
        }
        File[] children = directory.listFiles(File::isDirectory);
        if (children != null) {
            for (File child : children) {
                if (new File(child, "level.dat").isFile()) {
                    return child;
                }
            }
        }
        throw new IllegalStateException("No level.dat under " + directory);
    }

    private static void unzip(File archive, File destination) throws IOException {
        //noinspection ResultOfMethodCallIgnored
        destination.mkdirs();
        String root = destination.getCanonicalPath();
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive.toPath())))) {
            byte[] buffer = new byte[64 * 1024];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                File target = new File(destination, entry.getName());
                if (!target.getCanonicalPath().startsWith(root)) {
                    throw new IOException("Illegal entry " + entry.getName());
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                    int read;
                    while ((read = zip.read(buffer)) > 0) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static int count(File directory) {
        int total = 0;
        File[] children = directory.listFiles();
        if (children == null) {
            return 0;
        }
        for (File child : children) {
            total += child.isDirectory() ? count(child) : 1;
        }
        return total;
    }

    private static long bytes(File directory) {
        long total = 0L;
        File[] children = directory.listFiles();
        if (children == null) {
            return 0L;
        }
        for (File child : children) {
            total += child.isDirectory() ? bytes(child) : child.length();
        }
        return total;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }
}
