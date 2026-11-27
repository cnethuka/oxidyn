package dev.oxidyn.gen;

import dev.oxidyn.Oxidyn;
import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.Natives;
import dev.oxidyn.mixin.gen.BeardifierAccessor;
import dev.oxidyn.mixin.gen.NoiseChunkAccessor;
import dev.oxidyn.mixin.gen.XoroshiroFactoryAccessor;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.TerrainAdjustment;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import it.unimi.dsi.fastutil.ints.IntArrayList;

/**
 * Replacement for NoiseBasedChunkGenerator.doFill. Rust decides what goes
 * where; this class only writes the result into the chunk, walking blocks in
 * exactly vanilla's order so heightmaps come out the same.
 */
public final class NativeChunkFill {
    // output codes, see native/src/gen/aquifer.rs and fill.rs
    private static final int SCHEDULE = 0x80;

    private NativeChunkFill() {
    }

    /** @return false if this chunk has to go through vanilla instead */
    public static boolean fill(NoiseGeneratorSettings settings, RandomState random, Aquifer.FluidPicker picker,
                               ChunkAccess chunk, NoiseChunk noiseChunk, int minCellY, int cellCountY) {
        if (SharedConstants.debugVoidTerrain(chunk.getPos())) return false;
        NoiseSettings ns = settings.noiseSettings().clampToHeightAccessor(chunk.getHeightAccessorForGeneration());
        if (!verified(settings, ns, random, picker, chunk)) return false;
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        byte[] out = compute(settings, ns, random, ((NoiseChunkAccessor) noiseChunk).oxidyn$beardifier(), minX, minZ, cellCountY);
        if (out == null) return false;
        write(settings, chunk, out, minCellY, cellCountY, ns.getCellWidth(), ns.getCellHeight(), minX, minZ);
        return true;
    }

    private static boolean verified(NoiseGeneratorSettings settings, NoiseSettings ns, RandomState random,
                                    Aquifer.FluidPicker picker, ChunkAccess chunk) {
        CompiledRouter router = ((NativeRouterHolder) (Object) random).oxidyn$compiledRouter();
        if (router == null) return false;
        if (router.verified == 0) {
            synchronized (router) {
                if (router.verified == 0) {
                    long t = System.nanoTime();
                    boolean ok = FillVerifier.matches(settings, ns, random, picker, chunk.getPos().x, chunk.getPos().z);
                    FillStats.verification(System.nanoTime() - t);
                    router.verified = ok ? 1 : -1;
                    if (ok) Oxidyn.LOG.info("Native chunk fill verified against vanilla for this dimension");
                }
            }
        }
        return router.verified == 1;
    }

    /**
     * The native part on its own: one output code per block, laid out
     * ((y - firstY) * 16 + z) * 16 + x. null if it can't be done natively.
     */
    static byte[] compute(NoiseGeneratorSettings settings, NoiseSettings ns, RandomState random,
                          DensityFunctions.BeardifierOrMarker beardifier, int minX, int minZ, int cellCountY) {
        NativeBackend backend = Natives.get();
        CompiledRouter router = ((NativeRouterHolder) (Object) random).oxidyn$compiledRouter();
        if (backend == null || router == null) return null;

        // the random factories only matter when aquifers / ore veins are on;
        // the nether has neither but uses the legacy RNG, so don't insist
        long[] seeds = new long[4];
        if (settings.isAquifersEnabled() && !seeds(random.aquiferRandom(), seeds, 0)) return null;
        if (settings.oreVeinsEnabled() && !seeds(random.oreRandom(), seeds, 2)) return null;

        IntArrayList pieces = new IntArrayList();
        IntArrayList junctions = new IntArrayList();
        if (!beardifier(beardifier, pieces, junctions)) return null;

        int cellW = ns.getCellWidth();
        int cellH = ns.getCellHeight();

        BlockState fluid = settings.defaultFluid();
        int fluidFlags = (fluid.is(Blocks.LAVA) ? 1 : 0) | (fluid.is(Blocks.WATER) ? 2 : 0)
                | (fluid.isAir() ? 4 : 0) | (fluid == Blocks.LAVA.defaultBlockState() ? 8 : 0);
        int flags = (settings.isAquifersEnabled() ? 1 : 0) | (settings.oreVeinsEnabled() ? 2 : 0);
        int[] ints = {minX, minZ, ns.minY(), ns.height(), cellW, cellH, settings.seaLevel(), flags, fluidFlags,
                pieces.size() / 8, junctions.size() / 3, 0};

        byte[] out = new byte[256 * cellCountY * cellH];
        int rc = backend.genFill(router.program, ints, seeds, pieces.toIntArray(), junctions.toIntArray(),
                BeardifierAccessor.oxidyn$kernel(), out);
        return rc == 0 ? out : null;
    }

