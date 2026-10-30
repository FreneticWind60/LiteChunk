package com.hivemc.chunker.conversion.intermediate.column.chunk.palette;

import com.google.common.base.Preconditions;
import it.unimi.dsi.fastutil.shorts.ShortOpenHashSet;
import it.unimi.dsi.fastutil.shorts.ShortSet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A palette which uses shorts for indexing the palette keys and storing those keys.
 * <p>
 * The values are held in a single flat array rather than {@code short[16][16][16]}. The nested form allocated 273
 * arrays (1 outer + 16 + 256) to hold 8 KB of data, and because Java only keeps the last dimension contiguous a
 * {@code values[x][y][z]} walk jumped to a different array for every block. The flat form is one object, and
 * {@code (y << 8) | (z << 4) | x} is the same order the formats write their values in, so the hot loops can simply read
 * {@code values[i]}. The {@link Palette} contract is unchanged, this is only the internal storage.
 *
 * @param <T> the type held by the palette.
 */
public class ShortBasedPalette<T> implements WriteablePalette<T> {
    private final List<T> keys;
    private final short[] values;
    /**
     * log2 of the dimension size, so the index can be built with shifts: 4 for a chunk (16), 2 for 4x4x4 biomes.
     */
    private final int dimensionShift;

    /**
     * Create a short based palette from a pre-indexed list of keys and values.
     *
     * @param keys          the keys.
     * @param values        the flat values which point to indexes in the keys, in {@code (y, z, x)} order with x
     *                      varying fastest.
     * @param dimensionSize the dimension length, e.g. 16 for a chunk, to be used for the data.
     */
    public ShortBasedPalette(List<T> keys, short[] values, int dimensionSize) {
        Preconditions.checkArgument(dimensionSize > 0 && (dimensionSize & (dimensionSize - 1)) == 0,
                "dimensionSize must be a power of two, got %s", dimensionSize);
        Preconditions.checkArgument(values.length == dimensionSize * dimensionSize * dimensionSize,
                "expected %s values for a %s sized palette, got %s",
                dimensionSize * dimensionSize * dimensionSize, dimensionSize, values.length);
        this.keys = keys;
        this.values = values;
        this.dimensionShift = Integer.numberOfTrailingZeros(dimensionSize);
    }

    /**
     * Create a new short based palette.
     *
     * @param keySizeHint   the hint to use for the size of the keys.
     * @param dimensionSize the dimension length, e.g. 16 for a chunk, to be used for the data.
     */
    public ShortBasedPalette(int keySizeHint, int dimensionSize) {
        this(new ArrayList<>(keySizeHint),
                new short[dimensionSize * dimensionSize * dimensionSize],
                dimensionSize);
    }

    /**
     * Get the dimension length of this palette (16 for a chunk, 4 for a 4x4x4 biome palette).
     *
     * @return the dimension length.
     */
    public int getDimensionSize() {
        return 1 << dimensionShift;
    }

    /**
     * Get the backing value array which is storing the indexes of the keys.
     *
     * @return the backing flat short array, in {@code (y, z, x)} order with x varying fastest.
     */
    public short[] getValues() {
        return values;
    }

    /**
     * Build the flat index of a position.
     *
     * @param x the first dimension.
     * @param y the second dimension.
     * @param z the third dimension.
     * @return the index into the flat value array.
     */
    public int index(int x, int y, int z) {
        return (y << (dimensionShift + dimensionShift)) | (z << dimensionShift) | x;
    }

    @Override
    public int getKeyCount() {
        return keys.size();
    }

    @Override
    public T getKey(int keyIndex, T defaultValue) {
        if (keyIndex < 0 || keyIndex >= keys.size()) return defaultValue;
        return keys.get(keyIndex);
    }

    @Override
    public T get(int x, int y, int z, T defaultValue) {
        return getKey(values[index(x, y, z)], defaultValue);
    }

    @Override
    public short getOrCreateKey(T entry) {
        Preconditions.checkNotNull(entry);
        short index = (short) keys.indexOf(entry);
        if (index == -1) {
            index = (short) keys.size();
            keys.add(entry);
        }
        return index;
    }

    @Override
    public void set(int x, int y, int z, T key) {
        values[index(x, y, z)] = getOrCreateKey(key);
    }

    @Override
    public void setPaletteIndex(int x, int y, int z, short paletteIndex) {
        values[index(x, y, z)] = paletteIndex;
    }

    @Override
    public <U> Palette<U> map(Function<T, U> mappingFunction) {
        ArrayList<U> newKeys = new ArrayList<>(keys.size());
        for (T key : keys) {
            newKeys.add(mappingFunction.apply(key));
        }
        return new ShortBasedPalette<>(newKeys, values, getDimensionSize());
    }

