package dev.oxidyn.mixin.gen;

import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(XoroshiroRandomSource.XoroshiroPositionalRandomFactory.class)
public interface XoroshiroFactoryAccessor {
    @Accessor("seedLo")
    long oxidyn$seedLo();

    @Accessor("seedHi")
    long oxidyn$seedHi();
}
