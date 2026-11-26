package dev.oxidyn.ffi;

import java.nio.ByteBuffer;

/**
 * What the rest of the mod sees of the native library.
 *
 * <p>This lives in the Java 21 part of the mod on purpose - the only
 * implementation ({@code dev.oxidyn.panama.PanamaBackend}) is compiled for
 * Java 22 and gets loaded reflectively, so nothing here can mention
 * {@code java.lang.foreign} types. Noise handles are passed around as plain
 * {@code Object}s for the same reason.
 */
public interface NativeBackend {
    /** zlib return codes, mirrored from native/src/deflate.rs */
    int ERR_BAD_DATA = -1;
    int ERR_NO_SPACE = -2;
    int ERR_BAD_LEVEL = -3;
    int ERR_PANIC = -4;

    String describe();

    // --- noise ---

    Object compileNormalNoise(double valueFactor, OctaveSpec[] first, OctaveSpec[] second);

    /**
     * @param slots 8 main + 16 min limit + 16 max limit octaves, in
     *              getOctaveNoise order. Entries may be null.
     */
    Object compileBlendedNoise(double xzMultiplier, double yMultiplier, double xzFactor, double yFactor,
                               double smearScaleMultiplier, OctaveSpec[] slots);

    void sampleNormalNoise(Object handle, double[] xs, double[] ys, double[] zs, double[] out, int count);

    void sampleBlendedNoise(Object handle, double[] xs, double[] ys, double[] zs, double[] out, int count);

    // --- translucency ---

    /**
     * Sorts quads far-to-near from the camera and writes 6 indices per quad
     * to the native address {@code dst}.
     */
    void sortQuads(float[] centroids, int quads, float cx, float cy, float cz, long dst, int indexBytes);

    // --- zlib ---

    long zlibBound(long length);

    /** Compresses src's remaining bytes into dst's remaining space. Doesn't move positions. */
    int zlibCompress(int level, ByteBuffer src, ByteBuffer dst);

    /** Same as above, other direction. Returns ERR_NO_SPACE if dst is too small. */
    int zlibDecompress(ByteBuffer src, ByteBuffer dst);
}
