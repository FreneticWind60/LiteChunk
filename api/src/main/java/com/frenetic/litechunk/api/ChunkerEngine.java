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
}
