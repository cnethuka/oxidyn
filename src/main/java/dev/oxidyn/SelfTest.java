package dev.oxidyn;

import dev.oxidyn.client.SortSelfTest;
import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.noise.NoiseCompiler;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.neoforged.fml.loading.FMLEnvironment;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.SplittableRandom;
import java.util.function.BooleanSupplier;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Runs each native path once against the vanilla code at startup and turns
 * off anything that doesn't match exactly. Takes a few ms. This is the safety
 * net in case some platform's libm/compiler does something surprising, or
 * another mod has changed how vanilla noise works under our feet.
 */
final class SelfTest {
    record Result(boolean noise, boolean sort, boolean zlib) {
    }

    private SelfTest() {
    }

    static Result run(NativeBackend backend) {
        long start = System.nanoTime();
        boolean noise = guard("noise", () -> checkNoise(backend));
        boolean zlib = guard("zlib", () -> checkZlib(backend));
        boolean sort = FMLEnvironment.dist.isClient() && guard("translucent sort", () -> SortSelfTest.check(backend));
        Oxidyn.LOG.info("Self-test finished in {} ms (noise={}, zlib={}, sort={})",
                (System.nanoTime() - start) / 1_000_000, noise, zlib, sort);
        return new Result(noise, sort, zlib);
    }

    private static boolean guard(String name, BooleanSupplier check) {
        try {
            if (check.getAsBoolean()) return true;
            Oxidyn.LOG.error("Native {} doesn't match vanilla on this machine - disabling it. Please report this!", name);
        } catch (Throwable t) {
            Oxidyn.LOG.error("Native {} self-test blew up - disabling it", name, t);
        }
        return false;
    }

    // --- noise ---

    private static boolean checkNoise(NativeBackend backend) {
        SplittableRandom rng = new SplittableRandom(0x0D15EA5EL);
        boolean ok = true;

        // continentalness-ish, something with a zero amplitude gap, and a legacy nether one
        NormalNoise[] noises = {
                NormalNoise.create(new XoroshiroRandomSource(rng.nextLong()),
                        new NormalNoise.NoiseParameters(-9, 1.0, 1.0, 2.0, 2.0, 2.0, 1.0, 1.0, 1.0, 1.0)),
                NormalNoise.create(new XoroshiroRandomSource(rng.nextLong()),
                        new NormalNoise.NoiseParameters(-3, 1.0, 0.0, 0.5, 1.5)),
                NormalNoise.createLegacyNetherBiome(new LegacyRandomSource(rng.nextLong()),
                        new NormalNoise.NoiseParameters(-7, 1.0, 1.0)),
        };
        for (NormalNoise noise : noises) {
            Object handle = NoiseCompiler.compile(backend, noise);
            if (handle == null) return false;
            int n = 4096;
            double[] xs = new double[n], ys = new double[n], zs = new double[n], got = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = coord(rng);
                ys[i] = coord(rng);
                zs[i] = coord(rng);
            }
            backend.sampleNormalNoise(handle, xs, ys, zs, got, n);
            for (int i = 0; i < n; i++) {
                double want = noise.getValue(xs[i], ys[i], zs[i]);
                if (Double.doubleToLongBits(want) != Double.doubleToLongBits(got[i])) {
                    Oxidyn.LOG.error("NormalNoise mismatch at ({}, {}, {}): vanilla {} native {}", xs[i], ys[i], zs[i], want, got[i]);
                    ok = false;
                    break;
                }
            }
        }

        // overworld and nether-ish settings
        BlendedNoise[] blended = {
                new BlendedNoise(new XoroshiroRandomSource(rng.nextLong()), 0.25, 0.125, 80.0, 160.0, 8.0),
                new BlendedNoise(new XoroshiroRandomSource(rng.nextLong()), 0.25, 0.375, 80.0, 60.0, 8.0),
        };
        for (BlendedNoise noise : blended) {
            Object handle = NoiseCompiler.compile(backend, noise);
            if (handle == null) return false;
            int n = 2048;
            double[] xs = new double[n], ys = new double[n], zs = new double[n], got = new double[n];
            for (int i = 0; i < n; i++) {
                xs[i] = rng.nextInt(-30_000_000, 30_000_000);
                ys[i] = rng.nextInt(-64, 320);
                zs[i] = rng.nextInt(-30_000_000, 30_000_000);
            }
            backend.sampleBlendedNoise(handle, xs, ys, zs, got, n);
            for (int i = 0; i < n; i++) {
                double want = noise.compute(new DensityFunction.SinglePointContext((int) xs[i], (int) ys[i], (int) zs[i]));
                if (Double.doubleToLongBits(want) != Double.doubleToLongBits(got[i])) {
                    Oxidyn.LOG.error("BlendedNoise mismatch at ({}, {}, {}): vanilla {} native {}", xs[i], ys[i], zs[i], want, got[i]);
                    ok = false;
                    break;
                }
            }
        }
        return ok;
    }

    private static double coord(SplittableRandom rng) {
        return switch (rng.nextInt(4)) {
            case 0 -> rng.nextDouble(-512, 512);                 // spawn-ish
            case 1 -> rng.nextDouble(-3.0E7, 3.0E7);             // world border, exercises wrap()
            case 2 -> rng.nextInt(-100_000, 100_000) * 0.25;     // quart positions, lots of exact integers
            default -> rng.nextInt(-4096, 4096) * 1.0181268882175227;
        };
    }

    // --- zlib ---

    private static boolean checkZlib(NativeBackend backend) {
        byte[] data = sampleData(300_000);

        // native -> java
        byte[] packed = new byte[(int) backend.zlibBound(data.length)];
        int n = backend.zlibCompress(6, ByteBuffer.wrap(data), ByteBuffer.wrap(packed));
        if (n <= 0) return false;
        byte[] back = new byte[data.length];
        Inflater inf = new Inflater();
        try {
            inf.setInput(packed, 0, n);
            if (inf.inflate(back) != data.length || !inf.finished() || !Arrays.equals(back, data)) return false;
        } catch (DataFormatException e) {
            return false;
        } finally {
            inf.end();
        }

        // java -> native, through direct buffers this time
        Deflater def = new Deflater();
        byte[] jpacked = new byte[data.length + 1024];
        int jn;
        try {
            def.setInput(data);
            def.finish();
            jn = def.deflate(jpacked);
        } finally {
            def.end();
        }
        ByteBuffer src = ByteBuffer.allocateDirect(jn).put(0, jpacked, 0, jn);
        ByteBuffer dst = ByteBuffer.allocateDirect(data.length);
        if (backend.zlibDecompress(src, dst) != data.length) return false;
        byte[] out = new byte[data.length];
        dst.get(0, out);
        if (!Arrays.equals(out, data)) return false;

        // and too-small output has to come back as NO_SPACE, region loading relies on that
        return backend.zlibDecompress(ByteBuffer.wrap(packed, 0, n), ByteBuffer.allocate(100)) == NativeBackend.ERR_NO_SPACE;
    }

    /** Vaguely chunk-like: long runs, a few repeating patterns, some noise. */
    private static byte[] sampleData(int size) {
        SplittableRandom rng = new SplittableRandom(1234);
        byte[] b = new byte[size];
        int i = 0;
        while (i < size) {
            int run = rng.nextInt(1, 200);
            byte v = (byte) rng.nextInt(16);
            boolean random = rng.nextInt(8) == 0;
            for (int j = 0; j < run && i < size; j++, i++) {
                b[i] = random ? (byte) rng.nextInt() : v;
            }
        }
        return b;
    }
}
