package dev.oxidyn.noise;

import dev.oxidyn.Oxidyn;
import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.OctaveSpec;
import dev.oxidyn.mixin.noise.BlendedNoiseAccessor;
import dev.oxidyn.mixin.noise.ImprovedNoiseAccessor;
import dev.oxidyn.mixin.noise.NormalNoiseAccessor;
import dev.oxidyn.mixin.noise.PerlinNoiseAccessor;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns vanilla noise objects into the flat description the native sampler
 * reads. The per-octave factors are produced with the same loop vanilla runs
 * in PerlinNoise.getValue, so the doubles come out identical.
 */
public final class NoiseCompiler {
    private NoiseCompiler() {
    }

    @Nullable
    public static Object compile(NativeBackend backend, NormalNoise noise) {
        try {
            NormalNoiseAccessor n = (NormalNoiseAccessor) noise;
            return backend.compileNormalNoise(n.oxidyn$valueFactor(), octaves(n.oxidyn$first()), octaves(n.oxidyn$second()));
        } catch (RuntimeException e) {
            Oxidyn.LOG.warn("Couldn't compile a NormalNoise for native sampling, it'll stay on the Java path", e);
            return null;
        }
    }

    @Nullable
    public static Object compile(NativeBackend backend, BlendedNoise noise) {
        try {
            BlendedNoiseAccessor b = (BlendedNoiseAccessor) noise;
            OctaveSpec[] slots = new OctaveSpec[8 + 16 + 16];
            // getOctaveNoise(i) indexes from the end of the array, BlendedNoise
            // iterates it that way and the native side expects the same order
            fillSlots(slots, 0, 8, b.oxidyn$mainNoise());
            fillSlots(slots, 8, 16, b.oxidyn$minLimitNoise());
            fillSlots(slots, 24, 16, b.oxidyn$maxLimitNoise());
            return backend.compileBlendedNoise(b.oxidyn$xzMultiplier(), b.oxidyn$yMultiplier(),
                    b.oxidyn$xzFactor(), b.oxidyn$yFactor(), b.oxidyn$smearScaleMultiplier(), slots);
        } catch (RuntimeException e) {
            Oxidyn.LOG.warn("Couldn't compile a BlendedNoise for native sampling, it'll stay on the Java path", e);
            return null;
        }
    }

    private static OctaveSpec[] octaves(PerlinNoise perlin) {
        PerlinNoiseAccessor p = (PerlinNoiseAccessor) perlin;
        ImprovedNoise[] levels = p.oxidyn$noiseLevels();
        DoubleList amplitudes = p.oxidyn$amplitudes();

        List<OctaveSpec> out = new ArrayList<>(levels.length);
        double inputFactor = p.oxidyn$lowestFreqInputFactor();
        double valueFactor = p.oxidyn$lowestFreqValueFactor();
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] != null) {
                out.add(spec(levels[i], amplitudes.getDouble(i), inputFactor, valueFactor));
            }
            inputFactor *= 2.0;
            valueFactor /= 2.0;
        }
        return out.toArray(OctaveSpec[]::new);
    }

    private static void fillSlots(OctaveSpec[] slots, int start, int count, PerlinNoise perlin) {
        for (int i = 0; i < count; i++) {
            ImprovedNoise octave = perlin.getOctaveNoise(i);
            // BlendedNoise doesn't use amplitudes or factors, only the raw octave
            slots[start + i] = octave == null ? null : spec(octave, 0.0, 0.0, 0.0);
        }
    }

    private static OctaveSpec spec(ImprovedNoise noise, double amplitude, double inputFactor, double valueFactor) {
        // ImprovedNoise is final, so javac needs the detour through Object
        byte[] perm = ((ImprovedNoiseAccessor) (Object) noise).oxidyn$permutation().clone();
        return new OctaveSpec(noise.xo, noise.yo, noise.zo, amplitude, inputFactor, valueFactor, perm);
    }
}
