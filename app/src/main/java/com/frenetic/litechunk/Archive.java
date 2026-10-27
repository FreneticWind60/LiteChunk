package com.frenetic.litechunk;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Reads and replaces a single entry inside a world archive.
 * <p>
 * Worlds are stored as {@code .zip} / {@code .mcworld}, but everything worth editing lives in one 2 KB
 * {@code level.dat}. Two consequences shaped this class:
 * <ul>
 *     <li>reading only has to open one entry, so the details screen is instant even on a 1 GB world;</li>
 *     <li>writing cannot patch in place -- a zip's central directory records byte offsets, so any size change
 *     forces a full rewrite. The rewrite therefore goes to a sibling temporary file first: a phone that runs out
 *     of space leaves the original archive untouched instead of half overwritten.</li>
 * </ul>
 */
final class Archive {

    private static final String LEVEL_DAT = "level.dat";

    private Archive() {
    }

    /**
     * Copy {@code level.dat} out of the archive.
     *
     * @param archive the {@code .zip} / {@code .mcworld} file.
     * @param target  where to write it.
     * @return the entry name inside the archive, needed by {@link #writeBack(File, String, File)}.
     * @throws IOException when the archive has no {@code level.dat}.
     */
    static String extractLevelDat(File archive, File target) throws IOException {
        try (ZipFile zip = new ZipFile(archive)) {
            ZipEntry entry = findLevelDat(zip);
            if (entry == null) {
                throw new IOException("存档里找不到 level.dat");
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new IOException("无法创建临时目录");
            }
            try (InputStream in = new BufferedInputStream(zip.getInputStream(entry));
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                copy(in, out);
            }
            return entry.getName();
        }
    }

    /**
     * Replace one entry, keeping everything else byte for byte.
     * <p>
     * Entry order, names and timestamps are preserved because a world archive is handed straight to Minecraft,
     * which is tolerant of entry order but not of a missing folder record.
     */
    static void writeBack(File archive, String entryName, File replacement) throws IOException {
        File parent = archive.getParentFile();
        File temporary = new File(parent, archive.getName() + ".tmp");
        boolean replaced = false;

        try (ZipFile source = new ZipFile(archive);
             ZipOutputStream out = new ZipOutputStream(
                     new BufferedOutputStream(new FileOutputStream(temporary)))) {
            Enumeration<? extends ZipEntry> entries = source.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().equals(entryName)) {
                    out.putNextEntry(new ZipEntry(entryName));
                    try (InputStream in = new BufferedInputStream(new FileInputStream(replacement))) {
                        copy(in, out);
                    }
                    out.closeEntry();
                    replaced = true;
                } else if (entry.isDirectory()) {
                    out.putNextEntry(new ZipEntry(entry.getName()));
                    out.closeEntry();
                } else {
                    out.putNextEntry(new ZipEntry(entry.getName()));
                    try (InputStream in = new BufferedInputStream(source.getInputStream(entry))) {
                        copy(in, out);
                    }
                    out.closeEntry();
                }
            }

            // The entry can be missing when the archive was replaced underneath us; put it back so the save is
            // not silently dropped from a world that otherwise still opens.
            if (!replaced) {
                out.putNextEntry(new ZipEntry(entryName));
                try (InputStream in = new BufferedInputStream(new FileInputStream(replacement))) {
                    copy(in, out);
                }
                out.closeEntry();
            }
        } catch (IOException failure) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw failure;
        }

        if (!temporary.renameTo(archive)) {
            //noinspection ResultOfMethodCallIgnored
            archive.delete();
            if (!temporary.renameTo(archive)) {
                //noinspection ResultOfMethodCallIgnored
                temporary.delete();
                throw new IOException("无法替换存档文件");
            }
        }
    }

    /**
     * A world exported by this app has {@code level.dat} at the root; one imported from a phone may nest
     * everything under the world name. Match case insensitively for the same reason.
     */
    private static ZipEntry findLevelDat(ZipFile zip) {
        ZipEntry direct = zip.getEntry(LEVEL_DAT);
        if (direct != null) {
            return direct;
        }
        String suffix = "/" + LEVEL_DAT;
        Enumeration<? extends ZipEntry> entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (!entry.isDirectory() && entry.getName().toLowerCase(java.util.Locale.ROOT).endsWith(suffix)) {
                return entry;
            }
        }
        return null;
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = in.read(buffer)) > 0) {
            out.write(buffer, 0, read);
        }
    }
}
