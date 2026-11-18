package com.example.cinematic;

import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import org.lwjgl.input.Keyboard;

@Mod(modid = CinematicMod.MODID, name = "Cinematic Auto Recorder", version = "0.2",
        clientSideOnly = true, acceptedMinecraftVersions = "[1.12.2]",
        guiFactory = "com.example.cinematic.ConfigGuiFactory")
public class CinematicMod {
    public static final String MODID = "cinematicrec";
    public static KeyBinding OPEN_CONFIG;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e) {
        CinematicConfig.init(e.getSuggestedConfigurationFile());
        OPEN_CONFIG = new KeyBinding("Open Cinematic settings", Keyboard.KEY_F7, "Cinematic Auto Recorder");
        ClientRegistry.registerKeyBinding(OPEN_CONFIG);
        MinecraftForge.EVENT_BUS.register(new Director());
        MinecraftForge.EVENT_BUS.register(this);
    }

    @SubscribeEvent
    public void onConfigChanged(ConfigChangedEvent.OnConfigChangedEvent e) {
        if (MODID.equals(e.getModID())) CinematicConfig.load();
    }
}
