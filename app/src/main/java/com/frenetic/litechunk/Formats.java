package com.frenetic.litechunk;

import com.frenetic.litechunk.sdk.ChunkBridge;
import com.frenetic.litechunk.sdk.FormatGroup;
import com.frenetic.litechunk.sdk.FormatVersion;

import java.util.ArrayList;
import java.util.List;

/**
 * Snapshot of the formats the engine supports, in the shape the conversion page wants.
 * <p>
 * The SDK already publishes a catalog, but it models the editions as objects; the page keeps asking for parallel
 * arrays (label plus format id per row) because the spinner and the version picker both key off the array index.
 * Building that shape once here keeps the UI free of {@code FormatGroup} plumbing and keeps the engine unloaded
 * until a conversion actually starts.
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

    /** Read the catalog asset the SDK ships; this never loads Chunker. */
    static Formats load() {
        List<Group> groups = new ArrayList<>();
        for (FormatGroup group : ChunkBridge.formats()) {
            List<FormatVersion> versions = group.getVersions();
            String[] labels = new String[versions.size()];
            String[] ids = new String[versions.size()];
            for (int i = 0; i < versions.size(); i++) {
                labels[i] = versions.get(i).getDisplayName();
                ids[i] = versions.get(i).getFormatId();
            }
            groups.add(new Group(group.getEditionId(), group.getEditionLabel(), labels, ids));
        }
        return new Formats(ChunkBridge.engineVersion(), groups.toArray(new Group[0]));
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
}
