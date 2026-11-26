package dev.oxidyn;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(Oxidyn.MOD_ID)
public final class Oxidyn {
    public static final String MOD_ID = "oxidyn";
    public static final Logger LOG = LoggerFactory.getLogger("Oxidyn");

    public Oxidyn(IEventBus modBus, ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, OxidynConfig.SPEC);
        modBus.addListener(Oxidyn::onConfig);
        NeoForge.EVENT_BUS.addListener((RegisterCommandsEvent e) -> OxidynCommand.register(e.getDispatcher()));
    }

    private static void onConfig(ModConfigEvent event) {
        if (event.getConfig().getSpec() == OxidynConfig.SPEC && !(event instanceof ModConfigEvent.Unloading)) {
            Features.refresh();
        }
    }
}
