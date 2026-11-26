package dev.oxidyn.mixin.compress;

import dev.oxidyn.Features;
import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.Natives;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import net.minecraft.network.VarInt;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Packet compression. Vanilla copies the packet into a fresh byte[], then
 * feeds Deflater in 8 KB steps, copying each step into the output. We
 * compress from the input ByteBuf's memory straight into the output's.
 *
 * <p>Anything unusual (below threshold, oversized, composite buffers) goes
 * through vanilla untouched, including its error handling.
 */
@Mixin(CompressionEncoder.class)
public abstract class CompressionEncoderMixin {
    @Shadow private int threshold;

    @Inject(method = "encode(Lio/netty/channel/ChannelHandlerContext;Lio/netty/buffer/ByteBuf;Lio/netty/buffer/ByteBuf;)V",
            at = @At("HEAD"), cancellable = true)
    private void oxidyn$encode(ChannelHandlerContext ctx, ByteBuf in, ByteBuf out, CallbackInfo ci) {
        if (!Features.networkZlib) return;
        int len = in.readableBytes();
        if (len < threshold || len > CompressionDecoder.MAXIMUM_UNCOMPRESSED_LENGTH || in.nioBufferCount() != 1) return;
        NativeBackend backend = Natives.get();
        if (backend == null) return;

        int start = out.writerIndex();
        int bound = (int) backend.zlibBound(len);
        VarInt.write(out, len);
        out.ensureWritable(bound);
        if (out.nioBufferCount() != 1) {
            out.writerIndex(start);
            return;
        }

        int n = backend.zlibCompress(Features.networkZlibLevel,
                in.nioBuffer(in.readerIndex(), len), out.nioBuffer(out.writerIndex(), bound));
        if (n <= 0) {
            // shouldn't happen with a bound-sized buffer, but vanilla can have it
            out.writerIndex(start);
            return;
        }
        out.writerIndex(out.writerIndex() + n);
        in.skipBytes(len);
        ci.cancel();
    }
}
