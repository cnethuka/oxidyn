package dev.oxidyn.mixin.gen;

import dev.oxidyn.Features;
import dev.oxidyn.gen.NativeChunkFill;
import net.minecraft.core.Holder;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Takes over the noise fill step (terrain shape, aquifers, ore veins) for
 * chunks we can do natively. Chunks next to pre-1.18 terrain need blending,
 * which stays on the vanilla path.
 */
@Mixin(NoiseBasedChunkGenerator.class)
public abstract class NoiseBasedChunkGeneratorMixin {
    @Shadow @Final private Holder<NoiseGeneratorSettings> settings;

    @Shadow
    protected abstract NoiseChunk createNoiseChunk(ChunkAccess chunk, StructureManager structures, Blender blender, RandomState random);

    @Inject(method = "doFill", at = @At("HEAD"), cancellable = true)
    private void oxidyn$nativeFill(Blender blender, StructureManager structures, RandomState random, ChunkAccess chunk,
                                   int minCellY, int cellCountY, CallbackInfoReturnable<ChunkAccess> cir) {
        if (!Features.chunkFill || blender != Blender.empty()) return;
        // same NoiseChunk vanilla would use; we only need its beardifier
        NoiseChunk noiseChunk = chunk.getOrCreateNoiseChunk(c -> createNoiseChunk(c, structures, blender, random));
        if (NativeChunkFill.fill(settings.value(), random, chunk, noiseChunk, minCellY, cellCountY)) {
            cir.setReturnValue(chunk);
        }
    }
}
