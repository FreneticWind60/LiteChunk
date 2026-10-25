package com.frenetic.litechunk;

import android.os.Bundle;

import com.frenetic.litechunk.api.ChunkerEngine;
import com.frenetic.litechunk.api.FormatGroup;
import com.frenetic.litechunk.api.FormatVersion;

import java.util.ArrayList;
import java.util.List;

/**
 * Parcelable-friendly snapshot of the formats the engine supports.
 * <p>
 * The UI process never loads Chunker itself, so the list is produced in the {@code :chunker} process and shipped over
 * the {@link android.os.ResultReceiver} as primitive arrays.
 */
final class Formats {

    static final class Group {
        final String id;
        final String label;
        final String[] versions;
        final String[] formatIds;

        Group(String id, String label, String[] versions, String[] formatIds) {
            this.id = id;
            this.label = label;
            this.versions = versions;
            this.formatIds = formatIds;
        }

        String latestFormatId() {
            return formatIds.length == 0 ? null : formatIds[0];
        }

        int indexOf(String formatId) {
            for (int i = 0; i < formatIds.length; i++) {
                if (formatIds[i].equals(formatId)) {
                    return i;
                }
            }
            return 0;
        }
    }

    final String engineVersion;
    final Group[] groups;

    private Formats(String engineVersion, Group[] groups) {
        this.engineVersion = engineVersion;
        this.groups = groups;
    }

    static Formats of(ChunkerEngine engine) {
        List<Group> groups = new ArrayList<>();
        for (FormatGroup group : engine.formats()) {
            FormatVersion[] versions = group.versions;
            String[] labels = new String[versions.length];
            String[] ids = new String[versions.length];
            for (int i = 0; i < versions.length; i++) {
                labels[i] = versions[i].version;
                ids[i] = versions[i].id;
            }
            groups.add(new Group(group.id, group.label, labels, ids));
        }
        return new Formats(engine.engineVersion(), groups.toArray(new Group[0]));
    }

    Group group(String id) {
        for (Group group : groups) {
            if (group.id.equals(id)) {
                return group;
            }
        }
        return groups.length == 0 ? null : groups[0];
    }

    String editionLabel(String groupId) {
        Group group = group(groupId);
        return group == null ? groupId : group.label;
    }

    Bundle toBundle() {
        Bundle bundle = new Bundle();
        bundle.putString(Job.KEY_ENGINE_VERSION, engineVersion);
        bundle.putInt(Job.KEY_GROUP_COUNT, groups.length);
        for (int i = 0; i < groups.length; i++) {
            Group group = groups[i];
            bundle.putString(Job.KEY_GROUP_ID + i, group.id);
            bundle.putString(Job.KEY_GROUP_LABEL + i, group.label);
            bundle.putStringArray(Job.KEY_GROUP_VERSIONS + i, group.versions);
            bundle.putStringArray(Job.KEY_GROUP_FORMAT_IDS + i, group.formatIds);
        }
        return bundle;
    }

    static Formats fromBundle(Bundle bundle) {
        int count = bundle.getInt(Job.KEY_GROUP_COUNT, 0);
        Group[] groups = new Group[count];
        for (int i = 0; i < count; i++) {
            groups[i] = new Group(
                    bundle.getString(Job.KEY_GROUP_ID + i, ""),
                    bundle.getString(Job.KEY_GROUP_LABEL + i, ""),
                    orEmpty(bundle.getStringArray(Job.KEY_GROUP_VERSIONS + i)),
                    orEmpty(bundle.getStringArray(Job.KEY_GROUP_FORMAT_IDS + i))
            );
        }
        return new Formats(bundle.getString(Job.KEY_ENGINE_VERSION, "?"), groups);
    }

    private static String[] orEmpty(String[] values) {
        return values == null ? new String[0] : values;
    }
}
