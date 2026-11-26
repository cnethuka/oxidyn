package dev.oxidyn.mixin.noise;

import dev.oxidyn.Features;
import dev.oxidyn.ffi.Natives;
import dev.oxidyn.noise.NativeNoiseHolder;
import dev.oxidyn.noise.NoiseBatch;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@code minecraft:noise} density functions. */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$Noise")
public abstract class DensityNoiseMixin {
    @Shadow @Final private DensityFunction.NoiseHolder noise;
    @Shadow @Final @Deprecated private double xzScale;
    @Shadow @Final private double yScale;

    @Inject(method = "fillArray", at = @At("HEAD"), cancellable = true)
    private void oxidyn$fillArray(double[] out, DensityFunction.ContextProvider ctx, CallbackInfo ci) {
        if (!Features.noise) return;
        NormalNoise n = noise.noise();
        if (n == null) return;
        Object handle = ((NativeNoiseHolder) n).oxidyn$nativeHandle();
        if (handle != null && NoiseBatch.fillNoise(Natives.get(), handle, out, ctx, xzScale, yScale)) {
            ci.cancel();
        }
    }
}
