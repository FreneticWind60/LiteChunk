package com.frenetic.litechunk.engine;

import com.frenetic.litechunk.api.LevelField;
import com.frenetic.litechunk.api.LevelInfo;
import com.frenetic.litechunk.api.LevelNote;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Desktop verification for {@link LevelEditor}.
 * <p>
 * The details screen is the riskiest thing in the app: it writes to a file the user cannot easily restore. This runs
 * the same code path the device does, on a copy of a real world, and asserts the boring properties that matter —
 * the fields come back unchanged through a round trip, the edit lands, a backup exists, and re-opening the edited
 * file still parses.
 * <p>
 * Usage: {@code gradlew :engine:levelTest --args="<world directory>"}
 */
public final class LevelEditorTest {

    private LevelEditorTest() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: LevelEditorTest <world directory containing level.dat>");
            System.exit(2);
        }

        File world = new File(args[0]);
        File levelDat = new File(world, "level.dat");
        if (!levelDat.isFile()) {
            System.err.println("no level.dat in " + world);
            System.exit(2);
        }

        File work = Files.createTempDirectory("leveltest").toFile();
        copy(levelDat, new File(work, "level.dat"));
        System.out.println("working copy: " + work);

        // --- inspect -----------------------------------------------------------------------------
        LevelInfo info = LevelEditor.open(work).inspect();
        System.out.println("\n== inspect ==");
        System.out.println("format : " + info.formatId + " / " + info.formatLabel + " " + info.version);
        System.out.println("fields : " + info.fields.size());
        for (LevelField field : info.fields) {
            System.out.printf("  %-18s %-10s %-22s kind=%d%s%n",
                    field.id, field.labelZh, field.value == null ? "" : field.value, field.kind,
                    field.isEditable() ? "" : "  (read-only)");
        }
        System.out.println("notes  : " + info.notes.size());
        for (LevelNote note : info.notes) {
            System.out.printf("  [%d] %s%n", note.severity, note.textZh);
        }

        // --- edit --------------------------------------------------------------------------------
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put("LevelName", "LiteChunk 测试改名");
        LevelField gameType = info.field("GameType");
        if (gameType != null) {
            // Pick an option that is not the current one, so the assertion cannot pass by accident.
            changes.put("GameType", "1".equals(gameType.value) ? "0" : "1");
        }
        LevelField seed = info.field("RandomSeed");
        if (seed != null && seed.isEditable()) {
            changes.put("RandomSeed", "123456789");
        }

        System.out.println("\n== apply ==");
        for (Map.Entry<String, String> change : changes.entrySet()) {
            System.out.println("  " + change.getKey() + " -> " + change.getValue());
        }
        String backup = LevelEditor.open(work).apply(changes);
        System.out.println("backup : " + backup);

        // --- verify ------------------------------------------------------------------------------
        LevelInfo after = LevelEditor.open(work).inspect();
        int failures = 0;
        failures += expect("LevelName", "LiteChunk 测试改名", after.field("LevelName"));
        if (changes.containsKey("GameType")) {
            failures += expect("GameType", changes.get("GameType"), after.field("GameType"));
        }
        if (changes.containsKey("RandomSeed")) {
            failures += expect("RandomSeed", "123456789", after.field("RandomSeed"));
        }

        // The untouched fields must survive, or the writer is dropping data it should not touch.
        for (LevelField field : info.fields) {
            if (changes.containsKey(field.id) || !field.isEditable()) {
                continue;
            }
            LevelField now = after.field(field.id);
            if (now == null || !String.valueOf(field.value).equals(String.valueOf(now.value))) {
                System.out.printf("FAIL  %s changed from %s to %s%n", field.id, field.value,
                        now == null ? "<missing>" : now.value);
                failures++;
            }
        }

        File backupFile = new File(work, "level.dat.bak-" + backup);
        if (backup == null || !backupFile.isFile()) {
            System.out.println("FAIL  backup file missing: " + backupFile);
            failures++;
        } else if (backupFile.length() != levelDat.length()) {
            System.out.printf("FAIL  backup size %d != original %d%n", backupFile.length(), levelDat.length());
            failures++;
        } else {
            System.out.println("backup ok: " + backupFile.getName() + " (" + backupFile.length() + " bytes)");
        }

        // A second apply must still produce a readable file: the writer runs twice in a row on a device.
        String second = LevelEditor.open(work).apply(Map.of("LevelName", "第二次改名"));
        LevelInfo third = LevelEditor.open(work).inspect();
        failures += expect("LevelName", "第二次改名", third.field("LevelName"));
        System.out.println("second backup: " + second);

        // Nothing outside the allow list may be written.
        LevelEditor.open(work).apply(Map.of("DataVersion", "9999", "Player", "nope"));
        LevelInfo guarded = LevelEditor.open(work).inspect();
        LevelField dataVersion = guarded.field("DataVersion");
        if (dataVersion != null && "9999".equals(dataVersion.value)) {
            System.out.println("FAIL  DataVersion was written although it is read-only");
            failures++;
        }

        System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
        deleteTree(work);
        System.exit(failures == 0 ? 0 : 1);
    }

    /** The backup files keep the directory non-empty, so a plain delete of the directory throws on Windows. */
    private static void deleteTree(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteTree(child);
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }

    private static int expect(String id, String wanted, LevelField field) {
        if (field == null) {
            System.out.println("FAIL  " + id + " missing after write");
            return 1;
        }
        if (wanted.equals(field.value)) {
            System.out.println("ok    " + id + " = " + field.value);
            return 0;
        }
        System.out.println("FAIL  " + id + " = " + field.value + ", wanted " + wanted);
        return 1;
    }

    private static void copy(File source, File target) throws IOException {
        try (InputStream in = new FileInputStream(source);
             OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
        }
    }
}