    @Override
    public boolean containsValue(Predicate<T> predicate) {
        int size = keys.size();

        // If no keys, then it's empty
        if (size == 0) {
            return false;
        }

        // If single key, match against the first value
        if (size == 1) {
            return predicate.test(keys.get(0));
        }

        // Otherwise loop through the keys and identify indexes. The matching key indexes are held in two longs rather
        // than a set: a chunk palette almost never has more than 128 keys, and this is called once per section while
        // writing (to decide whether a liquid layer is needed), so an allocation here is per section per column.
        if (size <= 128) {
            long matchedLow = 0L;
            long matchedHigh = 0L;
            for (int i = 0; i < size; i++) {
                if (!predicate.test(keys.get(i))) continue;
                if (i < 64) {
                    matchedLow |= 1L << i;
                } else {
                    matchedHigh |= 1L << (i - 64);
                }
            }

            // Exit early if no matching identifiers
            if ((matchedLow | matchedHigh) == 0L) {
                return false;
            }

            for (short entry : values) {
                if (entry < 0 || entry >= size) continue; // Ignore invalid blocks

                // Return if it's a match
                if (entry < 64 ? (matchedLow & (1L << entry)) != 0L : (matchedHigh & (1L << (entry - 64))) != 0L) {
                    return true;
                }
            }

            return false;
        }

        // Fallback for palettes with more than 128 keys, which are rare
        ShortSet matchedIndexes = null;
        for (short i = 0; i < size; i++) {
            T identifier = keys.get(i);
            if (predicate.test(identifier)) {
                // Create the HashSet
                if (matchedIndexes == null) {
                    matchedIndexes = new ShortOpenHashSet(1);
                }

                // Add the index
                matchedIndexes.add(i);
            }
        }

        // Exit early if no matching identifiers
        if (matchedIndexes == null) {
            return false;
        }

        for (short entry : values) {
            if (entry < 0 || entry >= size) continue; // Ignore invalid blocks

            // Return if it's a match
            if (matchedIndexes.contains(entry)) return true;
        }

        // No matches
        return false;
    }

    @Override
    public boolean containsKey(Predicate<T> predicate) {
        for (T key : keys) {
            if (predicate.test(key)) return true;
        }
        return false;
    }

    @Override
    public boolean isEmpty() {
        return keys.isEmpty();
    }

    @Override
    public WriteablePalette<T> asWriteable() {
        return this; // We should already be writeable
    }

    @Override
    public Palette<T> compact(T defaultValue) {
        // Quick shortcut for empty palette
        if (keys.isEmpty()) return EmptyPalette.instance(getDimensionSize());

        // Create a remapping array for old index -> new index for keys
        short[] paletteRemapping = new short[keys.size()];
        Arrays.fill(paletteRemapping, (short) -1);

        // Create a copy of the old keys
        List<T> oldKeys = new ArrayList<>(keys);
        keys.clear();

        // Collect all the used palette ids. The walk order matters: new keys are appended in first-use order, so it
        // decides the index each key ends up with. This keeps the original (x, y, z) traversal with z innermost, which
        // keeps the output byte identical to the nested array version - the data would mean the same thing either way,
        // but identical output is worth more than a marginally more cache friendly loop.
        int dimension = getDimensionSize();
        for (int x = 0; x < dimension; x++) {
            for (int y = 0; y < dimension; y++) {
                for (int z = 0; z < dimension; z++) {
                    int index = index(x, y, z);
                    short oldKeyIndex = values[index];
                    boolean validKeyIndex = oldKeyIndex >= 0 && oldKeyIndex < oldKeys.size();
                    short newKeyIndex = validKeyIndex ? paletteRemapping[oldKeyIndex] : -1;

                    // If the newKeyIndex is -1, we need to check if this is already in our new keys or needs adding
                    if (newKeyIndex == -1) {
                        T oldValue = validKeyIndex ? oldKeys.get(oldKeyIndex) : defaultValue;
                        newKeyIndex = (short) keys.indexOf(oldValue);

                        // If it wasn't in the new keys, create a key for it
                        if (newKeyIndex == -1) {
                            newKeyIndex = (short) keys.size();
                            keys.add(oldValue);
                        }

                        // Update the mappings with this new old -> new index (if the key is valid)
                        if (validKeyIndex) {
                            paletteRemapping[oldKeyIndex] = newKeyIndex;
                        }
                    }

                    // Update the value
                    values[index] = newKeyIndex;
                }
            }
        }

        // Check if there's an easier way to represent this palette
        if (keys.size() == 1) return new SingleValuePalette<>(getDimensionSize(), keys.get(0));

        // Otherwise return this
        return this;
    }

    @Override
    public ShortBasedPalette<T> copy() {
        return new ShortBasedPalette<>(new ArrayList<>(keys), values.clone(), getDimensionSize());
    }
}
