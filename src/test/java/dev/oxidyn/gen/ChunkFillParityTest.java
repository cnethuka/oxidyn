package dev.oxidyn.gen;

import dev.oxidyn.ffi.Natives;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.pools.StructureTemplatePool;
import net.minecraft.util.Mth;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The native fill against vanilla's own NoiseChunk, block by block.
 * The vanilla side below is doFill's loop, minus writing into a chunk.
 */
class ChunkFillParityTest {
    private static HolderLookup.Provider registries;
    private static Method interpolatedState;

    @BeforeAll
    static void setUp() throws Exception {
        assumeTrue(Runtime.version().feature() >= 22, "native path needs Java 22+");
        assertNotNull(Natives.get(), Natives.status());
        registries = VanillaRegistries.createLookup();
        interpolatedState = NoiseChunk.class.getDeclaredMethod("getInterpolatedState");
        interpolatedState.setAccessible(true);
    }

    @Test
    void overworld() throws Exception {
        check(NoiseGeneratorSettings.OVERWORLD, 12345L, new int[][]{{0, 0}, {-3, 7}, {40, -12}, {-1000, 2500}, {100000, -50000}, {-1800000, 1799000}});
    }

    @Test
    void largeBiomesAndAmplified() throws Exception {
        check(NoiseGeneratorSettings.LARGE_BIOMES, -42L, new int[][]{{0, 0}, {17, -230}});
        check(NoiseGeneratorSettings.AMPLIFIED, 987654321L, new int[][]{{0, 0}, {-55, 3}});
    }

    @Test
    void netherCavesFloatingIslands() throws Exception {
        check(NoiseGeneratorSettings.NETHER, 7L, new int[][]{{0, 0}, {12, -40}});
        check(NoiseGeneratorSettings.CAVES, 8L, new int[][]{{0, 0}, {-9, 9}});
        check(NoiseGeneratorSettings.FLOATING_ISLANDS, 9L, new int[][]{{0, 0}, {30, 30}});
    }

    @Test
    void endStaysOnVanilla() {
        RandomState random = random(NoiseGeneratorSettings.END, 1L);
        assertNull(((NativeRouterHolder) (Object) random).oxidyn$compiledRouter());
    }

    @Test
    void structuresGetBearded() throws Exception {
        // one of each adjustment plus a couple of junctions, overlapping chunk (2, 3)
        List<Beardifier.Rigid> pieces = List.of(
                new Beardifier.Rigid(new BoundingBox(30, 60, 50, 45, 75, 60), TerrainAdjustment.BEARD_THIN, 0),
                new Beardifier.Rigid(new BoundingBox(20, 40, 40, 36, 52, 58), TerrainAdjustment.BURY, 2),
                new Beardifier.Rigid(new BoundingBox(38, 10, 44, 50, 30, 62), TerrainAdjustment.ENCAPSULATE, 0),
                new Beardifier.Rigid(new BoundingBox(28, 64, 36, 34, 70, 44), TerrainAdjustment.BEARD_BOX, -1));
        List<JigsawJunction> junctions = List.of(
                new JigsawJunction(40, 66, 52, 0, StructureTemplatePool.Projection.RIGID),
                new JigsawJunction(33, 70, 47, 0, StructureTemplatePool.Projection.TERRAIN_MATCHING));
        RandomState random = random(NoiseGeneratorSettings.OVERWORLD, 555L);
        NoiseGeneratorSettings settings = settings(NoiseGeneratorSettings.OVERWORLD);
        compareChunk(settings, random, 2, 3,
                () -> new Beardifier(new ObjectArrayList<>(pieces).iterator(), new ObjectArrayList<>(junctions).iterator()));
    }

    private static RandomState random(ResourceKey<NoiseGeneratorSettings> key, long seed) {
        return RandomState.create(settings(key), registries.lookupOrThrow(Registries.NOISE), seed);
    }

