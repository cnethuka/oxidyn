package dev.oxidyn.ffi;

import dev.oxidyn.Oxidyn;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;

/**
 * Loads the native backend on first use. If anything goes wrong we log it
 * once and the mod quietly behaves like vanilla.
 *
 * <p>System properties, mostly for debugging:
 * <ul>
 *   <li>{@code -Doxidyn.disable=true} - never load the library</li>
 *   <li>{@code -Doxidyn.library=/path/to/liboxidyn.so} - use this file instead of the bundled one</li>
 * </ul>
 */
public final class Natives {
    /** Has to match ABI_VERSION in native/src/lib.rs. */
    public static final int ABI_VERSION = 2;

    private static final String IMPL = "dev.oxidyn.panama.PanamaBackend";

    private static volatile boolean attempted;
    private static NativeBackend backend;
    private static String status = "not loaded yet";

    private Natives() {
    }

    @Nullable
    public static NativeBackend get() {
        if (!attempted) {
            synchronized (Natives.class) {
                if (!attempted) {
                    backend = load();
                    attempted = true;
                }
            }
        }
        return backend;
    }

    public static String status() {
        get();
        return status;
    }

    private static NativeBackend load() {
        if (Boolean.getBoolean("oxidyn.disable")) {
            status = "disabled with -Doxidyn.disable";
            Oxidyn.LOG.info("Native code disabled by system property");
            return null;
        }

        int java = Runtime.version().feature();
        if (java < 22) {
            // FFM is preview-only on 21. Not worth the --enable-preview dance.
            status = "needs Java 22+, running on " + java;
            Oxidyn.LOG.warn("Oxidyn needs Java 22 or newer for its native code (this is Java {}). "
                    + "Everything will run as vanilla.", java);
            return null;
        }

        try {
            String override = System.getProperty("oxidyn.library");
            Path lib = override != null
                    ? Path.of(override)
                    : LibraryExtractor.extract(FMLPaths.GAMEDIR.get().resolve(".oxidyn").resolve("natives"));

            Class<?> impl = Class.forName(IMPL, true, Natives.class.getClassLoader());
            NativeBackend loaded = (NativeBackend) impl.getConstructor(Path.class, int.class).newInstance(lib, ABI_VERSION);
            status = "loaded " + loaded.describe();
            Oxidyn.LOG.info("Native backend ready: {}", loaded.describe());
            return loaded;
        } catch (InvocationTargetException e) {
            return fail(e.getCause());
        } catch (Throwable t) {
            return fail(t);
        }
    }

    private static NativeBackend fail(Throwable t) {
        status = "failed: " + t;
        Oxidyn.LOG.error("Couldn't load Oxidyn's native library, falling back to vanilla code", t);
        return null;
    }
}
