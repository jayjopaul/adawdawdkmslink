package dev.slinky.forge;

import dev.slinky.SlinkyMod;
import dev.slinky.client.ClientEventBridge;
import dev.slinky.client.hook.GameHooks;
import dev.slinky.inject.Platform;
import dev.slinky.inject.SlinkyCorePlugin;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

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

      // Drive Slinky's lifecycle from Forge rather than Minecraft.runTick /
      // runGameLoop. A later injected client may redefine Minecraft.class,
      // but these Forge events remain intact.
      FMLCommonHandler.instance().bus().register(this);

      Platform.log("Slinky Semantic started through the Forge/LaunchWrapper macOS loader");
   }

   @SubscribeEvent
   public void onClientTick(TickEvent.ClientTickEvent event) {
      boolean start = event.phase == TickEvent.Phase.START;
      GameHooks.forgeWorldTick(start);
      if (start) {
         GameHooks.forgeControllerTick();
      }
      ClientEventBridge.forgeClientTick(start);
   }

   @SubscribeEvent
   public void onRenderTick(TickEvent.RenderTickEvent event) {
      boolean start = event.phase == TickEvent.Phase.START;
      if (start) {
         GameHooks.forgeFrameStart(event.renderTickTime);
      }
      ClientEventBridge.forgeRenderTick(start);
   }

   @SubscribeEvent
   public void onKeyInput(InputEvent.KeyInputEvent event) {
      ClientEventBridge.forgeKeyInput();
   }

   @SubscribeEvent
   public void onMouseInput(InputEvent.MouseInputEvent event) {
      ClientEventBridge.forgeMouseInput();
   }
}
