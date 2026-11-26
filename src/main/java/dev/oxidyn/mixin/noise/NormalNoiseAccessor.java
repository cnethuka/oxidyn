package dev.oxidyn.mixin.noise;

import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(NormalNoise.class)
public interface NormalNoiseAccessor {
    @Accessor("first")
    PerlinNoise oxidyn$first();

    @Accessor("second")
    PerlinNoise oxidyn$second();

    @Accessor("valueFactor")
    double oxidyn$valueFactor();
}
