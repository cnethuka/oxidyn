package dev.oxidyn;

import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.Natives;

/**
 * The switches the mixins actually look at. Each one is "enabled in config
 * AND the library loaded AND its startup parity check passed". Until the
 * config has loaded everything stays off, i.e. vanilla behaviour.
 */
public final class Features {
    public static volatile boolean noise;
    public static volatile boolean chunkFill;
    public static volatile boolean translucentSort;
    public static volatile boolean regionZlib;
    public static volatile boolean networkZlib;

    public static volatile int regionZlibLevel = 6;
    public static volatile int networkZlibLevel = 6;

    private static SelfTest.Result checks;

    private Features() {
    }

    static synchronized void refresh() {
        NativeBackend backend = Natives.get();
        if (backend != null && checks == null) {
            checks = SelfTest.run(backend);
        }
        boolean ok = backend != null;

        noise = ok && checks.noise() && OxidynConfig.NATIVE_NOISE.get();
        chunkFill = ok && checks.noise() && OxidynConfig.NATIVE_CHUNK_FILL.get();
        translucentSort = ok && checks.sort() && OxidynConfig.NATIVE_TRANSLUCENT_SORT.get();
        regionZlib = ok && checks.zlib() && OxidynConfig.NATIVE_REGION_ZLIB.get();
        networkZlib = ok && checks.zlib() && OxidynConfig.NATIVE_NETWORK_ZLIB.get();
        regionZlibLevel = OxidynConfig.REGION_ZLIB_LEVEL.get();
        networkZlibLevel = OxidynConfig.NETWORK_ZLIB_LEVEL.get();

        Oxidyn.LOG.info("Native paths: noise={}, chunkFill={}, translucentSort={}, regionZlib={}, networkZlib={}",
                noise, chunkFill, translucentSort, regionZlib, networkZlib);
    }

    public static String summary() {
        return "noise=" + noise + " chunkFill=" + chunkFill + " translucentSort=" + translucentSort
                + " regionZlib=" + regionZlib + " (level " + regionZlibLevel + ")"
                + " networkZlib=" + networkZlib + " (level " + networkZlibLevel + ")";
    }
}
