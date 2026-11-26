package dev.oxidyn.panama;

import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.OctaveSpec;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.ByteBuffer;
import java.nio.file.Path;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_FLOAT;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * The FFM side. Compiled for Java 22, only ever instantiated reflectively by
 * {@code Natives} after it has checked the runtime version.
 *
 * <p>A note on {@code critical(true)}: those downcalls skip the usual thread
 * state transition and are allowed to take heap segments, so we can hand Rust
 * a double[] without copying it anywhere. The catch is that GC can't run while
 * one is in flight, so only short calls get it - noise batches and quad sorts
 * are a few microseconds. zlib can take milliseconds on a big chunk, so those
 * use normal downcalls and off-heap memory.
 */
public final class PanamaBackend implements NativeBackend {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final Linker.Option CRITICAL = Linker.Option.critical(true);

    private final String description;

    private final MethodHandle noiseNormal;
    private final MethodHandle noiseBlended;
    private final MethodHandle sortQuads;
    private final MethodHandle genCreate;
    private final MethodHandle genFree;
    private final MethodHandle genFill;
    private final MethodHandle zlibBound;
    private final MethodHandle zlibCompress;
    private final MethodHandle zlibDecompress;

    public PanamaBackend(Path library, int expectedAbi) throws Throwable {
        // global arena: the library stays mapped for the life of the JVM,
        // there's no sane moment to unload it anyway
        SymbolLookup lib = SymbolLookup.libraryLookup(library, Arena.global());

        MethodHandle abi = LINKER.downcallHandle(find(lib, "oxidyn_abi_version"), FunctionDescriptor.of(JAVA_INT));
        int actual = (int) abi.invokeExact();
        if (actual != expectedAbi) {
            throw new IllegalStateException("native library " + library + " has ABI " + actual + ", expected " + expectedAbi);
        }

        FunctionDescriptor noise = FunctionDescriptor.ofVoid(ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT);
        noiseNormal = LINKER.downcallHandle(find(lib, "oxidyn_noise_normal"), noise, CRITICAL);
        noiseBlended = LINKER.downcallHandle(find(lib, "oxidyn_noise_blended"), noise, CRITICAL);

        sortQuads = LINKER.downcallHandle(find(lib, "oxidyn_sort_quads"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, JAVA_FLOAT, JAVA_FLOAT, JAVA_FLOAT, ADDRESS, JAVA_INT),
                CRITICAL);

        // chunk fills take milliseconds, so these are ordinary downcalls
        genCreate = LINKER.downcallHandle(find(lib, "oxidyn_gen_create"),
                FunctionDescriptor.of(ADDRESS, ADDRESS, JAVA_LONG, ADDRESS));
        genFree = LINKER.downcallHandle(find(lib, "oxidyn_gen_free"), FunctionDescriptor.ofVoid(ADDRESS));
        genFill = LINKER.downcallHandle(find(lib, "oxidyn_gen_fill"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_LONG));

        zlibBound = LINKER.downcallHandle(find(lib, "oxidyn_zlib_bound"), FunctionDescriptor.of(JAVA_LONG, JAVA_LONG));
        zlibCompress = LINKER.downcallHandle(find(lib, "oxidyn_zlib_compress"),
                FunctionDescriptor.of(JAVA_LONG, JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));
        zlibDecompress = LINKER.downcallHandle(find(lib, "oxidyn_zlib_decompress"),
                FunctionDescriptor.of(JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS, JAVA_LONG));

        description = library.getFileName() + " (abi " + actual + ", Java " + Runtime.version().feature() + ")";
    }

    private static MemorySegment find(SymbolLookup lib, String name) {
        return lib.find(name).orElseThrow(() -> new UnsatisfiedLinkError("missing native symbol " + name));
    }

    @Override
    public String describe() {
        return description;
    }

    // --- noise ---

    @Override
    public Object compileNormalNoise(double valueFactor, OctaveSpec[] first, OctaveSpec[] second) {
        return NoiseLayouts.normal(valueFactor, first, second);
    }

    @Override
    public Object compileBlendedNoise(double xzMultiplier, double yMultiplier, double xzFactor, double yFactor,
                                      double smearScaleMultiplier, OctaveSpec[] slots) {
        return NoiseLayouts.blended(xzMultiplier, yMultiplier, xzFactor, yFactor, smearScaleMultiplier, slots);
    }

    @Override
    public void sampleNormalNoise(Object handle, double[] xs, double[] ys, double[] zs, double[] out, int count) {
        sample(noiseNormal, handle, xs, ys, zs, out, count);
    }

