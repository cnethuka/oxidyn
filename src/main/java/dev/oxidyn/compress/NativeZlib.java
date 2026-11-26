package dev.oxidyn.compress;

import dev.oxidyn.Oxidyn;
import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.Natives;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;

/**
 * Whole-buffer zlib streams for region files.
 *
 * <p>Both directions fall back to java.util.zip if the native call fails for
 * whatever reason. Writing in particular must never produce a broken stream,
 * because RegionFile commits whatever we hand it as the chunk.
 */
public final class NativeZlib {
    // decompressed chunks above this go through the plain streaming path
    // rather than us allocating a giant array for them
    private static final int MAX_INFLATE = 256 << 20;

    private NativeZlib() {
    }

    public static OutputStream deflating(OutputStream sink, int level) {
        return new DeflatingStream(sink, level);
    }

    public static InputStream inflating(InputStream source) throws IOException {
        byte[] packed;
        try (source) {
            packed = source.readAllBytes();
        }
        byte[] plain = inflate(packed);
        if (plain != null) {
            return new ByteArrayInputStream(plain);
        }
        // let java.util.zip have a go; if the data really is broken it'll
        // throw the same ZipException vanilla would
        return new InflaterInputStream(new ByteArrayInputStream(packed));
    }

    /** null if the native side couldn't do it */
    static byte[] inflate(byte[] packed) {
        NativeBackend backend = Natives.get();
        if (backend == null) return null;

        // chunks usually come out 4-8x bigger, start optimistic and grow
        long size = Math.max(64 * 1024, (long) packed.length * 8);
        while (size <= MAX_INFLATE) {
            byte[] out = new byte[(int) size];
            int n = backend.zlibDecompress(ByteBuffer.wrap(packed), ByteBuffer.wrap(out));
            if (n >= 0) {
                return n == out.length ? out : Arrays.copyOf(out, n);
            }
            if (n != NativeBackend.ERR_NO_SPACE) {
                return null;
            }
            size *= 2;
        }
        return null;
    }

    private static final class DeflatingStream extends ByteArrayOutputStream {
        private final OutputStream sink;
        private final int level;
        private boolean closed;

        DeflatingStream(OutputStream sink, int level) {
            super(8192);
            this.sink = sink;
            this.level = level;
        }

        @Override
        public void close() throws IOException {
            if (closed) return;
            closed = true;
            try (sink) {
                byte[] packed = compress(buf, count, level);
                sink.write(packed);
            }
        }
    }

    static byte[] compress(byte[] data, int len, int level) {
        NativeBackend backend = Natives.get();
        if (backend != null) {
            byte[] out = new byte[(int) backend.zlibBound(len)];
            int n = backend.zlibCompress(level, ByteBuffer.wrap(data, 0, len), ByteBuffer.wrap(out));
            if (n > 0) {
                return Arrays.copyOf(out, n);
            }
            Oxidyn.LOG.warn("Native zlib compression failed ({}), using java.util.zip for this one", n);
        }

        Deflater d = new Deflater(Math.min(level, 9));
        try {
            d.setInput(data, 0, len);
            d.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, len / 4));
            byte[] chunk = new byte[16384];
            while (!d.finished()) {
                out.write(chunk, 0, d.deflate(chunk));
            }
            return out.toByteArray();
        } finally {
            d.end();
        }
    }
}
