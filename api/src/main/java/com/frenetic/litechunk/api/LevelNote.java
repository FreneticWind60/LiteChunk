package com.frenetic.litechunk.api;

/**
 * A finding produced while inspecting {@code level.dat}.
 * <p>
 * Notes are the actual value of the read-only screen: the fields say what a world <em>is</em>, the notes say what is
 * probably <em>wrong</em> with it. Every note is bilingual for the same reason as {@link LevelField}.
 */
public final class LevelNote {

    /** Something worth knowing, not a problem. */
    public static final int SEVERITY_INFO = 0;
    /** Something that will make the world behave unexpectedly in game. */
    public static final int SEVERITY_WARN = 1;
    /** Something that will stop the world from opening, or that this build cannot fix. */
    public static final int SEVERITY_ERROR = 2;

    /** One of the {@code SEVERITY_*} constants. */
    public final int severity;
    public final String textZh;
    public final String textEn;

    public LevelNote(int severity, String textZh, String textEn) {
        this.severity = severity;
        this.textZh = textZh;
        this.textEn = textEn;
    }
}
