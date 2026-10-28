package com.frenetic.litechunk.api;

import java.io.File;
import java.util.List;

/**
 * The stable contract between the Android app and the dynamically loaded Chunker engine.
 * <p>
 * This interface and every class in {@code com.frenetic.litechunk.api} are compiled into the APK and loaded by the
 * application ClassLoader. The engine (a trimmed Chunker build) is loaded through a {@code DexClassLoader} and
 * implements this interface. Because the ClassLoader is parent-first, the implementation resolves this interface
 * from the app ClassLoader and the cast is safe.
 * <p>
 * Keep this surface tiny: every method here is a compatibility boundary that has to be bumped when the
 * engine dex is rebuilt in an incompatible way.
 */
public interface ChunkerEngine {

    /**
     * @return the Chunker version that was compiled into the dex, e.g. {@code 1.21.0}.
     */
    String engineVersion();

    /**
     * @return the output formats supported by the engine, grouped per edition, newest version first.
     */
    List<FormatGroup> formats();

    /**
     * Detect the edition/version of a world directory (the folder that contains {@code level.dat} or a Bedrock
     * {@code db/} folder).
     *
     * @param worldDirectory the extracted world directory.
     * @return the detection result or {@code null} when the format is not recognised.
     */
    Detection detect(File worldDirectory);

    /**
     * Convert a world.
     *
     * @param inputDirectory  the extracted source world directory.
     * @param outputDirectory an empty directory the converted world is written to.
     * @param outputFormatId  one of the ids reported by {@link #formats()}.
     * @param listener        progress receiver, may be {@code null}.
     * @throws Exception when the conversion fails.
     */
    void convert(File inputDirectory, File outputDirectory, String outputFormatId, ProgressListener listener) throws Exception;

    /**
     * Point the engine at the directory that holds the engine's classpath resources which cannot travel inside a
     * dex ({@code java/default_font_width_codepoints.json} and {@code java/CavesAndCliffsHeightPack.zip}).
     *
     * @param directory the directory containing those files, keeping the {@code java/} sub-folder structure.
     */
    void setResourceDirectory(File directory);

    /**
     * Set how many worker threads the conversion pipeline may use.
     * <p>
     * Chunker ships with 8, which is fine on a desktop JVM but far too much for a phone: peak heap grows roughly
     * linearly with concurrency, and a large world at 8 threads needs well over a gigabyte. Mobile callers should
     * derive the value from {@link Runtime#maxMemory()}, see {@code MemoryPlan}.
     *
     * @param threads the number of worker threads, clamped to 1..8 by the engine.
     */
    void setConcurrency(int threads);

    /**
     * Toggle lean mode, which skips the parts of a world that cost the most memory for the least structural value:
     * lighting, entities, block entities, height maps and loot tables.
     * <p>
     * Terrain, blocks and biomes are always converted, so the result still opens in game; the game recomputes
     * lighting and containers come up empty. This is the fallback for worlds that do not fit in the heap at full
     * fidelity.
     *
     * @param lean {@code true} to skip those stages.
     */
    void setLeanMode(boolean lean);

    /**
     * Read {@code level.dat} and describe the world without modifying anything.
     * <p>
     * This is the repair path for the cases that are not conversions: a world that only needs its name, game mode or
     * declared version changed. Feeding those through {@link #convert} re-encodes every chunk, which is both slow (it
     * is a full conversion) and lossy (identifiers the engine cannot represent become placeholder blocks). Reading
     * the level alone avoids both.
     *
     * @param worldDirectory the world directory containing {@code level.dat}.
     * @return the description, never {@code null}.
     * @throws Exception when there is no readable {@code level.dat} in the directory.
     */
    LevelInfo inspectLevel(File worldDirectory) throws Exception;

    /**
     * Write the given fields back to {@code level.dat}.
     * <p>
     * Only the fields reported by {@link #inspectLevel(File)} with {@link LevelField#isEditable()} are accepted, any
     * other key is ignored rather than created. The new content is written to a temporary file and read back for
     * verification, and only then does it replace the original: a failed write leaves the world exactly as it was.
     * <p>
     * No backup of the previous file is kept. Callers that want the old header preserved must copy the archive
     * before calling this, and must tell the user that overwriting is not undoable.
     *
     * @param worldDirectory the world directory containing {@code level.dat}.
     * @param changes        field id to new value, in the same string form {@link LevelInfo} reports.
     * @throws Exception when the file cannot be read, or when the written file fails verification.
     */
    void applyLevelChanges(File worldDirectory, java.util.Map<String, String> changes) throws Exception;
}
