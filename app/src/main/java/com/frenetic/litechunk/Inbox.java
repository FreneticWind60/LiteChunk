package com.frenetic.litechunk;

import android.content.Context;
import android.net.Uri;
import android.os.StatFs;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Where an archive lands when it arrives from another app.
 * <p>
 * A shared or opened file comes in as a {@code content://} URI whose read grant only outlives the intent that carried
 * it: the conversion list deliberately stores only a reference and reads the bytes later, sometimes minutes later and
 * in another process, so the reference would be dead by then. The file is therefore copied into our own directory at
 * the entry point, and the queue entry points at that copy.
 * <p>
 * The copy is also the natural place to stop the same world being imported twice: a second share with the same name
 * and size reuses the file that is already here instead of paying for another 300 MB copy.
 */
final class Inbox {

    private static final String TAG = "Inbox";
    private static final int BUFFER = 128 * 1024;

    /**
     * Extensions worth accepting from a share intent.
     * <p>
     * The manifest has to declare {@code *}{@code /*} to be offered for {@code .mcworld} at all — every app that
     * matters (WeChat, QQ, most Chinese file managers) reports unknown extensions as {@code application/octet-stream}
     * or {@code *}{@code /*}, so a narrower filter would silently hide LiteChunk. The cost of that breadth is that it
     * also appears for files it cannot use, which is why this list exists: anything else is rejected before a byte
     * is copied.
     */
    private static final String[] ARCHIVE_EXTENSIONS = {".zip", ".mcworld", ".mctemplate"};

    private Inbox() {
    }

    static File directory(Context context) {
        return Paths.ensure(new File(Paths.root(context), "inbox"));
    }

    /** Whether a received file looks like something the converter can open. */
    static boolean isArchive(String displayName) {
        if (displayName == null) {
            return false;
        }
        String lower = displayName.toLowerCase(Locale.ROOT);
        for (String extension : ARCHIVE_EXTENSIONS) {
            if (lower.endsWith(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Why a received file cannot be used, or null when it can.
     * <p>
     * A share that is rejected used to be reported by name only, so a {@code .rar} looked like a file this app had
     * simply refused. Naming the format and the way out costs nothing here, because the alternative - copying a
     * possibly 1 GB archive into {@code inbox/} only to fail while unpacking - is worse on every axis.
     *
     * @param displayName the name the sending app reported.
     * @return a sentence to show after "已忽略", or null when the file is worth copying in.
     */
    static String rejectionReason(String displayName) {
        if (isArchive(displayName)) {
            return null;
        }
        String lower = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".rar") || lower.endsWith(".7z") || lower.endsWith(".tar")
                || lower.endsWith(".gz") || lower.endsWith(".tgz") || lower.endsWith(".bz2")) {
            return "不支持这个压缩格式，请先用解压工具解成文件夹再导入（本应用只支持 zip / .mcworld）";
        }
        return "不是存档文件（只支持 zip / .mcworld / .mctemplate）";
    }

    /** Whether an absolute path is one of our own staged imports, i.e. something we may delete after a conversion. */
    static boolean isInside(Context context, String path) {
        if (path == null) {
            return false;
        }
        String root = directory(context).getAbsolutePath();
        return path.startsWith(root + File.separator);
    }

    /**
     * Copy a received document into {@code inbox/}.
     *
     * @return the local file, which may be a copy that was already there.
     * @throws IOException when the source cannot be read or there is not enough room for the copy.
     */
    static File accept(Context context, Uri uri) throws IOException {
        String displayName = WorldFiles.displayName(context, uri);
        String safeName = safeName(displayName);
        long size = WorldFiles.documentSize(context, uri);
        Log.i(TAG, "accepting " + displayName + " (" + size + " bytes) from " + uri);

        File existing = findExisting(context, safeName, size);
        if (existing != null) {
            Log.i(TAG, "reusing inbox copy of " + safeName);
            return existing;
        }

        File directory = directory(context);
        requireSpace(directory, size);

        File target = WorldFiles.unique(directory, safeName);
        if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
            copyFile(new File(uri.getPath()), target);
        } else {
            try (InputStream input = context.getContentResolver().openInputStream(uri)) {
                if (input == null) {
                    throw new IOException("无法读取所选文件");
                }
                try (OutputStream output = new FileOutputStream(target)) {
                    copy(input, output);
                }
            } catch (IOException failure) {
                // A half written import would sit in the list as a corrupt world; drop it now.
                WorldFiles.delete(target);
                throw failure;
            }
        }
        return target;
    }

    /** A previous share of the same name and size is the same world as far as a queue entry cares. */
    private static File findExisting(Context context, String name, long size) {
        File[] files = directory(context).listFiles();
        if (files == null) {
            return null;
        }
        for (File file : files) {
            if (!file.isFile()) {
                continue;
            }
            boolean sameName = file.getName().equals(name) || file.getName().startsWith(stripExtension(name) + " (");
            if (sameName && (size < 0L || file.length() == size)) {
                return file;
            }
        }
        return null;
    }

    /**
     * Refuse the copy up front when it cannot fit.
     * <p>
     * The conversion needs roughly the size of the world twice (the staged copy plus the converted output), so the
     * check asks for the file itself plus a margin. Failing here produces a clear message; failing halfway through
     * would leave a truncated archive in the list.
     */
    private static void requireSpace(File directory, long size) throws IOException {
        if (size <= 0L) {
            return;
        }
        long available = new StatFs(directory.getAbsolutePath()).getAvailableBytes();
        if (available < size + size / 8L) {
            throw new IOException("存储空间不足，需要 " + WorldFiles.humanSize(size)
                    + "，可用 " + WorldFiles.humanSize(available));
        }
    }

    private static void copyFile(File source, File target) throws IOException {
        if (!source.isFile()) {
            throw new IOException("无法读取所选文件");
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream output = new FileOutputStream(target)) {
            copy(input, output);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[BUFFER];
        int read;
        while ((read = input.read(buffer)) > 0) {
            output.write(buffer, 0, read);
        }
    }

    /** Keep the extension (the queue row and the name template both show it) but drop characters a path rejects. */
    private static String safeName(String displayName) {
        String name = displayName == null ? "" : displayName;
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        if (name.isEmpty()) {
            return "world.zip";
        }
        return name;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
