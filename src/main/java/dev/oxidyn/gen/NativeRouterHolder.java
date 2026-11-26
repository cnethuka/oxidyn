package dev.oxidyn.gen;

import org.jetbrains.annotations.Nullable;

/** Mixed into RandomState. */
public interface NativeRouterHolder {
    @Nullable
    CompiledRouter oxidyn$compiledRouter();
}
