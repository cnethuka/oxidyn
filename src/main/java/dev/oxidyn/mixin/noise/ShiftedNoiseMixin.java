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

/** {@code minecraft:shifted_noise} - temperature, vegetation, ridges and friends. */
@Mixin(targets = "net.minecraft.world.level.levelgen.DensityFunctions$ShiftedNoise")
public abstract class ShiftedNoiseMixin {
    @Shadow @Final private DensityFunction shiftX;
    @Shadow @Final private DensityFunction shiftY;
    @Shadow @Final private DensityFunction shiftZ;
    @Shadow @Final private double xzScale;
    @Shadow @Final private double yScale;
    @Shadow @Final private DensityFunction.NoiseHolder noise;

    @Inject(method = "fillArray", at = @At("HEAD"), cancellable = true)
    private void oxidyn$fillArray(double[] out, DensityFunction.ContextProvider ctx, CallbackInfo ci) {
        if (!Features.noise) return;
        NormalNoise n = noise.noise();
        if (n == null) return;
        Object handle = ((NativeNoiseHolder) n).oxidyn$nativeHandle();
        if (handle != null && NoiseBatch.fillShiftedNoise(Natives.get(), handle, out, ctx, xzScale, yScale, shiftX, shiftY, shiftZ)) {
            ci.cancel();
        }
    }
}
