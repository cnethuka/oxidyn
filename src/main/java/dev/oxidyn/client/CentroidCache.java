package dev.oxidyn.client;

import org.joml.Vector3f;

public final class CentroidCache {
    private CentroidCache() {
    }

    /** Vector3f[] -> xyzxyz... */
    public static float[] flatten(Vector3f[] centroids) {
        float[] flat = new float[centroids.length * 3];
        for (int i = 0, j = 0; i < centroids.length; i++, j += 3) {
            Vector3f c = centroids[i];
            flat[j] = c.x;
            flat[j + 1] = c.y;
            flat[j + 2] = c.z;
        }
        return flat;
    }
}
