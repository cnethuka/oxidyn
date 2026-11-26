package dev.oxidyn.panama;

import dev.oxidyn.ffi.OctaveSpec;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;

import static java.lang.foreign.ValueLayout.JAVA_DOUBLE;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * Writes noise descriptions in the layout native/src/noise.rs expects.
 * Offsets are spelled out by hand - the structs are small and stable, and a
 * StructLayout for a 256 byte array plus padding isn't any easier to read.
 */
final class NoiseLayouts {
    // struct Octave { f64 xo, yo, zo, amplitude, input_factor, value_factor; u8 perm[256]; }
    static final long OCTAVE_SIZE = 6 * 8 + 256;
    // struct NormalHeader { f64 value_factor; u32 first_count; u32 second_count; }
    static final long NORMAL_HEADER = 16;
    // struct BlendedHeader { f64 xz_mul, y_mul, xz_factor, y_factor, smear; u64 present; }
    static final long BLENDED_HEADER = 48;
    static final int BLENDED_SLOTS = 8 + 16 + 16;

    private NoiseLayouts() {
    }

    static MemorySegment normal(double valueFactor, OctaveSpec[] first, OctaveSpec[] second) {
        // Arena.ofAuto: freed once the NormalNoise (which holds the segment) is collected
        MemorySegment seg = Arena.ofAuto().allocate(NORMAL_HEADER + OCTAVE_SIZE * (first.length + second.length), 8);
        seg.set(JAVA_DOUBLE, 0, valueFactor);
        seg.set(JAVA_INT, 8, first.length);
        seg.set(JAVA_INT, 12, second.length);
        long at = NORMAL_HEADER;
        for (OctaveSpec o : first) {
            writeOctave(seg, at, o);
            at += OCTAVE_SIZE;
        }
        for (OctaveSpec o : second) {
            writeOctave(seg, at, o);
            at += OCTAVE_SIZE;
        }
        return seg;
    }

    static MemorySegment blended(double xzMul, double yMul, double xzFactor, double yFactor, double smear, OctaveSpec[] slots) {
        if (slots.length != BLENDED_SLOTS) {
            throw new IllegalArgumentException("blended noise needs " + BLENDED_SLOTS + " slots, got " + slots.length);
        }
        MemorySegment seg = Arena.ofAuto().allocate(BLENDED_HEADER + OCTAVE_SIZE * BLENDED_SLOTS, 8);
        seg.set(JAVA_DOUBLE, 0, xzMul);
        seg.set(JAVA_DOUBLE, 8, yMul);
        seg.set(JAVA_DOUBLE, 16, xzFactor);
        seg.set(JAVA_DOUBLE, 24, yFactor);
        seg.set(JAVA_DOUBLE, 32, smear);
        long present = 0;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] != null) {
                present |= 1L << i;
                writeOctave(seg, BLENDED_HEADER + OCTAVE_SIZE * i, slots[i]);
            }
        }
        seg.set(JAVA_LONG, 40, present);
        return seg;
    }

    private static void writeOctave(MemorySegment seg, long at, OctaveSpec o) {
        seg.set(JAVA_DOUBLE, at, o.xo());
        seg.set(JAVA_DOUBLE, at + 8, o.yo());
        seg.set(JAVA_DOUBLE, at + 16, o.zo());
        seg.set(JAVA_DOUBLE, at + 24, o.amplitude());
        seg.set(JAVA_DOUBLE, at + 32, o.inputFactor());
        seg.set(JAVA_DOUBLE, at + 40, o.valueFactor());
        MemorySegment.copy(MemorySegment.ofArray(o.permutation()), 0, seg, at + 48, 256);
    }
}