    /** What each output code stands for, with the schedule bit masked off. */
    static BlockState[] states(NoiseGeneratorSettings settings) {
        return new BlockState[]{
                settings.defaultBlock(), Blocks.AIR.defaultBlockState(), settings.defaultFluid(), Blocks.LAVA.defaultBlockState(),
                Blocks.COPPER_ORE.defaultBlockState(), Blocks.RAW_COPPER_BLOCK.defaultBlockState(),
                Blocks.GRANITE.defaultBlockState(), Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(),
                Blocks.RAW_IRON_BLOCK.defaultBlockState(), Blocks.TUFF.defaultBlockState(),
        };
    }

    private static boolean seeds(PositionalRandomFactory factory, long[] out, int at) {
        // legacy-random worlds use a different RNG; leave those to vanilla
        if (!(factory instanceof XoroshiroRandomSource.XoroshiroPositionalRandomFactory)) return false;
        XoroshiroFactoryAccessor a = (XoroshiroFactoryAccessor) factory;
        out[at] = a.oxidyn$seedLo();
        out[at + 1] = a.oxidyn$seedHi();
        return true;
    }

    private static boolean beardifier(DensityFunctions.BeardifierOrMarker b, IntArrayList pieces, IntArrayList junctions) {
        // the marker (always 0) is a protected enum, so go by name
        if (b.getClass().getName().endsWith("DensityFunctions$BeardifierMarker")) return true;
        if (b.getClass() != Beardifier.class) return false; // someone replaced it, don't guess

        BeardifierAccessor a = (BeardifierAccessor) b;
        // same iterate-then-rewind dance Beardifier.compute does
        ObjectListIterator<Beardifier.Rigid> it = a.oxidyn$pieces();
        try {
            while (it.hasNext()) {
                Beardifier.Rigid r = it.next();
                BoundingBox box = r.box();
                pieces.add(box.minX());
                pieces.add(box.minY());
                pieces.add(box.minZ());
                pieces.add(box.maxX());
                pieces.add(box.maxY());
                pieces.add(box.maxZ());
                pieces.add(adjustment(r.terrainAdjustment()));
                pieces.add(r.groundLevelDelta());
            }
        } finally {
            it.back(Integer.MAX_VALUE);
        }
        ObjectListIterator<JigsawJunction> jt = a.oxidyn$junctions();
        try {
            while (jt.hasNext()) {
                JigsawJunction j = jt.next();
                junctions.add(j.getSourceX());
                junctions.add(j.getSourceGroundY());
                junctions.add(j.getSourceZ());
            }
        } finally {
            jt.back(Integer.MAX_VALUE);
        }
        return true;
    }

    private static int adjustment(TerrainAdjustment t) {
        return switch (t) {
            case NONE -> 0;
            case BURY -> 1;
            case BEARD_THIN -> 2;
            case BEARD_BOX -> 3;
            case ENCAPSULATE -> 4;
        };
    }

    /** The block-writing half of doFill, loop for loop. */
    private static void write(NoiseGeneratorSettings settings, ChunkAccess chunk, byte[] out,
                              int minCellY, int cellCountY, int cellW, int cellH, int minX, int minZ) {
        BlockState air = Blocks.AIR.defaultBlockState();
        BlockState[] states = states(settings);
        Heightmap oceanFloor = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.OCEAN_FLOOR_WG);
        Heightmap surface = chunk.getOrCreateHeightmapUnprimed(Heightmap.Types.WORLD_SURFACE_WG);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int firstY = minCellY * cellH;
        int cells = 16 / cellW;

        for (int cx = 0; cx < cells; cx++) {
            for (int cz = 0; cz < cells; cz++) {
                int sectionIndex = chunk.getSectionsCount() - 1;
                LevelChunkSection section = chunk.getSection(sectionIndex);
                for (int cy = cellCountY - 1; cy >= 0; cy--) {
                    for (int iy = cellH - 1; iy >= 0; iy--) {
                        int y = (minCellY + cy) * cellH + iy;
                        int ly = y & 15;
                        int si = chunk.getSectionIndex(y);
                        if (sectionIndex != si) {
                            sectionIndex = si;
                            section = chunk.getSection(si);
                        }
                        for (int ix = 0; ix < cellW; ix++) {
                            int x = minX + cx * cellW + ix;
                            int lx = x & 15;
                            for (int iz = 0; iz < cellW; iz++) {
                                int z = minZ + cz * cellW + iz;
                                int lz = z & 15;
                                int code = out[((y - firstY) * 16 + lz) * 16 + lx] & 0xFF;
                                BlockState state = states[code & 0x7F];
                                if (state != air) {
                                    section.setBlockState(lx, ly, lz, state, false);
                                    oceanFloor.update(lx, y, lz, state);
                                    surface.update(lx, y, lz, state);
                                    if ((code & SCHEDULE) != 0 && !state.getFluidState().isEmpty()) {
                                        pos.set(x, y, z);
                                        chunk.markPosForPostprocessing(pos);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
