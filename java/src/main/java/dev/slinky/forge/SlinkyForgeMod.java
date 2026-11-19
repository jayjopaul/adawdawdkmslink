package dev.slinky.forge;

import dev.slinky.SlinkyMod;
import dev.slinky.inject.Platform;
import dev.slinky.inject.SlinkyCorePlugin;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;

@Mod(
   modid = SlinkyMod.MOD_ID,
   name = SlinkyMod.NAME,
   version = SlinkyMod.VERSION,
   clientSideOnly = true,
   acceptedMinecraftVersions = "[1.8.9]"
)
public final class SlinkyForgeMod {
   private SlinkyMod delegate;

   @Mod.EventHandler
   public void initialize(FMLInitializationEvent event) {
      SlinkyCorePlugin.ensureInitialized();
      System.setProperty("slinky.patches.installed", "true");

      this.delegate = new SlinkyMod();
      this.delegate.preInitialize();
      this.delegate.initialize();

      Platform.log("Slinky Semantic started through the Forge/LaunchWrapper macOS loader");
   }
}
