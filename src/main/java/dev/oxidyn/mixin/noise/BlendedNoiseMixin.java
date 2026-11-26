package dev.oxidyn.mixin.noise;

import dev.oxidyn.Features;
import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.Natives;
import dev.oxidyn.noise.NativeNoiseHolder;
import dev.oxidyn.noise.NoiseBatch;
import dev.oxidyn.noise.NoiseCompiler;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

/**
 * BlendedNoise is the big one - up to 40 octave samples per point for the
 * overworld's base 3D terrain. Vanilla only has the interface default
 * fillArray (point by point), so we add a batched one.
 */
@Mixin(BlendedNoise.class)
public abstract class BlendedNoiseMixin implements NativeNoiseHolder, DensityFunction.SimpleFunction {
    @Unique
    private static final Object FAILED = new Object();

    @Unique
    private volatile Object oxidyn$handle;

    @Override
    public Object oxidyn$nativeHandle() {
        Object h = oxidyn$handle;
        if (h == null) {
            NativeBackend backend = Natives.get();
            Object built = backend == null ? null : NoiseCompiler.compile(backend, (BlendedNoise) (Object) this);
            oxidyn$handle = h = built == null ? FAILED : built;
        }
        return h == FAILED ? null : h;
    }

    @Override
    public void fillArray(double[] out, DensityFunction.ContextProvider ctx) {
        if (Features.noise) {
            Object handle = oxidyn$nativeHandle();
            if (handle != null && NoiseBatch.fillBlended(Natives.get(), handle, out, ctx)) {
                return;
            }
        }
        ctx.fillAllDirectly(out, this);
    }
}
