package com.example.cinematic;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.config.ConfigElement;
import net.minecraftforge.fml.client.IModGuiFactory;
import net.minecraftforge.fml.client.config.GuiConfig;
import net.minecraftforge.fml.client.config.IConfigElement;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Config screen: Mods -> Cinematic Auto Recorder -> Config, or the in-game key (default F7). */
public class ConfigGuiFactory implements IModGuiFactory {
    @Override public void initialize(Minecraft mc) { }
    @Override public boolean hasConfigGui() { return true; }
    @Override public GuiScreen createConfigGui(GuiScreen parent) { return new ConfigGui(parent); }
    @Override public Set<RuntimeOptionCategoryElement> runtimeGuiCategories() { return null; }

    public static class ConfigGui extends GuiConfig {
        private static final int OPEN_FOLDER_ID = 3000;

        public ConfigGui(GuiScreen parent) {
            super(parent, elements(), CinematicMod.MODID, false, false, "Cinematic Auto Recorder");
        }

        private static List<IConfigElement> elements() {
            List<IConfigElement> list = new ArrayList<>();
            for (String name : CinematicConfig.config.getCategoryNames()) {
                list.add(new ConfigElement(CinematicConfig.config.getCategory(name)));
            }
            return list;
        }

        @Override
        public void initGui() {
            super.initGui();
            this.buttonList.add(new GuiButton(OPEN_FOLDER_ID, 6, 6, 140, 20, "Open videos folder"));
        }

        @Override
        protected void actionPerformed(GuiButton button) {
            if (button.id == OPEN_FOLDER_ID) openOutputFolder();
            else super.actionPerformed(button);
        }

        /** Opens the folder with everything the mod recorded (.minecraft/<outputDir>). */
        private static void openOutputFolder() {
            File dir = new File(CinematicConfig.outputDir);
            if (!dir.isAbsolute()) dir = new File(Minecraft.getMinecraft().mcDataDir, CinematicConfig.outputDir);
            dir.mkdirs();
            try {
                if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                    Desktop.getDesktop().open(dir);
                    return;
                }
            } catch (Throwable ignored) { }
            String os = System.getProperty("os.name", "").toLowerCase();
            try {
                if (os.contains("win")) new ProcessBuilder("explorer.exe", dir.getAbsolutePath()).start();
                else if (os.contains("mac")) new ProcessBuilder("open", dir.getAbsolutePath()).start();
                else new ProcessBuilder("xdg-open", dir.getAbsolutePath()).start();
            } catch (IOException ignored) { }
        }
    }
}
