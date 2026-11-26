package dev.oxidyn.noise;

import org.jetbrains.annotations.Nullable;

/**
 * Mixed into NormalNoise and BlendedNoise. Returns the compiled native copy
 * of that noise, building it on first use, or null if we can't (no backend,
 * or something about the noise we don't understand).
 */
public interface NativeNoiseHolder {
    @Nullable
    Object oxidyn$nativeHandle();
}
