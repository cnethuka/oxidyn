package dev.oxidyn;

import dev.oxidyn.ffi.Natives;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.SplittableRandom;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RegionCompressionTest {
    @BeforeAll
    static void load() {
        assumeTrue(Runtime.version().feature() >= 22, "native path needs Java 22+");
        assertNotNull(Natives.get(), Natives.status());
        Features.regionZlib = true;
    }

    @Test
    void mixinIsInThePath() throws IOException {
        OutputStream out = RegionFileVersion.VERSION_DEFLATE.wrap((OutputStream) new ByteArrayOutputStream());
        assertTrue(out.getClass().getName().startsWith("dev.oxidyn.compress."), out.getClass().getName());
    }

    @Test
    void nativeWrittenChunksReadWithPlainZlib() throws IOException {
        CompoundTag chunk = fakeChunk(1);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(RegionFileVersion.VERSION_DEFLATE.wrap((OutputStream) file))) {
            NbtIo.write(chunk, out);
        }
        // this is what a vanilla install would do with the file
        try (DataInputStream in = new DataInputStream(new InflaterInputStream(new ByteArrayInputStream(file.toByteArray())))) {
            assertEquals(chunk, NbtIo.read(in, NbtAccounter.unlimitedHeap()));
        }
    }

    @Test
    void vanillaWrittenChunksReadNatively() throws IOException {
        CompoundTag chunk = fakeChunk(2);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new DeflaterOutputStream(file))) {
            NbtIo.write(chunk, out);
        }
        try (DataInputStream in = new DataInputStream(RegionFileVersion.VERSION_DEFLATE.wrap((InputStream) new ByteArrayInputStream(file.toByteArray())))) {
            assertEquals(chunk, NbtIo.read(in, NbtAccounter.unlimitedHeap()));
        }
    }

    @Test
    void highlyCompressibleDataNeedsGrowing() throws IOException {
        // ~1000:1, forces a few retries of the output buffer
        byte[] zeros = new byte[20 << 20];
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (OutputStream out = RegionFileVersion.VERSION_DEFLATE.wrap((OutputStream) file)) {
            out.write(zeros);
        }
        try (InputStream in = RegionFileVersion.VERSION_DEFLATE.wrap((InputStream) new ByteArrayInputStream(file.toByteArray()))) {
            assertArrayEquals(zeros, in.readAllBytes());
        }
    }

    @Test
    void garbageStillFailsLikeVanilla() {
        byte[] junk = {0x78, (byte) 0x9c, 1, 2, 3, 4, 5, 6, 7};
        assertThrows(IOException.class, () -> {
            try (InputStream in = RegionFileVersion.VERSION_DEFLATE.wrap((InputStream) new ByteArrayInputStream(junk))) {
                in.readAllBytes();
            }
        });
    }

    private static CompoundTag fakeChunk(long seed) {
        SplittableRandom rng = new SplittableRandom(seed);
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", 3955);
        root.putString("Status", "minecraft:full");
        ListTag sections = new ListTag();
        for (int s = 0; s < 24; s++) {
            CompoundTag section = new CompoundTag();
            section.putByte("Y", (byte) (s - 4));
            long[] states = new long[256];
            long v = rng.nextLong();
            for (int i = 0; i < states.length; i++) {
                if (rng.nextInt(6) == 0) v = rng.nextLong();
                states[i] = v;
            }
            section.put("block_states", new LongArrayTag(states));
            sections.add(section);
        }
        root.put("sections", sections);
        return root;
    }
}
