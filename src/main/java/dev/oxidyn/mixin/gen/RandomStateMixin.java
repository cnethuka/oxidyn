package dev.oxidyn.mixin.gen;

import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.Natives;
import dev.oxidyn.gen.CompiledRouter;
import dev.oxidyn.gen.NativeRouterHolder;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(RandomState.class)
public abstract class RandomStateMixin implements NativeRouterHolder {
    @Shadow @Final private NoiseRouter router;

    @Unique
    private volatile boolean oxidyn$compiled;
    @Unique
    private CompiledRouter oxidyn$router;

    @Override
    public CompiledRouter oxidyn$compiledRouter() {
        if (!oxidyn$compiled) {
            synchronized (this) {
                if (!oxidyn$compiled) {
                    NativeBackend backend = Natives.get();
                    oxidyn$router = backend == null ? null : CompiledRouter.compile(backend, router);
                    oxidyn$compiled = true;
                }
            }
        }
        return oxidyn$router;
    }
}
