package dev.oxidyn;

import com.mojang.brigadier.CommandDispatcher;
import dev.oxidyn.ffi.Natives;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/** /oxidyn - tells you whether the native stuff is actually on. */
final class OxidynCommand {
    private OxidynCommand() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("oxidyn").executes(ctx -> {
            CommandSourceStack src = ctx.getSource();
            src.sendSuccess(() -> Component.literal("Oxidyn native library: " + Natives.status()), false);
            src.sendSuccess(() -> Component.literal("Active: " + Features.summary()), false);
            return 1;
        }));
    }
}
