package dev.oxidyn.ffi;

/**
 * Flattened copy of one ImprovedNoise, plus the amplitude and the frequency /
 * value factors its parent PerlinNoise would use for it.
 */
public record OctaveSpec(double xo, double yo, double zo,
                         double amplitude, double inputFactor, double valueFactor,
                         byte[] permutation) {
    public OctaveSpec {
        if (permutation.length != 256) {
            throw new IllegalArgumentException("permutation table must be 256 bytes, got " + permutation.length);
        }
    }
}
