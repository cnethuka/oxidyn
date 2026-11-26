package dev.oxidyn;

import net.neoforged.neoforge.common.ModConfigSpec;

/** config/oxidyn-common.toml */
public final class OxidynConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.BooleanValue NATIVE_NOISE;
    public static final ModConfigSpec.BooleanValue NATIVE_TRANSLUCENT_SORT;
    public static final ModConfigSpec.BooleanValue NATIVE_REGION_ZLIB;
    public static final ModConfigSpec.IntValue REGION_ZLIB_LEVEL;
    public static final ModConfigSpec.BooleanValue NATIVE_NETWORK_ZLIB;
    public static final ModConfigSpec.IntValue NETWORK_ZLIB_LEVEL;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("worldgen");
        NATIVE_NOISE = b
                .comment("Sample Perlin/normal/blended noise in Rust when density functions fill whole arrays.",
                        "Output is bit-for-bit identical to vanilla (checked at startup).")
                .define("nativeNoise", true);
        b.pop();

        b.push("rendering");
        NATIVE_TRANSLUCENT_SORT = b
                .comment("Re-sort translucent chunk geometry (water, glass, ...) in Rust. Client only.")
                .define("nativeTranslucentSort", true);
        b.pop();

        b.push("compression");
        NATIVE_REGION_ZLIB = b
                .comment("Use libdeflate for deflate-compressed region files (the default format).",
                        "Files stay normal zlib, so they still open fine without the mod.")
                .define("nativeRegionCompression", true);
        REGION_ZLIB_LEVEL = b
                .comment("1 = fastest, 12 = smallest. Vanilla's zlib uses 6.")
                .defineInRange("regionCompressionLevel", 6, 1, 12);
        NATIVE_NETWORK_ZLIB = b
                .comment("Use libdeflate for packet compression. Works with vanilla clients/servers on the other end.")
                .define("nativeNetworkCompression", true);
        NETWORK_ZLIB_LEVEL = b
                .comment("Packets are latency sensitive, so don't go crazy here.")
                .defineInRange("networkCompressionLevel", 6, 1, 12);
        b.pop();

        SPEC = b.build();
    }

    private OxidynConfig() {
    }
}
