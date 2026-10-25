package com.frenetic.litechunk.engine;

import java.io.File;
import java.io.IOException;
import java.net.URL;

/**
 * Resolves the few classpath resources Chunker needs at runtime.
 * <p>
 * A {@code DexClassLoader} cannot see non-class entries inside the dex, so {@code app/src/main/assets/engine-res}
 * is copied to a private directory on first launch and handed to the engine via
 * {@link com.frenetic.litechunk.api.ChunkerEngine#setResourceDirectory(File)}.
 */
public final class Res {

    private static volatile File baseDirectory;

    private Res() {
    }

    /**
     * @param directory directory that mirrors the {@code java/} resource layout.
     */
    public static void setBaseDirectory(File directory) {
        baseDirectory = directory;
    }

    /**
     * @param path resource path, e.g. {@code java/CavesAndCliffsHeightPack.zip}.
     * @return a URL that can be read by Guava's {@code Resources} helpers.
     * @throws IOException when the resource is missing from both the directory and the ClassLoader.
     */
    public static URL url(String path) throws IOException {
        File directory = baseDirectory;
        if (directory != null) {
            File candidate = new File(directory, path);
            if (candidate.isFile()) {
                return candidate.toURI().toURL();
            }
        }

        ClassLoader loader = Res.class.getClassLoader();
        URL fallback = loader == null ? null : loader.getResource(path);
        if (fallback == null) {
            throw new IOException("Missing engine resource: " + path);
        }
        return fallback;
    }
}
