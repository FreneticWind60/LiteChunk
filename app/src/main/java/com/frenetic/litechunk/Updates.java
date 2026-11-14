package com.frenetic.litechunk;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What GitHub knows about published builds, and how it compares to the running one.
 * <p>
 * Two kinds of build live in the same repo: a tagged release that is marked stable ("1.21.0-Build3") and previews
 * ("1.21.0-Build4-pr6"). They are ordered, not just different: a preview is newer than the stable it builds on, and
 * older than the stable that eventually replaces it. That ordering is what {@link Version} encodes, using the same
 * layout the app puts in its version code:
 * <pre>
 * major * 1e8 + minor * 1e6 + patch * 1e4 + build * 100 + stage
 * </pre>
 * where {@code stage} is the preview number, or {@link #STABLE} for a release.
 */
final class Updates {

    /** Stage number used by a stable build, so it sorts above every preview of the same build number. */
    static final int STABLE = 50;

    private static final String REPO = "FreneticWind60/LiteChunk";
    private static final String RELEASES_API = "https://api.github.com/repos/" + REPO + "/releases?per_page=30";
    private static final String VERSION_MD =
            "https://raw.githubusercontent.com/" + REPO + "/main/VERSION.md";
    static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";

    private static final Pattern VERSION = Pattern.compile(
            "^\\s*v?(\\d+)\\.(\\d+)\\.(\\d+)"
                    + "(?:[-\\s]+[Bb]uild\\s*(\\d+))?"
                    + "(?:[-\\s]+[Pp][Rr]\\s*(\\d+))?");

    private Updates() {
    }

    // ---------------------------------------------------------------------------------------------
    // Version
    // ---------------------------------------------------------------------------------------------

    /** A comparable version, parsed from a tag, a release name or {@code BuildConfig.VERSION_NAME}. */
    static final class Version implements Comparable<Version> {
        final int major;
        final int minor;
        final int patch;
        final int build;
        final int stage;

        Version(int major, int minor, int patch, int build, int stage) {
            this.major = major;
            this.minor = minor;
            this.patch = patch;
            this.build = build;
            this.stage = stage;
        }

        /** @return the parsed version, or null when the text is not one (a release named something else). */
        static Version parse(String text) {
            if (text == null) {
                return null;
            }
            Matcher matcher = VERSION.matcher(text);
            if (!matcher.find()) {
                return null;
            }
            try {
                int build = matcher.group(4) == null ? 0 : Integer.parseInt(matcher.group(4));
                int stage = matcher.group(5) == null ? STABLE : Integer.parseInt(matcher.group(5));
                return new Version(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)),
                        build,
                        stage);
            } catch (NumberFormatException failure) {
                return null;
            }
        }

        boolean isStable() {
            return stage >= STABLE;
        }

        /** {@code 121000406}, the same number the manifest carries. */
        long code() {
            return (long) major * 100_000_000L + (long) minor * 1_000_000L + (long) patch * 10_000L
                    + (long) build * 100L + Math.min(stage, 99);
        }

        @Override
        public int compareTo(Version other) {
            return Long.compare(code(), other.code());
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Version version && code() == version.code();
        }

        @Override
        public int hashCode() {
            return Long.hashCode(code());
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Releases
    // ---------------------------------------------------------------------------------------------

    /** One published build. */
    static final class Release {
        final String tag;
        final String name;
        final String url;
        final boolean prerelease;
        final Version version;

        Release(String tag, String name, String url, boolean prerelease, Version version) {
            this.tag = tag;
            this.name = name;
            this.url = url;
            this.prerelease = prerelease;
            this.version = version;
        }

        /** What to show the user: the app's own spelling, e.g. {@code 1.21.0 build4 pr6}. */
        String label() {
            return name == null || name.isEmpty() ? tag : name;
        }
    }

    /** Everything the check found. Any of the releases may be missing (nothing published, or only one kind). */
    static final class Result {
        Release stable;
        Release preview;
        /** Version text -> the "这一版做了什么" cell from VERSION.md. Empty when the file could not be read. */
        final Map<String, String> notes = new HashMap<>();
    }

    // ---------------------------------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------------------------------

    static Result check() throws IOException {
        Result result = new Result();
        JSONArray array;
        try {
            array = new JSONArray(read(RELEASES_API));
        } catch (org.json.JSONException failure) {
            throw new IOException("unexpected payload", failure);
        }
        List<Release> releases = new ArrayList<>();
        for (int index = 0; index < array.length(); index++) {
            JSONObject entry = array.optJSONObject(index);
            if (entry == null) {
                continue;
            }
            String tag = entry.optString("tag_name", "");
            Version version = Version.parse(tag);
            if (version == null) {
                continue; // A release tagged something we cannot order is not something to offer.
            }
            releases.add(new Release(
                    tag,
                    entry.optString("name", ""),
                    entry.optString("html_url", RELEASES_PAGE),
                    entry.optBoolean("prerelease", false),
                    version));
        }

        // The API lists newest first, but "newest" is by publication date; a hotfix can be published after a preview
        // that is still ahead of it, so the highest version wins rather than the first row.
        for (Release release : releases) {
            if (release.prerelease) {
                if (result.preview == null || release.version.compareTo(result.preview.version) > 0) {
                    result.preview = release;
                }
            } else if (result.stable == null || release.version.compareTo(result.stable.version) > 0) {
                result.stable = release;
            }
        }

        try {
            result.notes.putAll(readVersionNotes());
        } catch (Throwable ignored) {
            // The notes are a nicety: no table, no "这一版做了什么", the dialog still says which version exists.
        }
        return result;
    }

    /**
     * Read the release table out of the repo's {@code VERSION.md}.
     * <p>
     * The file is a markdown table whose first column is the version and second the summary; anything else in the file
     * is ignored. Keys are {@link #key(String)} normalised so a lookup does not care about dashes and spaces.
     */
    static Map<String, String> readVersionNotes() throws IOException {
        Map<String, String> notes = new HashMap<>();
        for (String line : read(VERSION_MD).split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("|")) {
                continue;
            }
            String[] cells = trimmed.split("\\|");
            if (cells.length < 3) {
                continue;
            }
            String version = cells[1].trim();
            String summary = cells[2].trim();
            if (version.isEmpty() || summary.isEmpty() || version.startsWith("-")) {
                continue;
            }
            notes.put(key(version), summary);
        }
        return notes;
    }

    /** Letters and digits only, so "1.21.0 build4 pr6" and "1.21.0-Build4-pr6" land on the same key. */
    static String key(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    static String read(String endpoint) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(endpoint).openConnection();
        connection.setConnectTimeout(8000);
        connection.setReadTimeout(8000);
        connection.setRequestProperty("Accept", "application/vnd.github+json");
        connection.setRequestProperty("User-Agent", "LiteChunk/" + BuildConfig.VERSION_NAME);
        try {
            int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                byte[] chunk = new byte[8 * 1024];
                int read;
                while ((read = in.read(chunk)) > 0) {
                    buffer.write(chunk, 0, read);
                }
                return buffer.toString(StandardCharsets.UTF_8);
            }
        } finally {
            connection.disconnect();
        }
    }
}
