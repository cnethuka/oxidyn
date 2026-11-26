package dev.oxidyn.ffi;

import java.util.Locale;

/** os/arch naming has to line up with hostPlatform() in build.gradle. */
final class Platform {
    private Platform() {
    }

    static String id() {
        return os() + "-" + arch();
    }

    static String libraryFileName() {
        return switch (os()) {
            case "windows" -> "oxidyn.dll";
            case "macos" -> "liboxidyn.dylib";
            default -> "liboxidyn.so";
        };
    }

    private static String os() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        return "linux";
    }

    private static String arch() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64", "x86_64", "x64" -> "x86_64";
            case "aarch64", "arm64" -> "aarch64";
            default -> arch;
        };
    }
}
