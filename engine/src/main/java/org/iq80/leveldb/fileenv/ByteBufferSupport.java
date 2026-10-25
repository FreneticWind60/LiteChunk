package org.iq80.leveldb.fileenv;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;

/**
 * ChunkBridge replacement for the upstream {@code ByteBufferSupport}.
 * <p>
 * The original resolves {@code sun.misc.Unsafe#invokeCleaner} through a MethodHandle and, when that is missing, falls
 * back to reflecting on {@code java.nio.DirectByteBuffer}. ART has neither {@code invokeCleaner} nor
 * {@code DirectByteBuffer} (it uses {@code DirectByteBufferImpl}), so the fallback ends in
 * {@code throw new AssertionError(cause)} inside the static initialiser. That surfaces as an
 * {@code ExceptionInInitializerError} the first time a memory mapped table is closed, i.e. at the end of every Bedrock
 * conversion.
 * <p>
 * This version tries the same unmapping reflectively and simply gives up when the runtime does not provide it. On
 * Android the mapping is then released by the runtime's cleaner once the buffer becomes unreachable, which costs some
 * address space until the next GC but keeps the conversion alive. On a desktop JVM (used by {@code roundTrip}) the
 * original behaviour is preserved.
 * <p>
 * The upstream class is removed from the leveldb jar while the engine dex is assembled, see {@code engineDex} in
 * {@code engine/build.gradle.kts}.
 */
final class ByteBufferSupport {

    private static final Object UNSAFE;
    private static final Method INVOKE_CLEANER;

    static {
        Object unsafe = null;
        Method cleaner = null;
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            unsafe = theUnsafe.get(null);
            cleaner = unsafeClass.getMethod("invokeCleaner", ByteBuffer.class);
        } catch (Throwable ignored) {
            // Android: sun.misc.Unsafe has no invokeCleaner, unmapping is left to the GC.
        }
        UNSAFE = unsafe;
        INVOKE_CLEANER = cleaner;
    }

    private ByteBufferSupport() {
    }

    static void unmap(MappedByteBuffer buffer) {
        if (INVOKE_CLEANER == null) {
            return;
        }
        try {
            INVOKE_CLEANER.invoke(UNSAFE, buffer);
        } catch (Throwable ignored) {
            // Best effort only, never fail a conversion because a mapping could not be released.
        }
    }
}
