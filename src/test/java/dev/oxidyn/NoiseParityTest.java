package dev.oxidyn;

import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.Natives;
import dev.oxidyn.noise.NativeNoiseHolder;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Goes through the actual mixin'd fillArray methods and compares against
 * calling compute() point by point, which we never touch.
 */
class NoiseParityTest {
    private static NativeBackend backend;

    @BeforeAll
    static void load() {
        assumeTrue(Runtime.version().feature() >= 22, "native path needs Java 22+");
        backend = Natives.get();
        assertNotNull(backend, Natives.status());
        Features.noise = true;
    }

    @Test
    void selfTestPasses() {
        SelfTest.Result r = SelfTest.run(backend);
        assertTrue(r.noise(), "noise");
        assertTrue(r.zlib(), "zlib");
    }

    @Test
    void noiseFunction() {
        var params = Holder.direct(new NormalNoise.NoiseParameters(-8, 1.5, 0.0, 1.0, 0.0, 0.0, 0.0));
        compare(seeded(DensityFunctions.noise(params, 0.25, 0.75), 1));
    }

    @Test
    void shiftedNoise() {
        var offset = Holder.direct(new NormalNoise.NoiseParameters(-3, 1.0, 1.0, 1.0, 0.0));
        var temperature = Holder.direct(new NormalNoise.NoiseParameters(-10, 1.5, 0.0, 1.0, 0.0, 0.0, 0.0));
        DensityFunction f = DensityFunctions.shiftedNoise2d(
                DensityFunctions.shiftA(offset), DensityFunctions.shiftB(offset), 0.25, temperature);
        compare(seeded(f, 2));
    }

    @Test
    void blendedNoise() {
        BlendedNoise noise = new BlendedNoise(new XoroshiroRandomSource(3), 0.25, 0.125, 80.0, 160.0, 8.0);
        // make sure we're really testing the native path and not a silent fallback
        assertNotNull(((NativeNoiseHolder) (Object) noise).oxidyn$nativeHandle());
        compare(noise);
    }

    @Test
    void normalNoiseGetsCompiled() {
        NormalNoise noise = NormalNoise.create(new XoroshiroRandomSource(4), -4, 1.0, 1.0);
        assertNotNull(((NativeNoiseHolder) noise).oxidyn$nativeHandle());
    }

    private static void compare(DensityFunction f) {
        SplittableRandom rng = new SplittableRandom(7);
        for (int round = 0; round < 50; round++) {
            int n = 8 + rng.nextInt(200);
            DensityFunction.FunctionContext[] points = new DensityFunction.FunctionContext[n];
            for (int i = 0; i < n; i++) {
                points[i] = new DensityFunction.SinglePointContext(
                        rng.nextInt(-30_000_000, 30_000_000), rng.nextInt(-64, 320), rng.nextInt(-30_000_000, 30_000_000));
            }
            double[] want = new double[n];
            for (int i = 0; i < n; i++) want[i] = f.compute(points[i]);

            double[] got = new double[n];
            f.fillArray(got, new ListProvider(points));
            assertArrayEquals(want, got, 0.0, () -> "fillArray differs for " + f);
        }
    }

    /** Gives every NoiseHolder an actual noise instance, like RandomState does. */
    private static DensityFunction seeded(DensityFunction f, long seed) {
        return f.mapAll(new DensityFunction.Visitor() {
            @Override
            public DensityFunction apply(DensityFunction fn) {
                return fn;
            }

            @Override
            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
                long s = seed ^ holder.noiseData().value().firstOctave();
                return new DensityFunction.NoiseHolder(holder.noiseData(),
                        NormalNoise.create(new XoroshiroRandomSource(s), holder.noiseData().value()));
            }
        });
    }

    private record ListProvider(DensityFunction.FunctionContext[] points) implements DensityFunction.ContextProvider {
        @Override
        public DensityFunction.FunctionContext forIndex(int i) {
            return points[i];
        }

        @Override
        public void fillAllDirectly(double[] out, DensityFunction f) {
            for (int i = 0; i < out.length; i++) out[i] = f.compute(points[i]);
        }
    }
}