    private static NoiseGeneratorSettings settings(ResourceKey<NoiseGeneratorSettings> key) {
        return registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key).value();
    }

    private static void check(ResourceKey<NoiseGeneratorSettings> key, long seed, int[][] chunks) throws Exception {
        NoiseGeneratorSettings settings = settings(key);
        RandomState random = random(key, seed);
        assertNotNull(((NativeRouterHolder) (Object) random).oxidyn$compiledRouter(), key + " should compile");
        for (int[] c : chunks) {
            compareChunk(settings, random, c[0], c[1], () -> emptyBeardifier());
        }
    }

    private static DensityFunctions.BeardifierOrMarker emptyBeardifier() {
        return new Beardifier(new ObjectArrayList<Beardifier.Rigid>().iterator(), new ObjectArrayList<JigsawJunction>().iterator());
    }

    private static void compareChunk(NoiseGeneratorSettings settings, RandomState random, int chunkX, int chunkZ,
                                     java.util.function.Supplier<DensityFunctions.BeardifierOrMarker> beard) throws Exception {
        NoiseSettings ns = settings.noiseSettings();
        int cellW = ns.getCellWidth();
        int cellH = ns.getCellHeight();
        int minCellY = Mth.floorDiv(ns.minY(), cellH);
        int cellCountY = Mth.floorDiv(ns.height(), cellH);
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;

        byte[] got = NativeChunkFill.compute(settings, ns, random, beard.get(), minX, minZ, cellCountY);
        assertNotNull(got, "native fill refused chunk " + chunkX + ", " + chunkZ);
        BlockState[] states = NativeChunkFill.states(settings);

        // --- vanilla doFill ---
        NoiseChunk nc = new NoiseChunk(16 / cellW, random, minX, minZ, ns, beard.get(), settings, fluidPicker(settings), Blender.empty());
        Aquifer aquifer = nc.aquifer();
        nc.initializeForFirstCellX();
        int firstY = minCellY * cellH;
        int mismatches = 0;
        String first = null;
        for (int k1 = 0; k1 < 16 / cellW; k1++) {
            nc.advanceCellX(k1);
            for (int l1 = 0; l1 < 16 / cellW; l1++) {
                for (int j2 = cellCountY - 1; j2 >= 0; j2--) {
                    nc.selectCellYZ(j2, l1);
                    for (int k2 = cellH - 1; k2 >= 0; k2--) {
                        int y = (minCellY + j2) * cellH + k2;
                        nc.updateForY(y, (double) k2 / (double) cellH);
                        for (int k3 = 0; k3 < cellW; k3++) {
                            int x = minX + k1 * cellW + k3;
                            nc.updateForX(x, (double) k3 / (double) cellW);
                            for (int j4 = 0; j4 < cellW; j4++) {
                                int z = minZ + l1 * cellW + j4;
                                nc.updateForZ(z, (double) j4 / (double) cellW);
                                BlockState want = (BlockState) interpolatedState.invoke(nc);
                                if (want == null) want = settings.defaultBlock();
                                boolean wantSchedule = aquifer.shouldScheduleFluidUpdate() && !want.getFluidState().isEmpty();

                                int code = got[((y - firstY) * 16 + (z & 15)) * 16 + (x & 15)] & 0xFF;
                                BlockState have = states[code & 0x7F];
                                boolean haveSchedule = (code & 0x80) != 0 && !have.getFluidState().isEmpty();
                                if (have != want || haveSchedule != wantSchedule) {
                                    if (first == null) {
                                        first = "at " + x + " " + y + " " + z + ": vanilla " + want + (wantSchedule ? " (scheduled)" : "")
                                                + ", native " + have + (haveSchedule ? " (scheduled)" : "");
                                    }
                                    mismatches++;
                                }
                            }
                        }
                    }
                }
            }
            nc.swapSlices();
        }
        nc.stopInterpolation();
        if (mismatches > 0) {
            fail(mismatches + " blocks differ in chunk " + chunkX + ", " + chunkZ + ", first " + first);
        }
        assertEquals(0, mismatches);
    }

    /** NoiseBasedChunkGenerator.createFluidPicker */
    private static Aquifer.FluidPicker fluidPicker(NoiseGeneratorSettings settings) {
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        int sea = settings.seaLevel();
        Aquifer.FluidStatus fluid = new Aquifer.FluidStatus(sea, settings.defaultFluid());
        return (x, y, z) -> y < Math.min(-54, sea) ? lava : fluid;
    }
}
