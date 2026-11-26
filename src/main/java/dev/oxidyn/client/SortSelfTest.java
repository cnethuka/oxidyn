package dev.oxidyn.client;

import com.mojang.blaze3d.vertex.VertexSorting;
import dev.oxidyn.ffi.NativeBackend;
import org.joml.Vector3f;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.SplittableRandom;

/** Client-only half of the self-test. Don't touch this class on a server. */
public final class SortSelfTest {
    private SortSelfTest() {
    }

    public static boolean check(NativeBackend backend) {
        SplittableRandom rng = new SplittableRandom(99);
        for (int n : new int[]{1, 30, 49, 1500}) {
            Vector3f[] centroids = new Vector3f[n];
            for (int i = 0; i < n; i++) {
                // half-block grid inside a section, so there are plenty of ties
                centroids[i] = new Vector3f(rng.nextInt(32) * 0.5f, rng.nextInt(32) * 0.5f, rng.nextInt(32) * 0.5f);
            }
            float cx = (float) rng.nextDouble(-40, 40), cy = (float) rng.nextDouble(-40, 40), cz = (float) rng.nextDouble(-40, 40);
            int[] want = VertexSorting.byDistance(cx, cy, cz).sort(centroids);

            if (!matches(backend, centroids, want, cx, cy, cz, 2) || !matches(backend, centroids, want, cx, cy, cz, 4)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(NativeBackend backend, Vector3f[] centroids, int[] want,
                                   float cx, float cy, float cz, int indexBytes) {
        int n = centroids.length;
        float[] flat = CentroidCache.flatten(centroids);
        ByteBuffer buf = MemoryUtil.memAlloc(n * 6 * indexBytes);
        try {
            backend.sortQuads(flat, n, cx, cy, cz, MemoryUtil.memAddress(buf), indexBytes);
            for (int q = 0; q < n; q++) {
                int base = want[q] * 4;
                int[] expect = {base, base + 1, base + 2, base + 2, base + 3, base};
                for (int k = 0; k < 6; k++) {
                    int at = (q * 6 + k) * indexBytes;
                    int got = indexBytes == 2 ? Short.toUnsignedInt(buf.getShort(at)) : buf.getInt(at);
                    int exp = indexBytes == 2 ? expect[k] & 0xFFFF : expect[k];
                    if (got != exp) return false;
                }
            }
            return true;
        } finally {
            MemoryUtil.memFree(buf);
        }
    }
}
