package com.frenetic.litechunk;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.zip.GZIPInputStream;

import be.stef.arcana.extractor.RarExtractor;
import be.stef.arcana.extractor.SevenZExtractor;
import be.stef.arcana.extractor.TarExtractor;
import be.stef.arcana.formats.rar.rar4.Rar4Extractor;
import be.stef.arcana.formats.rar.rar5.Rar5Extractor;
import be.stef.arcana.util.ArcanaConcurrency;

/**
 * Unpacks the archive formats {@code java.util.zip} cannot read.
 * <p>
 * Worlds are shared as {@code .zip} / {@code .mcworld} most of the time, but Chinese file hosts and QQ groups hand
 * out {@code .rar} and {@code .7z} just as often, and those used to fail with "no level.dat found" — {@code
 * ZipInputStream} reports an empty archive for them instead of raising. Rather than teach the user to re-pack, the
 * importer now opens them itself through a trimmed copy of <a href="https://github.com/RealBurst/arcana">Arcana</a>
 * (Apache-2.0, pure Java, no native code), which is vendored under {@code be.stef.arcana}.
 * <p>
 * Only the extraction side is vendored, and only for the four containers that matter here: RAR 4/5, 7z, TAR and
 * TAR+GZIP. Everything Arcana brings for those (its own LZMA/LZMA2, PPMd, bzip2 and RAR decoders) comes along,
 * because they are what the formats need; the compressor, the CLI, the other thirty formats and the console
 * progress bars were dropped.
 */
final class Archives {

    static {
        // Extraction runs inside the conversion service, which already spreads itself over the few cores a phone
        // has; Arcana otherwise sizes its worker pool from the core count and spawns one writer per core.
        ArcanaConcurrency.threads = 2;
        // The RAR decoders print a per-file progress bar to stdout by default. On a phone that is logcat spam at
        // hundreds of lines per second, so it is off.
        Rar4Extractor.showProgress = false;
        Rar5Extractor.showProgress = false;
    }

    private Archives() {
    }

    /**
     * Whether the importer can open this container itself.
     *
     * @param container what {@link WorldFiles#sniff(InputStream)} reported.
     * @return true when {@link #extract(File, File, WorldFiles.Container)} handles it.
     */
    static boolean isSupported(WorldFiles.Container container) {
        switch (container) {
            case ZIP:
            case RAR:
            case SEVEN_Z:
            case TAR:
            case GZIP:
                return true;
            default:
                return false;
        }
    }

    /**
     * Whether the format reads at random offsets and therefore needs a real file.
     * <p>
     * RAR and 7z index their content from the end of the archive; tar and tar.gz are sequential and can be read
     * straight from a content-resolver stream, which avoids copying the archive to disk first.
     *
     * @param container the container reported by the sniffer.
     * @return true when the caller must stage the source as a file.
     */
    static boolean needsFile(WorldFiles.Container container) {
        return container == WorldFiles.Container.RAR || container == WorldFiles.Container.SEVEN_Z;
    }

    /**
     * Extract a sequential container straight from a stream.
     *
     * @param in          the archive stream, positioned at its start.
     * @param destination directory to unpack into.
     * @param container   TAR or GZIP (a bare .gz is treated as tar.gz, which is the only shape a world arrives in).
     * @throws Exception when the archive is damaged or is not a tar.
     */
    static void extract(InputStream in, File destination, WorldFiles.Container container) throws Exception {
        switch (container) {
            case TAR:
                new TarExtractor().extract(in, destination);
                break;
            case GZIP: {
                try (InputStream gzip = new GZIPInputStream(in)) {
                    new TarExtractor().extract(gzip, destination);
                }
                break;
            }
            default:
                throw new IOException("这个格式需要随机读取，必须先存成文件");
        }
    }

    /**
     * Extract a non-zip archive. Zip is handled by {@link WorldFiles#unzip} instead, which is faster and needs no
     * third-party code.
     * <p>
     * RAR and 7z are read at random offsets, so they need a real file rather than a stream; the caller copies the
     * source out of the content resolver first. TAR and TAR+GZIP are read sequentially and could stream, but going
     * through one code path keeps the cleanup in one place.
     *
     * @param archive     the archive on disk.
     * @param destination directory to unpack into, created if needed.
     * @param container   the container reported by the sniffer.
     * @throws Exception when the archive is damaged, encrypted without a password, or not what it claimed.
     */
    static void extract(File archive, File destination, WorldFiles.Container container) throws Exception {
        switch (container) {
            case RAR:
                new RarExtractor().extract(archive, destination);
                break;
            case SEVEN_Z:
                new SevenZExtractor().extract(archive, destination);
                break;
            case TAR:
            case GZIP: {
                try (InputStream in = new BufferedInputStream(new FileInputStream(archive), 64 * 1024)) {
                    extract(in, destination, container);
                }
                break;
            }
            default:
                throw new IOException("不支持的压缩格式");
        }
    }
}
