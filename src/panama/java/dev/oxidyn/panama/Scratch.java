package dev.oxidyn.panama;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.nio.ByteBuffer;

/**
 * Gets ByteBuffers into a shape a normal (non-critical) downcall accepts.
 * Direct buffers are passed straight through. Heap ones are copied into a
 * per-thread off-heap buffer - a memcpy of a chunk is way cheaper than the
 * compression itself.
 */
final class Scratch {
    @FunctionalInterface
    interface Call {
        long run(MemorySegment in, MemorySegment out) throws Throwable;
    }

    private static final ThreadLocal<Scratch> LOCAL = ThreadLocal.withInitial(Scratch::new);

    // ofAuto so a dead thread's buffers get freed by the GC eventually
    private MemorySegment in = MemorySegment.NULL;
    private MemorySegment out = MemorySegment.NULL;

    private static MemorySegment grow(MemorySegment cur, long size) {
        if (cur.byteSize() >= size) return cur;
        long cap = Math.max(size, Math.min(cur.byteSize() * 2, size + (1 << 20)));
        return Arena.ofAuto().allocate(Math.max(cap, 4096), 16);
    }

    static int transfer(ByteBuffer src, ByteBuffer dst, Call call) {
        Scratch s = LOCAL.get();
        long srcLen = src.remaining();
        long dstLen = dst.remaining();

        MemorySegment inSeg;
        if (src.isDirect()) {
            inSeg = MemorySegment.ofBuffer(src);
        } else {
            s.in = grow(s.in, srcLen);
            inSeg = s.in.asSlice(0, srcLen);
            MemorySegment.copy(MemorySegment.ofBuffer(src), 0, inSeg, 0, srcLen);
        }

        MemorySegment outSeg;
        if (dst.isDirect()) {
            outSeg = MemorySegment.ofBuffer(dst);
        } else {
            s.out = grow(s.out, dstLen);
            outSeg = s.out.asSlice(0, dstLen);
        }

        long n;
        try {
            n = call.run(inSeg, outSeg);
        } catch (Throwable t) {
            throw PanamaBackend.rethrow(t);
        }

        if (n > 0 && !dst.isDirect()) {
            MemorySegment.copy(outSeg, 0, MemorySegment.ofBuffer(dst), 0, n);
        }
        return (int) n;
    }
}
