package com.frenetic.litechunk.engine;

import com.frenetic.litechunk.api.ChunkerEngine;
import com.frenetic.litechunk.api.Detection;
import com.frenetic.litechunk.api.FormatGroup;
import com.frenetic.litechunk.api.FormatVersion;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Runs the trimmed engine on a desktop JVM.
 * <p>
 * This does not replace testing on a device, but it catches the cheap mistakes: a broken encoder registration table,
 * a version list that fails to resolve, or a detection call that explodes on an empty folder.
 * <p>
 * {@code gradlew :engine:smokeTest}
 */
public final class EngineSmokeTest {

    public static void main(String[] args) throws Exception {
        File resourceDir = Files.createTempDirectory("engine-res").toFile();
        File resourceJava = new File(resourceDir, "java");
        //noinspection ResultOfMethodCallIgnored
        resourceJava.mkdirs();

        // Mirror what the app ships in assets/engine-res so the resource hook is exercised as well.
        File assets = new File("../app/src/main/assets/engine-res/java");
        if (assets.isDirectory()) {
            for (File file : assets.listFiles()) {
                File target = new File(resourceJava, file.getName());
                Files.copy(file.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        }
        System.out.println("resources = " + resourceJava.list().length + " file(s) from " + assets.getAbsolutePath());

        ChunkerEngine engine = new EngineImpl();
        engine.setResourceDirectory(resourceDir);

        System.out.println("engineVersion = " + engine.engineVersion());

        // Touching FontUtil initialises its static resource lookup through Res.
        System.out.println("fontWidth(ChunkBridge) = "
                + com.hivemc.chunker.conversion.encoding.java.util.FontUtil.getWidth("ChunkBridge"));

        List<FormatGroup> groups = engine.formats();
        System.out.println("groups = " + groups.size());
        for (FormatGroup group : groups) {
            System.out.println("  " + group.id + " (" + group.label + "), versions = " + group.versions.length);
            for (int i = 0; i < Math.min(3, group.versions.length); i++) {
                FormatVersion version = group.versions[i];
                System.out.println("    first[" + i + "] " + version.id + " -> " + version.version);
            }
            FormatVersion oldest = group.versions[group.versions.length - 1];
            System.out.println("    oldest " + oldest.id + " -> " + oldest.version);
        }

        File empty = Files.createTempDirectory("no-world").toFile();
        Detection detection = engine.detect(empty);
        System.out.println("detect(empty) = " + detection);

        File missing = new File(empty, "does-not-exist");
        System.out.println("detect(missing) = " + engine.detect(missing));

        if (groups.isEmpty()) {
            throw new IllegalStateException("Engine reported no output formats");
        }
        if (groups.get(0).versions.length == 0) {
            throw new IllegalStateException("First group has no versions");
        }
        System.out.println("SMOKE TEST OK");
    }
}
