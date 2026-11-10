package com.frenetic.litechunk.commands;

import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Rewrites a {@code /particle} command for the other edition.
 * <p>
 * The two commands are not the same shape at all:
 * <ul>
 *     <li>Java: {@code particle <name> <pos> <delta> <speed> <count> [force|normal] [<viewers>]}, where a name that
 *     takes options carries them as an SNBT compound glued to the name since 1.20.5
 *     ({@code dust{color:[1,0,0],scale:1}}) and as separate arguments before that;</li>
 *     <li>Bedrock: {@code particle <id> [x y z]} - just a name and a position.</li>
 * </ul>
 * So converting is not a rename: the extra arguments have to go (Bedrock refuses to parse the command otherwise) and
 * the name has to be translated through {@link ParticleIds}.
 * <p>
 * Only a command whose text starts with {@code particle} is touched. Anything wrapped in {@code execute ... run} is
 * left alone: the two editions also disagree about {@code execute} syntax, and half-converting a command would make
 * the failure harder to see than leaving it in its original form.
 */
public final class ParticleCommand {

    private static final String NAMESPACE = "minecraft:";
    private static final String COMMAND = "particle";
    /** Java defaults for a position-less particle: one particle, no spread. */
    private static final String JAVA_TAIL = " 0 0 0 1 1";

    /** The tail of an {@code execute}; only the part after it is rewritten. */
    private static final Pattern RUN_PARTICLE = Pattern.compile("(?i)\\brun\\s+(?=particle\\s)");

    private ParticleCommand() {
    }

    /**
     * Rewrite a Java {@code /particle} command for Bedrock.
     *
     * @param command the command as stored in the command block, with or without a leading slash.
     * @return the Bedrock command, or null when this is not a particle command or the name has no counterpart.
     */
    @Nullable
    public static String toBedrock(@Nullable String command) {
        return rewrite(command, true);
    }

    /**
     * Rewrite a Bedrock {@code /particle} command for Java.
     *
     * @param command the command as stored in the command block, with or without a leading slash.
     * @return the Java command, or null when this is not a particle command or the name has no counterpart.
     */
    @Nullable
    public static String toJava(@Nullable String command) {
        return rewrite(command, false);
    }

    /**
     * Rewrite the particle command, including the common case where it is the tail of an {@code execute}.
     * <p>
     * The prefix before {@code run} is kept verbatim: the two editions disagree about plenty of {@code execute}
     * syntax, and rewriting the part that is not a particle command would only move the failure somewhere harder to
     * see. If the tail cannot be translated, the whole command is left alone.
     *
     * @param command   the raw command.
     * @param toBedrock true for Java to Bedrock, false for the other direction.
     * @return the rewritten command, or null when nothing should change.
     */
    @Nullable
    private static String rewrite(@Nullable String command, boolean toBedrock) {
        if (command == null) {
            return null;
        }
        String trimmed = command.trim();
        String prefix = "";
        String body = trimmed;

        Matcher matcher = RUN_PARTICLE.matcher(trimmed);
        if (matcher.find()) {
            prefix = trimmed.substring(0, matcher.end());
            body = trimmed.substring(matcher.end());
        }

        String converted = toBedrock ? convertToBedrock(body) : convertToJava(body);
        return converted == null ? null : prefix + converted;
    }

    @Nullable
    private static String convertToBedrock(String command) {
        String[] tokens = tokens(command);
        if (tokens == null || tokens.length < 2) {
            return null;
        }

        String rawName = tokens[1];
        boolean inlineOptions = rawName.indexOf('{') >= 0;
        String name = bareName(rawName);
        String mapped = ParticleIds.toBedrock(name);
        if (mapped == null) {
            return null;
        }

        // Old-format option arguments sit between the name and the position; the SNBT spelling keeps them inside the
        // name, so they must not be skipped twice.
        int position = 2 + (inlineOptions ? 0 : ParticleIds.javaOptionCount(name));
        StringBuilder output = new StringBuilder(48).append(COMMAND).append(' ').append(NAMESPACE).append(mapped);
        if (tokens.length >= position + 3) {
            output.append(' ').append(tokens[position])
                    .append(' ').append(tokens[position + 1])
                    .append(' ').append(tokens[position + 2]);
        }
        return output.toString();
    }

    @Nullable
    private static String convertToJava(String command) {
        String[] tokens = tokens(command);
        if (tokens == null || tokens.length < 2) {
            return null;
        }

        String mapped = ParticleIds.toJava(bareName(tokens[1]));
        if (mapped == null) {
            return null;
        }

        StringBuilder output = new StringBuilder(48).append(COMMAND).append(' ').append(NAMESPACE).append(mapped);
        if (tokens.length >= 5) {
            output.append(' ').append(tokens[2])
                    .append(' ').append(tokens[3])
                    .append(' ').append(tokens[4]);
        } else {
            // Bedrock allows the position to be omitted; Java needs one, and the command block's own position is what
            // the game would have used.
            output.append(" ~ ~ ~");
        }
        return output.append(JAVA_TAIL).toString();
    }

    /**
     * @param command the raw command.
     * @return the whitespace separated tokens, or null when this is not a particle command.
     */
    @Nullable
    private static String[] tokens(@Nullable String command) {
        if (command == null) {
            return null;
        }
        String trimmed = command.trim();
        if (trimmed.startsWith("/")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.isEmpty()) {
            return null;
        }
        String[] tokens = trimmed.split("\\s+");
        if (!COMMAND.equalsIgnoreCase(tokens[0])) {
            return null;
        }
        return tokens;
    }

    /**
     * @param token a particle name, possibly namespaced and possibly carrying an SNBT option compound.
     * @return the bare name, e.g. {@code flame} for {@code minecraft:flame} or {@code dust{scale:1}}.
     */
    private static String bareName(String token) {
        String name = token;
        int brace = name.indexOf('{');
        if (brace >= 0) {
            name = name.substring(0, brace);
        }
        // Bedrock allows the name to be quoted.
        name = name.replace("\"", "");
        return name.startsWith(NAMESPACE) ? name.substring(NAMESPACE.length()) : name;
    }
}