    @Override
    public void sampleBlendedNoise(Object handle, double[] xs, double[] ys, double[] zs, double[] out, int count) {
        sample(noiseBlended, handle, xs, ys, zs, out, count);
    }

    private static void sample(MethodHandle mh, Object handle, double[] xs, double[] ys, double[] zs, double[] out, int count) {
        if (count > xs.length || count > ys.length || count > zs.length || count > out.length) {
            // Rust trusts the count blindly, so this check is not optional
            throw new IndexOutOfBoundsException("noise batch of " + count + " doesn't fit the arrays");
        }
        try {
            mh.invokeExact((MemorySegment) handle,
                    MemorySegment.ofArray(xs), MemorySegment.ofArray(ys), MemorySegment.ofArray(zs),
                    MemorySegment.ofArray(out), count);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // --- translucency ---

    @Override
    public void sortQuads(float[] centroids, int quads, float cx, float cy, float cz, long dst, int indexBytes) {
        if ((long) quads * 3 > centroids.length) {
            throw new IndexOutOfBoundsException(quads + " quads but only " + centroids.length + " floats");
        }
        int rc;
        try {
            rc = (int) sortQuads.invokeExact(MemorySegment.ofArray(centroids), quads, cx, cy, cz,
                    MemorySegment.ofAddress(dst), indexBytes);
        } catch (Throwable t) {
            throw rethrow(t);
        }
        if (rc != 0) {
            throw new IllegalArgumentException("native sort rejected index size " + indexBytes);
        }
    }

    // --- chunk fill ---

    @Override
    public long noiseAddress(Object handle) {
        return ((MemorySegment) handle).address();
    }

    @Override
    public long genCreate(long[] words, int[] error) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment w = arena.allocateFrom(JAVA_LONG, words);
            MemorySegment err = arena.allocate(JAVA_INT);
            MemorySegment prog = (MemorySegment) genCreate.invokeExact(w, (long) words.length, err);
            error[0] = err.get(JAVA_INT, 0);
            return prog.address();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public void genFree(long program) {
        try {
            genFree.invokeExact(MemorySegment.ofAddress(program));
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int genFill(long program, int[] ints, long[] longs, int[] pieces, int[] junctions, float[] kernel, byte[] out) {
        try (Arena arena = Arena.ofConfined()) {
            // FillParams: 12 ints then 4 longs, see native/src/gen/fill.rs
            MemorySegment params = arena.allocate(12 * 4 + 4 * 8, 8);
            MemorySegment.copy(ints, 0, params, JAVA_INT, 0, 12);
            MemorySegment.copy(longs, 0, params, JAVA_LONG, 48, 4);
            MemorySegment p = pieces.length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_INT, pieces);
            MemorySegment j = junctions.length == 0 ? MemorySegment.NULL : arena.allocateFrom(JAVA_INT, junctions);
            MemorySegment dst = arena.allocate(out.length);
            int rc = (int) genFill.invokeExact(MemorySegment.ofAddress(program), params, p, j, kernel(kernel), dst, (long) out.length);
            if (rc == 0) {
                MemorySegment.copy(dst, 0, MemorySegment.ofArray(out), 0, out.length);
            }
            return rc;
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    // the beard kernel never changes, keep one off-heap copy around
    private volatile MemorySegment kernelCopy;

    private MemorySegment kernel(float[] kernel) {
        MemorySegment k = kernelCopy;
        if (k == null) {
            k = Arena.global().allocateFrom(JAVA_FLOAT, kernel);
            kernelCopy = k;
        }
        return k;
    }

    // --- zlib ---

    @Override
    public long zlibBound(long length) {
        try {
            return (long) zlibBound.invokeExact(length);
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }

    @Override
    public int zlibCompress(int level, ByteBuffer src, ByteBuffer dst) {
        return Scratch.transfer(src, dst, (in, out) ->
                (long) zlibCompress.invokeExact(level, in, in.byteSize(), out, out.byteSize()));
    }

    @Override
    public int zlibDecompress(ByteBuffer src, ByteBuffer dst) {
        return Scratch.transfer(src, dst, (in, out) ->
                (long) zlibDecompress.invokeExact(in, in.byteSize(), out, out.byteSize()));
    }

    static RuntimeException rethrow(Throwable t) {
        if (t instanceof RuntimeException r) return r;
        if (t instanceof Error e) throw e;
        return new IllegalStateException(t);
    }

}
