package dev.slinky.inject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import dev.slinky.shaded.asm.ClassReader;
import net.minecraft.launchwrapper.LaunchClassLoader;
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

         // Mapping lookup must never resolve Minecraft classes with Class.forName
         // during Mixin's prepare phase. Read raw class headers from LaunchWrapper
         // instead so inherited methods/fields can be mapped without class loading.
         Mappings.setHierarchyProvider(SlinkyCorePlugin::readSupertypes);
      } catch (IOException failure) {
         throw new IllegalStateException("Could not load Slinky runtime mappings", failure);
      }

      initialized = true;
      Platform.log("Coremod runtime initialized: " + Platform.describe() + ", " + Mappings.size() + " mappings");
   }

   private static String[] readSupertypes(String runtimeInternalName) {
      try {
         ClassLoader loader = Platform.getGameClassLoader();
         byte[] bytes = null;

         if (loader instanceof LaunchClassLoader) {
            bytes = ((LaunchClassLoader)loader).getClassBytes(runtimeInternalName.replace('/', '.'));
         }

         if (bytes != null) {
            ClassReader reader = new ClassReader(bytes);
            String[] interfaces = reader.getInterfaces();
            String[] result = new String[interfaces.length + 1];
            result[0] = reader.getSuperName();
            System.arraycopy(interfaces, 0, result, 1, interfaces.length);
            return result;
         }

         InputStream stream = loader.getResourceAsStream(runtimeInternalName + ".class");
         if (stream != null) {
            try {
               ClassReader reader = new ClassReader(stream);
               String[] interfaces = reader.getInterfaces();
               String[] result = new String[interfaces.length + 1];
               result[0] = reader.getSuperName();
               System.arraycopy(interfaces, 0, result, 1, interfaces.length);
               return result;
            } finally {
               stream.close();
            }
         }
      } catch (Throwable failure) {
         Platform.log("Could not inspect hierarchy for " + runtimeInternalName + " without loading it: " + failure);
      }

      return null;
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
