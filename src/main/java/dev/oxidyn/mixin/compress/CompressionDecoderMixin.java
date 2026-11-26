package dev.oxidyn.mixin.compress;

import dev.oxidyn.Features;
import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.Natives;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.VarInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The decoder knows the uncompressed size up front (it's in the packet),
 * which is exactly what libdeflate's one-shot API wants.
 *
 * <p>If anything looks off - sizes outside the limits, a bad stream, a length
 * mismatch - we rewind and let vanilla decode the packet, so the error
 * messages and edge cases stay exactly as they were.
 */
@Mixin(CompressionDecoder.class)
public abstract class CompressionDecoderMixin {
    @Shadow private int threshold;
    @Shadow private boolean validateDecompressed;

    @Inject(method = "decode", at = @At("HEAD"), cancellable = true)
    private void oxidyn$decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out, CallbackInfo ci) {
        if (!Features.networkZlib || in.readableBytes() == 0) return;
        NativeBackend backend = Natives.get();
        if (backend == null) return;

        int mark = in.readerIndex();
        int size = VarInt.read(in);
        if (size == 0
                || (validateDecompressed && (size < threshold || size > CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH))
                || size > CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH
                || in.nioBufferCount() != 1) {
            in.readerIndex(mark);
            return;
        }

        ByteBuf result = ctx.alloc().directBuffer(size);
        boolean done = false;
        try {
            int n = backend.zlibDecompress(in.nioBuffer(in.readerIndex(), in.readableBytes()), result.nioBuffer(0, size));
            if (n == size) {
                result.writerIndex(n);
                in.skipBytes(in.readableBytes());
                out.add(result);
                done = true;
                ci.cancel();
            }
        } finally {
            if (!done) {
                result.release();
                in.readerIndex(mark);
            }
        }
    }
}
