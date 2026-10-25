package com.frenetic.litechunk.api;

/**
 * A group of output formats, e.g. all supported Java Edition versions.
 */
public final class FormatGroup {
    /**
     * Stable id of the group, {@code JAVA} or {@code BEDROCK}.
     */
    public final String id;
    /**
     * User facing label, e.g. {@code Java 版}.
     */
    public final String label;
    /**
     * Versions of this edition, newest first.
     */
    public final FormatVersion[] versions;

    public FormatGroup(String id, String label, FormatVersion[] versions) {
        this.id = id;
        this.label = label;
        this.versions = versions;
    }

    @Override
    public String toString() {
        return label;
    }
}
