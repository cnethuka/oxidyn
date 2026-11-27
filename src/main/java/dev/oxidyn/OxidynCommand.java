package dev.oxidyn;

import com.mojang.brigadier.CommandDispatcher;
import dev.oxidyn.ffi.Natives;
import dev.oxidyn.gen.FillStats;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** /oxidyn - is the native stuff on; /oxidyn stats - how fast is it. */
final class OxidynCommand {
    private OxidynCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oxidyn")
                .executes(ctx -> {
                    CommandSourceStack src = ctx.getSource();
                    src.sendSuccess(() -> Component.literal("Oxidyn native library: " + Natives.status()), false);
                    src.sendSuccess(() -> Component.literal("Active: " + Features.summary()), false);
                    return 1;
                })
                .then(Commands.literal("stats")
                        .executes(ctx -> stats(ctx.getSource()))
                        .then(Commands.literal("reset")
                                .requires(src -> src.hasPermission(2))
                                .executes(ctx -> {
                                    FillStats.reset();
                                    ctx.getSource().sendSuccess(() -> Component.literal("Oxidyn stats reset"), false);
                                    return 1;
                                }))));
    }

    private static int stats(CommandSourceStack src) {
        long n = FillStats.nativeChunks();
        long v = FillStats.vanillaChunks();
        double nMs = FillStats.nativeAvgMs();
        double vMs = FillStats.vanillaAvgMs();
        src.sendSuccess(() -> Component.literal("Noise fill step, per chunk (terrain, caves, aquifers, ore veins):"), false);
        src.sendSuccess(() -> Component.literal(line("native", n, nMs)), false);
        src.sendSuccess(() -> Component.literal(line("vanilla", v, vMs)), false);
        if (n > 0 && v > 0) {
            src.sendSuccess(() -> Component.literal(String.format(Locale.ROOT, "  native is %.2fx the speed of vanilla", vMs / nMs)), false);
        } else if (n > 0) {
            src.sendSuccess(() -> Component.literal("  (set nativeChunkFill = false in config/oxidyn-common.toml to time vanilla for comparison)"), false);
        }
        return 1;
    }

    private static String line(String name, long chunks, double avgMs) {
        return chunks == 0
                ? String.format(Locale.ROOT, "  %-7s 0 chunks", name)
                : String.format(Locale.ROOT, "  %-7s %d chunks, %.2f ms average", name, chunks, avgMs);
    }
}
