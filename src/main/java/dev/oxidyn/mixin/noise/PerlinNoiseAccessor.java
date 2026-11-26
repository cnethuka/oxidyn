package dev.oxidyn.mixin.noise;

import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(PerlinNoise.class)
public interface PerlinNoiseAccessor {
    @Accessor("noiseLevels")
    ImprovedNoise[] oxidyn$noiseLevels();

    @Accessor("amplitudes")
    DoubleList oxidyn$amplitudes();

    @Accessor("lowestFreqInputFactor")
    double oxidyn$lowestFreqInputFactor();

    @Accessor("lowestFreqValueFactor")
    double oxidyn$lowestFreqValueFactor();
}
