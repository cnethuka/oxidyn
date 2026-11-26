package dev.oxidyn.mixin.gen;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(NoiseChunk.class)
public interface NoiseChunkInvoker {
    @Invoker("getInterpolatedState")
    BlockState oxidyn$interpolatedState();
}
