package com.frenetic.litechunk;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import androidx.documentfile.provider.DocumentFile;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * File plumbing for worlds: SAF staging, zip round tripping and size helpers.
 * <p>
 * Chunker needs real files (LevelDB memory maps its files), so everything is staged into app private storage and only
 * packaged back into a zip at the very end.
 */
final class WorldFiles {

    private static final int BUFFER = 64 * 1024;
    private static final int MAX_DEPTH = 3;

    private WorldFiles() {
    }

    static void unzip(InputStream input, File destination) throws IOException {
        if (!destination.isDirectory() && !destination.mkdirs()) {
            throw new IOException("无法创建解压目录");
        }
        String rootPath = destination.getCanonicalPath();

        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(input))) {
            byte[] buffer = new byte[BUFFER];
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (name == null || name.isEmpty() || name.startsWith("__MACOSX")) {
                    continue;
                }
                File target = new File(destination, name);
                String path = target.getCanonicalPath();
                if (!path.equals(rootPath) && !path.startsWith(rootPath + File.separator)) {
                    throw new IOException("压缩包包含非法路径：" + name);
                }
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    target.mkdirs();
                    continue;
                }
                File parent = target.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    throw new IOException("无法创建目录：" + parent.getName());
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

    /**
     * Finds the folder that actually holds {@code level.dat}, unwrapping archives that contain a single wrapper
     * directory (which is what most world exporters produce).
     */
    static File locateWorldRoot(File directory) {
        return locateWorldRoot(directory, MAX_DEPTH);
    }

    private static File locateWorldRoot(File directory, int depth) {
        if (directory == null || !directory.isDirectory()) {
            return null;
        }
        if (isWorld(directory)) {
            return directory;
        }
        if (depth <= 0) {
            return null;
        }
        File[] children = directory.listFiles(File::isDirectory);
        if (children == null) {
            return null;
        }
        for (File child : children) {
            if (child.getName().startsWith("__MACOSX")) {
                continue;
            }
            if (isWorld(child)) {
                return child;
            }
        }
        for (File child : children) {
            if (child.getName().startsWith("__MACOSX")) {
                continue;
            }
            File nested = locateWorldRoot(child, depth - 1);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    static boolean isWorld(File directory) {
        return new File(directory, "level.dat").isFile();
    }

    static void copyTree(Context context, Uri treeUri, File destination) throws IOException {
        DocumentFile root = DocumentFile.fromTreeUri(context, treeUri);
        if (root == null || !root.exists()) {
            throw new IOException("无法访问所选文件夹");
        }
        copyDocument(context, root, destination);
    }

    private static void copyDocument(Context context, DocumentFile source, File destination) throws IOException {
        if (source.isDirectory()) {
            if (!destination.isDirectory() && !destination.mkdirs()) {
                throw new IOException("无法创建目录：" + destination.getName());
            }
            for (DocumentFile child : source.listFiles()) {
                String name = child.getName();
                if (name == null) {
                    continue;
                }
                copyDocument(context, child, new File(destination, name));
            }
            return;
        }

        try (InputStream in = context.getContentResolver().openInputStream(source.getUri());
             OutputStream out = new FileOutputStream(destination)) {
            if (in == null) {
                throw new IOException("无法读取：" + source.getName());
            }
            copy(in, out);
        }
    }

    /**
     * Pack a converted world into a {@code .zip} / {@code .mcworld}.
     * <p>
     * The obvious optimisation here - "everything in a world is already compressed, so deflate is wasted CPU, store
     * it" - was measured and is false. A converted Bedrock output (80 MB of {@code db/*.ldb} and {@code .log} for a
     * 278 MB Java world) still deflates to 38%, because the writer leaves most of the LevelDB payload as palette
     * runs and zero padding. Storing it verbatim multiplies the archive 2.6x for a saving of ~2% of the pipeline.
     * <p>
     * What <em>is</em> free is a cheaper level for Java region files, see {@link #levelFor(String)}.
     */
    static void zip(File directory, File zipFile) throws IOException {
        File parent = zipFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建输出目录");
        }
        try (ZipOutputStream zip = new ZipOutputStream(
                new BufferedOutputStream(new FileOutputStream(zipFile), BUFFER))) {
            addToZip(zip, directory, "");
        }
    }

    /**
     * @param entryName the name this file will have inside the archive, empty for the root directory.
     */
    private static void addToZip(ZipOutputStream zip, File file, String entryName) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                String prefix = entryName.isEmpty() ? "" : entryName + "/";
                for (File child : children) {
                    addToZip(zip, child, prefix + child.getName());
                }
            }
            return;
        }
        if (entryName.isEmpty()) {
            return;
        }
        // The level has to be set before putNextEntry: that is where the stream initialises the deflater.
        zip.setLevel(levelFor(entryName));
        zip.putNextEntry(new ZipEntry(entryName));
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER)) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) > 0) {
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
    }

    /**
     * Deflate level for a single entry.
     * <p>
     * Java region files are the one case where a cheaper level is free, because two thirds of a converted
     * {@code .mca} is 4 KB sector padding and repeated zlib headers, which even the fast level catches:
     * <ul>
     *   <li>converted 278 MB world, 80 MB output: level 1 = 38.7% of source, level 6 = 38.0%, 1.4x faster;</li>
     *   <li>converted 25 MB world, 11 MB output: both 38%, 3x faster.</li>
     * </ul>
     * Level 0 was rejected everywhere: 20x faster, but it stores the bytes verbatim, so an 80 MB folder becomes an
     * 80 MB archive instead of 30 MB. LevelDB files keep the default level for the same reason.
     *
     * @param entryName the path inside the archive.
     * @return the deflate level to use for this entry.
     */
    private static int levelFor(String entryName) {
        String lower = entryName.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        String extension = dot == -1 ? "" : lower.substring(dot + 1);
        if ("mca".equals(extension) || "mcr".equals(extension)) {
            return Deflater.BEST_SPEED;
        }
        return Deflater.DEFAULT_COMPRESSION;
    }

    static long size(File file) {
        if (file == null) {
            return 0L;
        }
        if (file.isFile()) {
            return file.length();
        }
        File[] children = file.listFiles();
        long total = 0L;
        if (children != null) {
            for (File child : children) {
                total += size(child);
            }
        }
        return total;
    }

    static String humanSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        double value = bytes;
        String[] units = {"KB", "MB", "GB"};
        for (String unit : units) {
            value /= 1024D;
            if (value < 1024D || "GB".equals(unit)) {
                return String.format(Locale.US, "%.1f %s", value, unit);
            }
        }
        return bytes + " B";
    }

    static boolean delete(File file) {
        if (file == null || !file.exists()) {
            return true;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    delete(child);
                }
            }
        }
        return file.delete();
    }

    static String displayName(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) {
                        return name;
                    }
                }
            }
        } catch (Exception ignored) {
            // Fall back to the last path segment.
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? "存档" : segment;
    }

    static String suggestName(String sourceName, boolean javaTarget) {
        return baseName(sourceName) + (javaTarget ? ".zip" : ".mcworld");
    }

    /** Strip the extension and any character a file system or a content provider would reject. */
    static String baseName(String sourceName) {
        String base = sourceName == null ? "world" : sourceName;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        base = base.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return base.isEmpty() ? "world" : base;
    }

    /**
     * Drop only a known archive extension, keeping dots that belong to the world's own name.
     * <p>
     * {@link #baseName} cuts at the last dot, which is right for a file picked from disk and wrong for a name we
     * produced ourselves: "我的世界 v1.2-BE-20261027" would come back as "我的世界 v1".
     */
    static String stripArchiveExtension(String name) {
        if (name == null) {
            return "world";
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String extension : new String[]{".mcworld", ".zip"}) {
            if (lower.endsWith(extension)) {
                String trimmed = name.substring(0, name.length() - extension.length()).trim();
                return trimmed.isEmpty() ? "world" : trimmed;
            }
        }
        return baseName(name);
    }

    /**
     * Find a name that does not collide, so converting the same world twice keeps both results instead of silently
     * overwriting the first one -- or worse, overwriting the file a second queue entry is about to convert.
     */
    static File unique(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) {
            return candidate;
        }
        // Split at the last dot rather than reusing baseName(): a world may legitimately contain a dot ("我的世界
        // v1.2"), and baseName() would treat the tail as the extension and produce "我的世界 v1 (2).2".
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String extension = dot > 0 ? name.substring(dot) : "";
        for (int index = 2; index < 1000; index++) {
            candidate = new File(directory, base + " (" + index + ")" + extension);
            if (!candidate.exists()) {
                return candidate;
            }
        }
        return new File(directory, base + "-" + System.currentTimeMillis() + extension);
    }

    /** Size of a SAF document, or -1 when the provider does not report one. */
    static long documentSize(Context context, Uri uri) {
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (index >= 0 && !cursor.isNull(index)) {
                    return cursor.getLong(index);
                }
            }
        } catch (Exception ignored) {
            // Not every provider implements SIZE.
        }
        return -1L;
    }

    static void unzip(File archive, File destination) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(archive))) {
            unzip(in, destination);
        }
    }

    static void copyTree(File source, File destination) throws IOException {
        if (source == null || !source.exists()) {
            throw new IOException("源文件不存在");
        }
        if (source.isDirectory()) {
            if (!destination.isDirectory() && !destination.mkdirs()) {
                throw new IOException("无法创建目录：" + destination.getName());
            }
            File[] children = source.listFiles();
            if (children != null) {
                for (File child : children) {
                    copyTree(child, new File(destination, child.getName()));
                }
            }
            return;
        }
        File parent = destination.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建目录：" + parent.getName());
        }
        try (InputStream in = new BufferedInputStream(new FileInputStream(source));
             OutputStream out = new BufferedOutputStream(new FileOutputStream(destination))) {
            copy(in, out);
        }
    }

    /**
     * Whether a zip archive holds a Bedrock world, decided by the {@code db/} folder every Bedrock save contains.
     * Used only for labelling the file list, so a cheap central-directory scan is enough.
     */
    static boolean isBedrockArchive(File archive) {
        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive)) {
            java.util.Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                String name = entries.nextElement().getName();
                if (name.startsWith("db/")) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // Unreadable archive: fall back to the extension, which is what the caller already knows.
        }
        return false;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }
}
