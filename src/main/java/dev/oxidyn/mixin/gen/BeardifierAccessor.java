package dev.oxidyn.mixin.gen;

import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Beardifier.class)
public interface BeardifierAccessor {
    @Accessor("pieceIterator")
    ObjectListIterator<Beardifier.Rigid> oxidyn$pieces();

    @Accessor("junctionIterator")
    ObjectListIterator<JigsawJunction> oxidyn$junctions();

    @Accessor("BEARD_KERNEL")
    static float[] oxidyn$kernel() {
        throw new AssertionError();
    }
}
