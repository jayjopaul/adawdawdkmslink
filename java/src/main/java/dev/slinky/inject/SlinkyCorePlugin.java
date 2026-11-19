package dev.slinky.inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

@IFMLLoadingPlugin.Name("Slinky Semantic Coremod")
@IFMLLoadingPlugin.MCVersion("1.8.9")
@IFMLLoadingPlugin.SortingIndex(1001)
@IFMLLoadingPlugin.TransformerExclusions({"dev.slinky."})
public final class SlinkyCorePlugin implements IFMLLoadingPlugin {
   private static volatile boolean runtimeDeobfuscationEnabled = true;
   private static volatile boolean initialized;

   public SlinkyCorePlugin() {
      System.setProperty("slinky.coremod.present", "true");
   }

   public static synchronized void ensureInitialized() {
      if (initialized) {
         return;
      }

      Platform.Namespace namespace = runtimeDeobfuscationEnabled ? Platform.Namespace.SRG : Platform.Namespace.NAMED;
      Platform.initialize(namespace, null, false);

      try {
         InputStream mappings = namespace == Platform.Namespace.NAMED
            ? null
            : SlinkyCorePlugin.class.getResourceAsStream(Mappings.RESOURCE);
         if (namespace != Platform.Namespace.NAMED && mappings == null) {
            throw new IllegalStateException("Missing " + Mappings.RESOURCE + " for " + namespace.id + " runtime");
         }

         try {
            Mappings.load(mappings);
         } finally {
            if (mappings != null) {
               mappings.close();
            }
         }
      } catch (IOException failure) {
         throw new IllegalStateException("Could not load Slinky runtime mappings", failure);
      }

      initialized = true;
      Platform.log("Coremod runtime initialized: " + Platform.describe() + ", " + Mappings.size() + " mappings");
   }

   @Override
   public String[] getASMTransformerClass() {
      return new String[]{"dev.slinky.inject.transform.LaunchTransformer"};
   }

   @Override
   public String getModContainerClass() {
      return null;
   }

   @Override
   public String getSetupClass() {
      return null;
   }

   @Override
   public void injectData(Map<String, Object> data) {
      Object deobfuscated = data.get("runtimeDeobfuscationEnabled");
      runtimeDeobfuscationEnabled = Boolean.TRUE.equals(deobfuscated);
      ensureInitialized();
   }

   @Override
   public String getAccessTransformerClass() {
      return null;
   }
}
