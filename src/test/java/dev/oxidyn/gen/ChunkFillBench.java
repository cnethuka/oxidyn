package dev.oxidyn.gen;

import dev.oxidyn.ffi.Natives;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Time per chunk for the noise fill step, vanilla NoiseChunk loop vs native.
 * ./gradlew test -Pbench -Prun_jdk=22 --tests dev.oxidyn.gen.ChunkFillBench
 */
@Tag("bench")
class ChunkFillBench {
    @Test
    void run() throws Exception {
        assumeTrue(Runtime.version().feature() >= 22 && Natives.get() != null);
        HolderLookup.Provider reg = VanillaRegistries.createLookup();
        NoiseGeneratorSettings settings = reg.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        RandomState random = RandomState.create(settings, reg.lookupOrThrow(Registries.NOISE), 12345L);
        NoiseSettings ns = settings.noiseSettings();
        Method state = NoiseChunk.class.getDeclaredMethod("getInterpolatedState");
        state.setAccessible(true);

        int[] chunk = {0};
        Runnable vanilla = () -> {
            int c = chunk[0]++ % 64;
            vanillaFill(settings, random, ns, (c % 8) * 16, (c / 8) * 16, state);
        };
        Runnable nativeFill = () -> {
            int c = chunk[0]++ % 64;
            NativeChunkFill.compute(settings, ns, random, empty(), (c % 8) * 16, (c / 8) * 16, Mth.floorDiv(ns.height(), ns.getCellHeight()));
        };
        double v = time(vanilla);
        double n = time(nativeFill);
        System.out.printf("BENCH noise fill per chunk: vanilla %.2f ms, native %.2f ms, %.2fx%n", v, n, v / n);
    }

    private static Beardifier empty() {
        return new Beardifier(new ObjectArrayList<Beardifier.Rigid>().iterator(), new ObjectArrayList<JigsawJunction>().iterator());
    }

    private static void vanillaFill(NoiseGeneratorSettings settings, RandomState random, NoiseSettings ns, int minX, int minZ, Method state) {
        int cellW = ns.getCellWidth(), cellH = ns.getCellHeight();
        int minCellY = Mth.floorDiv(ns.minY(), cellH), cellCountY = Mth.floorDiv(ns.height(), cellH);
        Aquifer.FluidStatus lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
        Aquifer.FluidStatus fluid = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
        NoiseChunk nc = new NoiseChunk(16 / cellW, random, minX, minZ, ns, empty(), settings,
                (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : fluid, Blender.empty());
        nc.initializeForFirstCellX();
        try {
            for (int k1 = 0; k1 < 16 / cellW; k1++) {
                nc.advanceCellX(k1);
                for (int l1 = 0; l1 < 16 / cellW; l1++) {
                    for (int j2 = cellCountY - 1; j2 >= 0; j2--) {
                        nc.selectCellYZ(j2, l1);
                        for (int k2 = cellH - 1; k2 >= 0; k2--) {
                            nc.updateForY((minCellY + j2) * cellH + k2, (double) k2 / cellH);
                            for (int k3 = 0; k3 < cellW; k3++) {
                                nc.updateForX(minX + k1 * cellW + k3, (double) k3 / cellW);
                                for (int j4 = 0; j4 < cellW; j4++) {
                                    nc.updateForZ(minZ + l1 * cellW + j4, (double) j4 / cellW);
                                    state.invoke(nc);
                                }
                            }
                        }
                    }
                }
                nc.swapSlices();
            }
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
        nc.stopInterpolation();
    }

    private static double time(Runnable r) {
        long end = System.nanoTime() + 15_000_000_000L;
        while (System.nanoTime() < end) r.run();
        long t0 = System.nanoTime();
        int n = 0;
        while (System.nanoTime() - t0 < 10_000_000_000L) {
            r.run();
            n++;
        }
        return (System.nanoTime() - t0) / 1e6 / n;
    }
}
