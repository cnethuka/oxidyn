package dev.oxidyn.gen;

import java.util.concurrent.atomic.LongAdder;

/**
 * Per-chunk timing of the noise fill step, native and vanilla, so the two
 * can be compared in game with /oxidyn stats. Only the fill step itself;
 * surface, features and lighting aren't in here.
 */
public final class FillStats {
    private static final LongAdder NATIVE_CHUNKS = new LongAdder();
    private static final LongAdder NATIVE_NANOS = new LongAdder();
    private static final LongAdder VANILLA_CHUNKS = new LongAdder();
    private static final LongAdder VANILLA_NANOS = new LongAdder();

    // doFill runs start to finish on one worker thread
    private static final ThreadLocal<long[]> VANILLA_START = ThreadLocal.withInitial(() -> new long[]{-1});
    private static final ThreadLocal<long[]> VERIFY_NANOS = ThreadLocal.withInitial(() -> new long[1]);

    private FillStats() {
    }

    public static void nativeFill(long startNanos) {
        long[] verify = VERIFY_NANOS.get();
        // the first chunk of a dimension also runs vanilla once for the check; leave that out
        NATIVE_NANOS.add(System.nanoTime() - startNanos - verify[0]);
        verify[0] = 0;
        NATIVE_CHUNKS.increment();
    }

    public static void vanillaStarted(long startNanos) {
        VERIFY_NANOS.get()[0] = 0;
        VANILLA_START.get()[0] = startNanos;
    }

    public static void vanillaFinished() {
        long[] start = VANILLA_START.get();
        if (start[0] < 0) return;
        VANILLA_NANOS.add(System.nanoTime() - start[0]);
        VANILLA_CHUNKS.increment();
        start[0] = -1;
    }

    static void verification(long nanos) {
        VERIFY_NANOS.get()[0] += nanos;
    }

    public static void reset() {
        NATIVE_CHUNKS.reset();
        NATIVE_NANOS.reset();
        VANILLA_CHUNKS.reset();
        VANILLA_NANOS.reset();
    }

    public static long nativeChunks() {
        return NATIVE_CHUNKS.sum();
    }

    public static long vanillaChunks() {
        return VANILLA_CHUNKS.sum();
    }

    /** average milliseconds per chunk, NaN if none yet */
    public static double nativeAvgMs() {
        return avg(NATIVE_NANOS.sum(), NATIVE_CHUNKS.sum());
    }

    public static double vanillaAvgMs() {
        return avg(VANILLA_NANOS.sum(), VANILLA_CHUNKS.sum());
    }

    private static double avg(long nanos, long chunks) {
        return chunks == 0 ? Double.NaN : nanos / 1e6 / chunks;
    }
}
