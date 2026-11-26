package dev.oxidyn;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.oxidyn.client.CentroidCache;
import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.Natives;
import net.minecraft.core.Holder;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.joml.Vector3f;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.SplittableRandom;
import java.util.function.Consumer;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Rough before/after numbers. Not JMH, so take them with a grain of salt,
 * but the warmup is long enough for C2 to be done with both sides.
 * Run with: ./gradlew test -Pbench -Prun_jdk=22 --tests dev.oxidyn.Bench
 */
@Tag("bench")
class Bench {
    @Test
    void run() throws Exception {
        assumeTrue(Runtime.version().feature() >= 22);
        NativeBackend backend = Natives.get();
        assumeTrue(backend != null);

        // NoiseInterpolator fills one column of cell corners at a time:
        // 49 points, 8 blocks apart in y, same x/z. Use a few such columns.
        int n = 49 * 4;
        DensityFunction.FunctionContext[] pts = new DensityFunction.FunctionContext[n];
        SplittableRandom rng = new SplittableRandom(1);
        for (int c = 0; c < 4; c++) {
            int x = rng.nextInt(-5000, 5000) & ~3, z = rng.nextInt(-5000, 5000) & ~3;
            for (int i = 0; i < 49; i++) {
                pts[c * 49 + i] = new DensityFunction.SinglePointContext(x, -64 + i * 8, z);
            }
        }
        Provider ctx = new Provider(pts);
        double[] out = new double[n];

        var params = Holder.direct(new NormalNoise.NoiseParameters(-9, 1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0));
        DensityFunction noise = DensityFunctions.noise(params, 1.0, 1.0).mapAll(new DensityFunction.Visitor() {
            public DensityFunction apply(DensityFunction f) {
                return f;
            }

            public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder h) {
                return new DensityFunction.NoiseHolder(h.noiseData(), NormalNoise.create(new XoroshiroRandomSource(1), h.noiseData().value()));
            }
        });
        compare("noise (9 octaves), 4 columns", v -> {
            Features.noise = v;
            return () -> noise.fillArray(out, ctx);
        });

        BlendedNoise blended = new BlendedNoise(new XoroshiroRandomSource(2), 0.25, 0.125, 80.0, 160.0, 8.0);
        compare("blended noise, 4 columns", v -> {
            Features.noise = v;
            return () -> blended.fillArray(out, ctx);
        });

        // a section with a lot of water surface / glass
        int quads = 4096;
        Vector3f[] centroids = new Vector3f[quads];
        for (int i = 0; i < quads; i++) {
            centroids[i] = new Vector3f(rng.nextInt(32) * 0.5f, rng.nextInt(32) * 0.5f, rng.nextInt(32) * 0.5f);
        }
        float[] flat = CentroidCache.flatten(centroids);
        ByteBuffer idx = MemoryUtil.memAlloc(quads * 6 * 4);
        long addr = MemoryUtil.memAddress(idx);
        compare("translucent resort, 4096 quads", v -> v
                ? () -> backend.sortQuads(flat, quads, 3.5f, 20f, -7f, addr, 4)
                : () -> {
                    int[] order = VertexSorting.byDistance(3.5f, 20f, -7f).sort(centroids);
                    long p = addr;
                    for (int q : order) {
                        int b = q * 4;
                        MemoryUtil.memPutInt(p, b);
                        MemoryUtil.memPutInt(p + 4, b + 1);
                        MemoryUtil.memPutInt(p + 8, b + 2);
                        MemoryUtil.memPutInt(p + 12, b + 2);
                        MemoryUtil.memPutInt(p + 16, b + 3);
                        MemoryUtil.memPutInt(p + 20, b);
                        p += 24;
                    }
                });
        MemoryUtil.memFree(idx);

        byte[] chunk = chunkish(rng, 180_000);
        byte[] packedBuf = new byte[(int) backend.zlibBound(chunk.length)];
        Deflater def = new Deflater();
        compare("zlib compress, 180 KB", v -> v
                ? () -> backend.zlibCompress(6, ByteBuffer.wrap(chunk), ByteBuffer.wrap(packedBuf))
                : () -> {
                    def.reset();
                    def.setInput(chunk);
                    def.finish();
                    while (!def.finished()) def.deflate(packedBuf);
                });

        int packedLen = backend.zlibCompress(6, ByteBuffer.wrap(chunk), ByteBuffer.wrap(packedBuf));
        byte[] plain = new byte[chunk.length];
        Inflater inf = new Inflater();
        compare("zlib decompress, 180 KB", v -> v
                ? () -> backend.zlibDecompress(ByteBuffer.wrap(packedBuf, 0, packedLen), ByteBuffer.wrap(plain))
                : () -> {
                    inf.reset();
                    inf.setInput(packedBuf, 0, packedLen);
                    try {
                        inf.inflate(plain);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
        System.out.printf("compressed size: native %d bytes, ratio %.2f%n", packedLen, chunk.length / (double) packedLen);
    }

    interface Setup {
        Runnable make(boolean nativeOn) throws Exception;
    }

    private static void compare(String name, Setup setup) throws Exception {
        double vanilla = time(setup.make(false));
        double ours = time(setup.make(true));
        System.out.printf("BENCH %-32s vanilla %10.1f us   native %10.1f us   %.2fx%n", name, vanilla, ours, vanilla / ours);
        Features.noise = false;
    }

    private static double time(Runnable r) {
        long deadline = System.nanoTime() + 2_000_000_000L;
        while (System.nanoTime() < deadline) r.run();
        double best = Double.MAX_VALUE;
        for (int round = 0; round < 10; round++) {
            int iters = 0;
            long t0 = System.nanoTime();
            long t;
            do {
                r.run();
                iters++;
                t = System.nanoTime();
            } while (t - t0 < 200_000_000L);
            best = Math.min(best, (t - t0) / 1000.0 / iters);
        }
        return best;
    }

    private static byte[] chunkish(SplittableRandom rng, int size) {
        byte[] b = new byte[size];
        int i = 0;
        while (i < size) {
            int run = rng.nextInt(1, 120);
            byte v = (byte) rng.nextInt(12);
            boolean noisy = rng.nextInt(5) == 0;
            for (int j = 0; j < run && i < size; j++, i++) b[i] = noisy ? (byte) rng.nextInt() : v;
        }
        return b;
    }

    private record Provider(DensityFunction.FunctionContext[] pts) implements DensityFunction.ContextProvider {
        public DensityFunction.FunctionContext forIndex(int i) {
            return pts[i];
        }

        public void fillAllDirectly(double[] out, DensityFunction f) {
            for (int i = 0; i < out.length; i++) out[i] = f.compute(pts[i]);
        }
    }
}
