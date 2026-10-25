package com.frenetic.litechunk;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.frenetic.litechunk.api.ChunkerEngine;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import dalvik.system.DexClassLoader;

/**
 * Loads the pre-dexed Chunker engine that ships inside {@code assets/chunker.jar}.
 * <p>
 * Notes that matter for correctness:
 * <ul>
 *     <li>{@link DexClassLoader} cannot read an asset directly, the jar is copied to {@code code_cache} first.</li>
 *     <li>Android 14+ rejects dynamically loaded code from writable files, so the copy is marked read-only.</li>
 *     <li>The loader is parent-first, therefore the engine resolves {@code com.frenetic.litechunk.api} from the app and the
 *     interface cast works without reflection on individual Chunker types.</li>
 *     <li>The optimized directory argument is ignored since API 26; dexopt output lives in code_cache.</li>
 * </ul>
 */
final class EngineLoader {

    private static final String TAG = "EngineLoader";
    private static final String DEX_ASSET = "chunker.jar";
    private static final String RESOURCE_ASSET = "engine-res";
    private static final String ENGINE_CLASS = "com.frenetic.litechunk.engine.EngineImpl";

    private EngineLoader() {
    }

    static ChunkerEngine load(Context context) throws Exception {
        File dexFile = syncDex(context);
        File resourceDir = syncResources(context);

        ClassLoader parent = ChunkerEngine.class.getClassLoader();
        DexClassLoader loader = new DexClassLoader(
                dexFile.getAbsolutePath(),
                null,
                context.getApplicationInfo().nativeLibraryDir,
                parent
        );

        Class<?> implementation = Class.forName(ENGINE_CLASS, true, loader);
        ChunkerEngine engine = (ChunkerEngine) implementation.getDeclaredConstructor().newInstance();
        engine.setResourceDirectory(resourceDir);
        return engine;
    }

    private static File syncDex(Context context) throws IOException {
        File directory = new File(context.getCodeCacheDir(), "engine");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("无法创建引擎缓存目录");
        }

        long size = assetSize(context, DEX_ASSET);
        File target = new File(directory, "chunker-" + size + ".jar");
        if (!target.isFile() || target.length() != size) {
            File temp = new File(directory, "chunker.tmp");
            try (InputStream in = context.getAssets().open(DEX_ASSET);
                 OutputStream out = new FileOutputStream(temp)) {
                copy(in, out);
            }
            if (!temp.renameTo(target)) {
                //noinspection ResultOfMethodCallIgnored
                target.delete();
                if (!temp.renameTo(target)) {
                    throw new IOException("无法写入引擎文件");
                }
            }
        }

        // Drop jars from previous app versions so code_cache does not grow forever.
        File[] stale = directory.listFiles((dir, name) -> name.startsWith("chunker-") && !name.equals(target.getName()));
        if (stale != null) {
            for (File file : stale) {
                if (!file.delete()) {
                    Log.w(TAG, "Unable to delete stale engine " + file);
                }
            }
        }

        // Required since Android 14: dynamically loaded code must not come from a writable file.
        if (target.canWrite() && !target.setReadOnly()) {
            throw new IOException("无法将引擎设为只读");
        }
        return target;
    }

    private static File syncResources(Context context) throws IOException {
        File root = new File(context.getFilesDir(), RESOURCE_ASSET);
        File stamp = new File(root, ".stamp");
        String wanted = Integer.toString(BuildConfig.VERSION_CODE);

        if (stamp.isFile() && wanted.equals(readText(stamp))) {
            return root;
        }

        WorldFiles.delete(root);
        if (!root.isDirectory() && !root.mkdirs()) {
            throw new IOException("无法创建引擎资源目录");
        }
        copyAssetTree(context.getAssets(), RESOURCE_ASSET, root);
        try (OutputStream out = new FileOutputStream(stamp)) {
            out.write(wanted.getBytes("UTF-8"));
        }
        return root;
    }

    private static void copyAssetTree(AssetManager assets, String path, File destination) throws IOException {
        String[] children = assets.list(path);
        if (children == null || children.length == 0) {
            File parent = destination.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法创建目录：" + parent.getName());
            }
            try (InputStream in = assets.open(path);
                 OutputStream out = new FileOutputStream(destination)) {
                copy(in, out);
            }
            return;
        }
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("无法创建目录：" + destination.getName());
        }
        for (String child : children) {
            copyAssetTree(assets, path + "/" + child, new File(destination, child));
        }
    }

    private static long assetSize(Context context, String asset) throws IOException {
        try (InputStream in = context.getAssets().open(asset)) {
            return in.available();
        }
    }

    private static String readText(File file) {
        try (InputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[64];
            int read = in.read(buffer);
            return read <= 0 ? "" : new String(buffer, 0, read, "UTF-8");
        } catch (IOException exception) {
            return "";
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }
}
