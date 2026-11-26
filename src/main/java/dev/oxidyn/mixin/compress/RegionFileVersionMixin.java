package dev.oxidyn.mixin.compress;

import dev.oxidyn.Features;
import dev.oxidyn.compress.NativeZlib;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Only the deflate version (id 2, the default) is touched. GZip, LZ4 and
 * "none" are left to vanilla. The bytes on disk stay standard zlib, so worlds
 * open fine without the mod.
 */
@Mixin(RegionFileVersion.class)
public abstract class RegionFileVersionMixin {
    @Inject(method = "wrap(Ljava/io/OutputStream;)Ljava/io/OutputStream;", at = @At("HEAD"), cancellable = true)
    private void oxidyn$deflate(OutputStream out, CallbackInfoReturnable<OutputStream> cir) {
        if (Features.regionZlib && (Object) this == RegionFileVersion.VERSION_DEFLATE) {
            cir.setReturnValue(NativeZlib.deflating(out, Features.regionZlibLevel));
        }
    }

    @Inject(method = "wrap(Ljava/io/InputStream;)Ljava/io/InputStream;", at = @At("HEAD"), cancellable = true)
    private void oxidyn$inflate(InputStream in, CallbackInfoReturnable<InputStream> cir) throws IOException {
        if (Features.regionZlib && (Object) this == RegionFileVersion.VERSION_DEFLATE) {
            cir.setReturnValue(NativeZlib.inflating(in));
        }
    }
}
