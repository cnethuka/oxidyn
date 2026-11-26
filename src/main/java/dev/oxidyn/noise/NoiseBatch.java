package dev.oxidyn.noise;

import com.mojang.serialization.MapCodec;
import dev.oxidyn.ffi.NativeBackend;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

/**
 * Batched noise sampling for DensityFunction.fillArray.
 *
 * <p>The trick: instead of walking the ContextProvider ourselves (NoiseChunk's
 * forIndex/fillAllDirectly juggle a bunch of internal counters, and the two
 * don't leave it in quite the same state), we let it run its normal
 * fillAllDirectly loop over a fake density function that just writes down the
 * coordinates it's handed. Then all of them go to Rust in one call.
 *
 * <p>For ShiftedNoise the recorder also evaluates the shift functions, in the
 * same x, y, z order vanilla does, so any caches they sit behind see exactly
 * the same calls they would have without us.
 */
public final class NoiseBatch {
    // Below this the downcall overhead isn't worth it. fillArray is normally
    // called with 49 (a column of cell corners) or 128 (a cell) entries anyway.
    private static final int MIN_BATCH = 8;

    private static final ThreadLocal<Recorder> RECORDERS = ThreadLocal.withInitial(Recorder::new);

    private NoiseBatch() {
    }

    /** DensityFunctions.Noise: noise(blockX * xzScale, blockY * yScale, blockZ * xzScale) */
    public static boolean fillNoise(NativeBackend backend, Object handle, double[] out,
                                    DensityFunction.ContextProvider ctx, double xzScale, double yScale) {
        if (out.length < MIN_BATCH) return false;
        Recorder r = acquire();
        try {
            r.begin(Recorder.SCALED, out.length, xzScale, yScale, null, null, null);
            ctx.fillAllDirectly(out, r);
            backend.sampleNormalNoise(handle, r.xs, r.ys, r.zs, out, r.count);
        } finally {
            r.release();
        }
        return true;
    }

    /** DensityFunctions.ShiftedNoise: same as above plus shiftX/Y/Z(ctx) */
    public static boolean fillShiftedNoise(NativeBackend backend, Object handle, double[] out,
                                           DensityFunction.ContextProvider ctx, double xzScale, double yScale,
                                           DensityFunction shiftX, DensityFunction shiftY, DensityFunction shiftZ) {
        if (out.length < MIN_BATCH) return false;
        Recorder r = acquire();
        try {
            r.begin(Recorder.SHIFTED, out.length, xzScale, yScale, shiftX, shiftY, shiftZ);
            ctx.fillAllDirectly(out, r);
            backend.sampleNormalNoise(handle, r.xs, r.ys, r.zs, out, r.count);
        } finally {
            r.release();
        }
        return true;
    }

    /** BlendedNoise: takes raw block coordinates, the multipliers live in the native copy */
    public static boolean fillBlended(NativeBackend backend, Object handle, double[] out, DensityFunction.ContextProvider ctx) {
        if (out.length < MIN_BATCH) return false;
        Recorder r = acquire();
        try {
            r.begin(Recorder.RAW, out.length, 1.0, 1.0, null, null, null);
            ctx.fillAllDirectly(out, r);
            backend.sampleBlendedNoise(handle, r.xs, r.ys, r.zs, out, r.count);
        } finally {
            r.release();
        }
        return true;
    }

    private static Recorder acquire() {
        Recorder r = RECORDERS.get();
        // A shift function could in theory end up in another batched fillArray
        // while we're mid-recording. Never seen it with vanilla worldgen, but
        // datapacks get creative, so hand out a throwaway one instead.
        return r.busy ? new Recorder() : r;
    }

    private static final class Recorder implements DensityFunction {
        static final int SCALED = 0;
        static final int SHIFTED = 1;
        static final int RAW = 2;

        double[] xs = new double[128];
        double[] ys = new double[128];
        double[] zs = new double[128];
        int count;
        boolean busy;

        private int mode;
        private double xzScale;
        private double yScale;
        private DensityFunction shiftX;
        private DensityFunction shiftY;
        private DensityFunction shiftZ;

        void begin(int mode, int size, double xzScale, double yScale,
                   DensityFunction shiftX, DensityFunction shiftY, DensityFunction shiftZ) {
            if (xs.length < size) {
                xs = new double[size];
                ys = new double[size];
                zs = new double[size];
            }
            this.busy = true;
            this.count = 0;
            this.mode = mode;
            this.xzScale = xzScale;
            this.yScale = yScale;
            this.shiftX = shiftX;
            this.shiftY = shiftY;
            this.shiftZ = shiftZ;
        }

        void release() {
            shiftX = shiftY = shiftZ = null;
            busy = false;
        }

        @Override
        public double compute(FunctionContext c) {
            int i = count;
            if (i == xs.length) {
                // some custom ContextProvider handed out more than array.length
                // entries; vanilla would have thrown already, so will we
                throw new IllegalStateException("context provider produced more points than the array holds");
            }
            switch (mode) {
                case SCALED -> {
                    xs[i] = (double) c.blockX() * xzScale;
                    ys[i] = (double) c.blockY() * yScale;
                    zs[i] = (double) c.blockZ() * xzScale;
                }
                case SHIFTED -> {
                    // keep vanilla's evaluation order: x, then y, then z
                    xs[i] = (double) c.blockX() * xzScale + shiftX.compute(c);
                    ys[i] = (double) c.blockY() * yScale + shiftY.compute(c);
                    zs[i] = (double) c.blockZ() * xzScale + shiftZ.compute(c);
                }
                default -> {
                    xs[i] = c.blockX();
                    ys[i] = c.blockY();
                    zs[i] = c.blockZ();
                }
            }
            count = i + 1;
            return 0.0;
        }

        @Override
        public void fillArray(double[] out, ContextProvider ctx) {
            ctx.fillAllDirectly(out, this);
        }

        @Override
        public DensityFunction mapAll(Visitor visitor) {
            return this;
        }

        @Override
        public double minValue() {
            return 0.0;
        }

        @Override
        public double maxValue() {
            return 0.0;
        }

        @Override
        public KeyDispatchDataCodec<? extends DensityFunction> codec() {
            // never serialized - it only exists for the duration of one fillArray
            return KeyDispatchDataCodec.of(MapCodec.unit(this));
        }
    }
}
