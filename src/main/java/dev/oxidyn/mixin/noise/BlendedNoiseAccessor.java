package dev.oxidyn.mixin.noise;

import net.minecraft.world.level.levelgen.synth.BlendedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(BlendedNoise.class)
public interface BlendedNoiseAccessor {
    @Accessor("minLimitNoise")
    PerlinNoise oxidyn$minLimitNoise();

    @Accessor("maxLimitNoise")
    PerlinNoise oxidyn$maxLimitNoise();

    @Accessor("mainNoise")
    PerlinNoise oxidyn$mainNoise();

    @Accessor("xzMultiplier")
    double oxidyn$xzMultiplier();

    @Accessor("yMultiplier")
    double oxidyn$yMultiplier();

    @Accessor("xzFactor")
    double oxidyn$xzFactor();

    @Accessor("yFactor")
    double oxidyn$yFactor();

    @Accessor("smearScaleMultiplier")
    double oxidyn$smearScaleMultiplier();
}
