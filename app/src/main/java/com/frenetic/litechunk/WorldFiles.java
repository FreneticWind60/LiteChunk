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

    static void zip(File directory, File zipFile) throws IOException {
        File parent = zipFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("无法创建输出目录");
        }
        String rootPath = directory.getCanonicalPath();
        try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)))) {
            addToZip(zip, directory, rootPath);
        }
    }

    private static void addToZip(ZipOutputStream zip, File file, String rootPath) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    addToZip(zip, child, rootPath);
                }
            }
            return;
        }
        String absolute = file.getCanonicalPath();
        if (absolute.length() <= rootPath.length()) {
            return;
        }
        String entryName = absolute.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
        zip.putNextEntry(new ZipEntry(entryName));
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            byte[] buffer = new byte[BUFFER];
            int read;
            while ((read = in.read(buffer)) > 0) {
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
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
     * Find a name that does not collide, so converting the same world twice keeps both results instead of silently
     * overwriting the first one -- or worse, overwriting the file a second queue entry is about to convert.
     */
    static File unique(File directory, String name) {
        File candidate = new File(directory, name);
        if (!candidate.exists()) {
            return candidate;
        }
        String base = baseName(name);
        String extension = name.substring(base.length());
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
