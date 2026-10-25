package com.frenetic.litechunk.api;

/**
 * Result of {@link ChunkerEngine#detect(java.io.File)}.
 */
public final class Detection {
    /**
     * Group id, {@code JAVA} or {@code BEDROCK}.
     */
    public final String formatId;
    /**
     * User facing label, e.g. {@code Java 版}.
     */
    public final String formatLabel;
    /**
     * Detected version, e.g. {@code 1.21.6}.
     */
    public final String version;

    public Detection(String formatId, String formatLabel, String version) {
        this.formatId = formatId;
        this.formatLabel = formatLabel;
        this.version = version;
    }

    @Override
    public String toString() {
        return formatLabel + " " + version;
    }
}
