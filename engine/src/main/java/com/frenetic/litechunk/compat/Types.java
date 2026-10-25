package com.frenetic.litechunk.compat;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SortedMap;

/**
 * Builds {@link ParameterizedType} instances from code instead of from {@code TypeToken} anonymous
 * subclasses.
 * <p>
 * {@code new TypeToken<List<Foo>>() {}} works by reading
 * {@code getClass().getGenericSuperclass()} at runtime, which requires the {@code Signature} class
 * attribute to survive dexing. R8 drops that attribute for anonymous classes even with
 * {@code -keepattributes Signature}, so on device {@code TypeToken} throws
 * {@code IllegalArgumentException: class ... isn't parameterized} the first time it is constructed.
 * <p>
 * Declaring the parameterised type explicitly removes the dependency on reflection and on the
 * attribute surviving the shrinker, and behaves identically on a desktop JVM and on ART.
 */
public final class Types {

    private Types() {
    }

    public static ParameterizedType listOf(Type element) {
        return new Impl(List.class, null, element);
    }

    public static ParameterizedType mapOf(Type key, Type value) {
        return new Impl(Map.class, null, key, value);
    }

    public static ParameterizedType sortedMapOf(Type key, Type value) {
        return new Impl(SortedMap.class, null, key, value);
    }

    public static ParameterizedType parameterized(Type raw, Type... arguments) {
        return new Impl(raw, null, arguments);
    }

    /**
     * Immutable {@link ParameterizedType} with value semantics, matching the contract the JDK
     * implementation uses (and therefore Gson's type cache keys).
     */
    static final class Impl implements ParameterizedType {

        private final Type rawType;
        private final Type[] arguments;
        private final Type ownerType;

        Impl(Type rawType, Type ownerType, Type... arguments) {
            this.rawType = Objects.requireNonNull(rawType, "rawType");
            this.arguments = arguments == null ? new Type[0] : arguments.clone();
            this.ownerType = ownerType;
        }

        @Override
        public Type[] getActualTypeArguments() {
            return arguments.clone();
        }

        @Override
        public Type getRawType() {
            return rawType;
        }

        @Override
        public Type getOwnerType() {
            return ownerType;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof ParameterizedType)) {
                return false;
            }
            ParameterizedType that = (ParameterizedType) other;
            return Objects.equals(rawType, that.getRawType())
                    && Objects.equals(ownerType, that.getOwnerType())
                    && Arrays.equals(arguments, that.getActualTypeArguments());
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(arguments) ^ Objects.hashCode(rawType) ^ Objects.hashCode(ownerType);
        }

        @Override
        public String toString() {
            return getTypeName();
        }
    }
}
