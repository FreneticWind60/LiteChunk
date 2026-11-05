package com.frenetic.litechunk.api;

/**
 * Options for a single conversion.
 * <p>
 * This is a value object on purpose: it crosses the {@code DexClassLoader} boundary between the app and the engine,
 * so it must not reference anything the engine owns. Adding fields here is source compatible for the app, and the
 * engine treats an unknown combination as "do less rather than fail".
 */
public final class ConversionOptions {

    /** Everything on: the behaviour of every engine build before this class existed. */
    public static final ConversionOptions DEFAULT = new ConversionOptions(true);

    /** Terrain and block entities only. */
    public static final ConversionOptions NO_ENTITIES = new ConversionOptions(false);

    private final boolean entities;

    public ConversionOptions(boolean entities) {
        this.entities = entities;
    }

    /**
     * Whether mobs, dropped items and the rest of the entities should be converted.
     * <p>
     * When false the engine skips entity work entirely, which is roughly 10% of the wall clock on a large world and
     * leaves a world that still opens (it is simply empty of mobs). When true the engine converts terrain first and
     * entities afterwards, in a separate pass.
     *
     * @return true when entities should be converted.
     */
    public boolean entities() {
        return entities;
    }

    @Override
    public String toString() {
        return "ConversionOptions{entities=" + entities + '}';
    }
}
