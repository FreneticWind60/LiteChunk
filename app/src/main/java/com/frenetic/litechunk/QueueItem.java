package com.frenetic.litechunk;

import android.content.Context;
import android.net.Uri;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.Serializable;
import java.util.UUID;

/**
 * One entry of the conversion list.
 * <p>
 * A world is never copied just to sit in the list: the entry remembers where the user picked it from (a SAF document,
 * a SAF tree or a file inside our own output folder) and the bytes are only materialised into flush space when the
 * conversion actually starts. That keeps a 100MB import instant and avoids holding two copies on a phone that may not
 * have the room.
 */
final class QueueItem implements Serializable {

    private static final long serialVersionUID = 1L;

    enum Kind {
        /** A single file picked through SAF: zip, mcworld or a bare region folder archive. */
        DOCUMENT,
        /** A world folder picked through SAF. */
        TREE,
        /** A file or folder that already lives inside our own directory. */
        LOCAL
    }

    final String id;
    String name;
    Kind kind;
    /** Set for {@link Kind#DOCUMENT} and {@link Kind#TREE}. */
    String uri;
    /** Set for {@link Kind#LOCAL}. */
    String path;
    long size;

    /** Filled in by detection, may stay null when the world could not be recognised. */
    String formatId;
    String formatLabel;
    String version;

    QueueItem(String name, Kind kind) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.kind = kind;
    }

    boolean isDetected() {
        return formatId != null;
    }

    /** True when the source is a folder, which has to be copied instead of unzipped. */
    boolean isFolder() {
        return kind == Kind.TREE || (kind == Kind.LOCAL && path != null && new File(path).isDirectory());
    }

    String detectDescription() {
        if (formatLabel == null && formatId == null) {
            return "未识别版本";
        }
        String label = formatLabel == null ? formatId : formatLabel;
        if (version == null || version.isEmpty()) {
            return label;
        }
        return label + " " + version;
    }

    JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);
        json.put("kind", kind.name());
        json.put("uri", uri);
        json.put("path", path);
        json.put("size", size);
        json.put("formatId", formatId);
        json.put("formatLabel", formatLabel);
        json.put("version", version);
        return json;
    }

    static QueueItem fromJson(JSONObject json) {
        QueueItem item = new QueueItem(json.optString("name", "存档"), Kind.valueOf(json.optString("kind", "DOCUMENT")));
        item.uri = json.optString("uri", null);
        item.path = json.optString("path", null);
        item.size = json.optLong("size", 0L);
        item.formatId = json.optString("formatId", null);
        item.formatLabel = json.optString("formatLabel", null);
        item.version = json.optString("version", null);
        return item;
    }

    static QueueItem fromDocument(Context context, Uri uri) {
        String displayName = WorldFiles.displayName(context, uri);
        QueueItem item = new QueueItem(displayName, Kind.DOCUMENT);
        item.uri = uri.toString();
        item.size = WorldFiles.documentSize(context, uri);
        return item;
    }

    static QueueItem fromTree(Context context, Uri uri) {
        String name = WorldFiles.displayName(context, uri);
        QueueItem item = new QueueItem(name, Kind.TREE);
        item.uri = uri.toString();
        item.size = -1L;
        return item;
    }

    static QueueItem fromLocal(File file) {
        QueueItem item = new QueueItem(file.getName(), Kind.LOCAL);
        item.path = file.getAbsolutePath();
        item.size = file.isFile() ? file.length() : WorldFiles.size(file);
        return item;
    }

    /** Same source, same format: used to avoid adding the same world twice. */
    boolean sameSource(QueueItem other) {
        if (other == null) {
            return false;
        }
        if (kind == Kind.LOCAL) {
            return other.kind == Kind.LOCAL && equal(path, other.path);
        }
        return other.kind == kind && equal(uri, other.uri);
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
