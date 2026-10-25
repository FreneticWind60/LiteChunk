package com.frenetic.litechunk;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;
import androidx.fragment.app.FragmentActivity;

import java.io.File;

/**
 * Hands a finished archive to the game.
 * <p>
 * Bedrock on Android accepts a {@code content://} URI and imports a {@code .mcworld} directly. Java edition has no
 * official Android client, so the best that can be done is to open the archive with an installed launcher (Pojav,
 * FCL, Boat) and fall back to the system share sheet when none is there.
 */
final class Importers {

    static final String BEDROCK_PACKAGE = "com.mojang.minecraftpe";

    /**
     * Android Java launchers that accept an archive through a VIEW intent. Kept in one place so the manifest
     * {@code <queries>} block and this list cannot drift apart.
     */
    static final String[] JAVA_LAUNCHERS = {
            "net.kdt.pojavlaunch",   // PojavLauncher
            "com.tungsten.fcl",      // Fold Craft Launcher
            "com.tungsten.fclauncher",
            "com.boat.launcher",     // Boat
            "com.mio.launcher",
            "com.angnesss.amethyst"  // Amethyst
    };

    private Importers() {
    }

    enum Target {BEDROCK, JAVA_LAUNCHER, NONE}

    static Target findTarget(Context context, boolean bedrockArchive) {
        PackageManager manager = context.getPackageManager();
        if (bedrockArchive && isInstalled(manager, BEDROCK_PACKAGE)) {
            return Target.BEDROCK;
        }
        for (String launcher : JAVA_LAUNCHERS) {
            if (isInstalled(manager, launcher)) {
                return Target.JAVA_LAUNCHER;
            }
        }
        return Target.NONE;
    }

    private static boolean isInstalled(PackageManager manager, String packageName) {
        try {
            manager.getPackageInfo(packageName, 0);
            return true;
        } catch (PackageManager.NameNotFoundException absent) {
            return false;
        }
    }

    /**
     * Open the archive with the game, or explain why that is not possible on this device.
     *
     * @return true when an intent was actually launched.
     */
    static boolean openInGame(FragmentActivity activity, File archive, boolean bedrockArchive) {
        Target target = findTarget(activity, bedrockArchive);
        if (target == Target.NONE) {
            Toast.makeText(activity, bedrockArchive
                    ? "未检测到基岩版，已改为分享"
                    : "未检测到 Java 版启动器，已改为分享", Toast.LENGTH_SHORT).show();
            share(activity, archive, bedrockArchive);
            return false;
        }

        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", archive);
        Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, bedrockArchive ? "application/octet-stream" : "application/zip")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        // Some receivers only look at the ClipData for the read grant.
        intent.setClipData(ClipData.newRawUri(archive.getName(), uri));
        if (target == Target.BEDROCK) {
            intent.setPackage(BEDROCK_PACKAGE);
        }

        try {
            activity.startActivity(Intent.createChooser(intent, "导入到游戏"));
            return true;
        } catch (RuntimeException failure) {
            Toast.makeText(activity, "导入失败：" + Job.describe(failure), Toast.LENGTH_LONG).show();
            return false;
        }
    }

    static void share(FragmentActivity activity, File archive, boolean bedrockArchive) {
        Uri uri = FileProvider.getUriForFile(activity, activity.getPackageName() + ".files", archive);
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType(bedrockArchive ? "application/octet-stream" : "application/zip")
                .putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        send.setClipData(ClipData.newRawUri(archive.getName(), uri));
        try {
            activity.startActivity(Intent.createChooser(send, "分享存档"));
        } catch (RuntimeException failure) {
            Toast.makeText(activity, "分享失败：" + Job.describe(failure), Toast.LENGTH_LONG).show();
        }
    }
}
