package dev.oxidyn;

import dev.oxidyn.ffi.Natives;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.CompressionDecoder;
import net.minecraft.network.CompressionEncoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.SplittableRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class NetworkCompressionTest {
    private static final int THRESHOLD = 256;

    @BeforeAll
    static void load() {
        assumeTrue(Runtime.version().feature() >= 22, "native path needs Java 22+");
        assertNotNull(Natives.get(), Natives.status());
    }

    @AfterEach
    void reset() {
        Features.networkZlib = false;
    }

    @Test
    void nativeToNative() {
        roundTrip(true, true);
    }

    @Test
    void nativeEncoderVanillaDecoder() {
        roundTrip(true, false);
    }

    @Test
    void vanillaEncoderNativeDecoder() {
        roundTrip(false, true);
    }

    @Test
    void encoderReallyTakesTheNativePath() {
        // libdeflate and zlib produce different (both valid) streams, so if
        // the bytes match we're secretly still running vanilla
        byte[] payload = payload(new SplittableRandom(9), 50_000);
        Features.networkZlib = true;
        ByteBuf a = encode(payload);
        Features.networkZlib = false;
        ByteBuf b = encode(payload);
        assertNotEquals(a, b);
        a.release();
        b.release();
    }

    private static ByteBuf encode(byte[] payload) {
        EmbeddedChannel enc = new EmbeddedChannel(new CompressionEncoder(THRESHOLD));
        enc.writeOutbound(Unpooled.wrappedBuffer(payload));
        ByteBuf wire = enc.readOutbound();
        enc.finishAndReleaseAll();
        return wire;
    }

    private static void roundTrip(boolean nativeEncode, boolean nativeDecode) {
        SplittableRandom rng = new SplittableRandom(5);
        for (int size : new int[]{0, 10, THRESHOLD - 1, THRESHOLD, 5000, 200_000, 2_000_000}) {
            for (boolean direct : new boolean[]{true, false}) {
                byte[] payload = payload(rng, size);

                Features.networkZlib = nativeEncode;
                EmbeddedChannel enc = new EmbeddedChannel(new CompressionEncoder(THRESHOLD));
                ByteBuf msg = direct ? Unpooled.directBuffer(size) : Unpooled.buffer(size);
                enc.writeOutbound(msg.writeBytes(payload));
                ByteBuf wire = enc.readOutbound();

                Features.networkZlib = nativeDecode;
                EmbeddedChannel dec = new EmbeddedChannel(new CompressionDecoder(THRESHOLD, true));
                dec.writeInbound(wire);
                ByteBuf back = dec.readInbound();

                assertEquals(Unpooled.wrappedBuffer(payload), back, "size " + size + " direct " + direct);
                back.release();
                enc.finishAndReleaseAll();
                dec.finishAndReleaseAll();
            }
        }
    }

    private static byte[] payload(SplittableRandom rng, int size) {
        byte[] b = new byte[size];
        for (int i = 0; i < size; i++) {
            b[i] = (byte) (rng.nextInt(10) == 0 ? rng.nextInt() : i / 64);
        }
        return b;
    }
}
