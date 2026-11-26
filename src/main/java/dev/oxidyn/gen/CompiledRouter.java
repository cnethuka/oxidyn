package dev.oxidyn.gen;

import dev.oxidyn.Oxidyn;
import dev.oxidyn.ffi.NativeBackend;
import net.minecraft.world.level.levelgen.NoiseRouter;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.Cleaner;
import java.util.List;

/**
 * A dimension's noise router, compiled into a native program. One per
 * RandomState; freed when the RandomState goes away.
 */
public final class CompiledRouter {
    private static final Cleaner CLEANER = Cleaner.create();

    final long program;
    // the native program points into these noise descriptors
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final List<Object> keepAlive;

    private CompiledRouter(long program, List<Object> keepAlive) {
        this.program = program;
        this.keepAlive = keepAlive;
    }

    /** null when this router can't be filled natively (logged once). */
    @Nullable
    public static CompiledRouter compile(NativeBackend backend, NoiseRouter router) {
        DensityCompiler compiler = new DensityCompiler(backend);
        long[] words;
        try {
            words = compiler.compile(router);
        } catch (DensityCompiler.Unsupported e) {
            Oxidyn.LOG.info("Native chunk fill not available for this dimension: {}", e.getMessage());
            return null;
        }
        int[] error = new int[1];
        long program = backend.genCreate(words, error);
        if (program == 0) {
            Oxidyn.LOG.info("Native chunk fill not available for this dimension: {}", reason(error[0]));
            return null;
        }
        CompiledRouter compiled = new CompiledRouter(program, compiler.keepAlive);
        CLEANER.register(compiled, () -> backend.genFree(program));
        return compiled;
    }

    private static String reason(int code) {
        return switch (code) {
            case 4 -> "nested interpolation";
            case 5 -> "cache_2d over a y-dependent function";
            case 6 -> "cache_once in the per-cell part of the final density";
            default -> "native side rejected the router (code " + code + ")";
        };
    }
}
