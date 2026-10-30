package com.hivemc.chunker.resolver;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A resolver represents a way to resolve an input/output to an intermediate format.
 *
 * @param <T> the input/output format.
 * @param <U> the intermediate format.
 */
public interface Resolver<T, U> {
    /**
     * Attempt to convert the input to the intermediate format.
     *
     * @param input the input value to convert.
     * @return an optional which is absent if there is no equivalent that could be resolved but otherwise the value as
     * intermediate format.
     */
    Optional<U> to(T input);

    /**
     * Attempt to convert the input to the intermediate format, without wrapping the result in an Optional.
     * <p>
     * The default delegates to {@link #to(Object)}, so a resolver only pays for this when it overrides it. Resolvers on
     * a hot path (per block / per palette entry) override it to skip the Optional allocation entirely - the lookup
     * itself rarely fails, so the wrapper is pure overhead there.
     *
     * @param input the input value to convert.
     * @return the intermediate value, or null if there is no equivalent.
     */
    @Nullable
    default U toOrNull(T input) {
        return to(input).orElse(null);
    }

    /**
     * Attempt to convert the intermediate format to the output format.
     *
     * @param input the input intermediate value to convert.
     * @return an optional which is absent if there is no equivalent that could be resolved but otherwise the value as
     * output format.
     */
    Optional<T> from(U input);

    /**
     * Chain another resolver with this resolver
     *
     * @param chained the resolver to chain after this resolver.
     * @param <V>     the new output type based on the output of the chained resolver.
     * @return a combined resolver which can convert from the input of this, to the output of the chained resolver.
     */
    default <V> Resolver<T, V> then(Resolver<U, V> chained) {
        return new ChainedResolver<>(this, chained);
    }

    /**
     * Get the equivalent of this resolver but where input is output and output is input.
     *
     * @return an inversed version of the current resolver.
     */
    default Resolver<U, T> inverse() {
        return new InversedResolver<>(this);
    }

    /**
     * Create a cached version of this resolver.
     * This is used when the result is an immutable object.
     *
     * @return a cached version which uses a loading cache.
     */
    default Resolver<T, U> cached() {
        Resolver<T, U> original = this;
        return new Resolver<>() {
            // ChunkBridge: Caffeine is replaced by plain maps. The cache is unbounded anyway and Caffeine 3.x
            // requires java.lang.invoke.VarHandle (API 33+), which is missing on Android 8.1 - 12L.
            private final Map<T, Optional<U>> toCache = new ConcurrentHashMap<>();
            private final Map<U, Optional<T>> fromCache = new ConcurrentHashMap<>();

            @Override
            public Optional<U> to(T input) {
                Optional<U> cached = toCache.get(input);
                if (cached != null) {
                    return cached;
                }
                Optional<U> value = original.to(input);
                toCache.put(input, value);
                return value;
            }

            @Override
            @Nullable
            public U toOrNull(T input) {
                return to(input).orElse(null);
            }

            @Override
            public Optional<T> from(U input) {
                Optional<T> cached = fromCache.get(input);
                if (cached != null) {
                    return cached;
                }
                Optional<T> value = original.from(input);
                fromCache.put(input, value);
                return value;
            }
        };
    }

    /**
     * A class combining two resolvers.
     *
     * @param <T> the input format.
     * @param <U> the intermediate format used between the resolvers.
     * @param <V> the output format.
     */
    class ChainedResolver<T, U, V> implements Resolver<T, V> {
        private final Resolver<T, U> original;
        private final Resolver<U, V> chained;

        private ChainedResolver(Resolver<T, U> original, Resolver<U, V> chained) {
            this.original = original;
            this.chained = chained;
        }

        @Override
        public Optional<V> to(T input) {
            return original.to(input).flatMap(chained::to);
        }

        @Override
        @Nullable
        public V toOrNull(T input) {
            U intermediate = original.toOrNull(input);
            return intermediate == null ? null : chained.toOrNull(intermediate);
        }

        @Override
        public Optional<T> from(V input) {
            return chained.from(input).flatMap(original::from);
        }
    }

    /**
     * A basic wrapper which inverses a resolver, this doesn't change any functionality and just reverses the
     * {@link #to(Object)} and {@link #from(Object)} methods.
     *
     * @param <T> the input format.
     * @param <U> the output format.
     */
    class InversedResolver<T, U> implements Resolver<T, U> {
        private final Resolver<U, T> original;

        private InversedResolver(Resolver<U, T> original) {
            this.original = original;
        }

        @Override
        public Optional<U> to(T input) {
            return original.from(input);
        }

        @Override
        public Optional<T> from(U input) {
            return original.to(input);
        }
    }
}
