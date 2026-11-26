package dev.oxidyn.client;

import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Vector3f;

/**
 * Same thing as {@code VertexSorting.byDistance(x, y, z)}, except it keeps
 * the camera position around where we can read it. Vanilla's version is a
 * lambda capturing a Vector3f, so there's no way to get at it from outside.
 *
 * <p>{@link #sort} still does the vanilla thing; the native shortcut lives in
 * the SortState mixin, which recognises this class.
 */
public final class CameraDistanceSorting implements VertexSorting {
    public final float x;
    public final float y;
    public final float z;
    private VertexSorting fallback;

    public CameraDistanceSorting(float x, float y, float z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    @Override
    public int[] sort(Vector3f[] centroids) {
        VertexSorting f = fallback;
        if (f == null) {
            fallback = f = VertexSorting.byDistance(x, y, z);
        }
        return f.sort(centroids);
    }
}
