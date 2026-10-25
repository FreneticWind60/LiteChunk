package com.frenetic.litechunk.api;

/**
 * A single output version inside a {@link FormatGroup}.
 */
public final class FormatVersion {
    /**
     * The id handed back to {@link ChunkerEngine#convert}, e.g. {@code JAVA_1_21_6} or {@code BEDROCK_1_21_120}.
     */
    public final String id;
    /**
     * The bare version, e.g. {@code 1.21.6}.
     */
    public final String version;

    public FormatVersion(String id, String version) {
        this.id = id;
        this.version = version;
    }

    @Override
    public String toString() {
        return version;
    }
}
