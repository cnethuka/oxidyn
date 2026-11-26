package dev.oxidyn.gen;

import dev.oxidyn.Oxidyn;
import dev.oxidyn.mixin.gen.NoiseChunkInvoker;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;

/**
 * Before a dimension's first native fill, run one chunk through both
 * vanilla's NoiseChunk and the native code and compare every block. The unit
 * tests cover the vanilla presets; this covers whatever datapacks and mods
 * did to this particular world. One chunk, once per dimension per start.
 */
final class FillVerifier {
    private FillVerifier() {
    }

    static boolean matches(NoiseGeneratorSettings settings, NoiseSettings ns, RandomState random,
                           Aquifer.FluidPicker picker, int chunkX, int chunkZ) {
        int cellW = ns.getCellWidth();
        int cellH = ns.getCellHeight();
        int minCellY = Mth.floorDiv(ns.minY(), cellH);
        int cellCountY = Mth.floorDiv(ns.height(), cellH);
        int minX = chunkX * 16;
        int minZ = chunkZ * 16;

        byte[] got = NativeChunkFill.compute(settings, ns, random, emptyBeardifier(), minX, minZ, cellCountY);
        if (got == null) return false;
        BlockState[] states = NativeChunkFill.states(settings);

        // doFill's loop, against a throwaway NoiseChunk
        NoiseChunk nc = new NoiseChunk(16 / cellW, random, minX, minZ, ns, emptyBeardifier(), settings, picker, Blender.empty());
        Aquifer aquifer = nc.aquifer();
        int firstY = minCellY * cellH;
        nc.initializeForFirstCellX();
        try {
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
                                    BlockState want = ((NoiseChunkInvoker) nc).oxidyn$interpolatedState();
                                    if (want == null) want = settings.defaultBlock();
                                    boolean wantSchedule = aquifer.shouldScheduleFluidUpdate() && !want.getFluidState().isEmpty();

                                    int code = got[((y - firstY) * 16 + (z & 15)) * 16 + (x & 15)] & 0xFF;
                                    BlockState have = states[code & 0x7F];
                                    boolean haveSchedule = (code & 0x80) != 0 && !have.getFluidState().isEmpty();
                                    if (have != want || haveSchedule != wantSchedule) {
                                        Oxidyn.LOG.error("Native chunk fill differs from vanilla at {} {} {} (vanilla {}, native {}). "
                                                + "Turning it off for this dimension, please report this along with your mods/datapacks.",
                                                x, y, z, want, have);
                                        return false;
                                    }
                                }
                            }
                        }
                    }
                }
                nc.swapSlices();
            }
        } finally {
            nc.stopInterpolation();
        }
        return true;
    }

    private static Beardifier emptyBeardifier() {
        return new Beardifier(new ObjectArrayList<Beardifier.Rigid>().iterator(), new ObjectArrayList<JigsawJunction>().iterator());
    }
}
