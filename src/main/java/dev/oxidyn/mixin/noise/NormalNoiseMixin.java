package dev.oxidyn.mixin.noise;

import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.Natives;
import dev.oxidyn.noise.NativeNoiseHolder;
import dev.oxidyn.noise.NoiseCompiler;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(NormalNoise.class)
public abstract class NormalNoiseMixin implements NativeNoiseHolder {
    // null = not built yet, FAILED = tried and gave up
    @Unique
    private static final Object FAILED = new Object();

    @Unique
    private volatile Object oxidyn$handle;

    @Override
    public Object oxidyn$nativeHandle() {
        Object h = oxidyn$handle;
        if (h == null) {
            // racing worldgen threads may both build it; harmless, one wins
            NativeBackend backend = Natives.get();
            Object built = backend == null ? null : NoiseCompiler.compile(backend, (NormalNoise) (Object) this);
            oxidyn$handle = h = built == null ? FAILED : built;
        }
        return h == FAILED ? null : h;
    }
}
