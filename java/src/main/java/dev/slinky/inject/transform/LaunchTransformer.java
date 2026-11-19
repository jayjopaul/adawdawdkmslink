package dev.slinky.inject.transform;

import dev.slinky.inject.Platform;
import dev.slinky.inject.SlinkyCorePlugin;
import dev.slinky.shaded.asm.ClassReader;
import dev.slinky.shaded.asm.tree.ClassNode;
import dev.slinky.shaded.asm.tree.MethodNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.launchwrapper.IClassTransformer;

public final class LaunchTransformer implements IClassTransformer {
   private final Map<String, SlinkyPatches.ClassEdits> patches = new HashMap<>();

   public LaunchTransformer() {
      SlinkyCorePlugin.ensureInitialized();
      List<SlinkyPatches.ClassEdits> created = SlinkyPatches.create(true);
      for (SlinkyPatches.ClassEdits patch : created) {
         this.patches.put(patch.getRuntimeTarget(), patch);
      }
      Platform.log("LaunchWrapper transformer armed for " + this.patches.size() + " Minecraft classes");
   }

   @Override
   public byte[] transform(String name, String transformedName, byte[] basicClass) {
      if (basicClass == null) return null;

      String transformed = transformedName == null ? null : transformedName.replace('.', '/');
      String original = name == null ? null : name.replace('.', '/');
      SlinkyPatches.ClassEdits patch = transformed == null ? null : this.patches.get(transformed);
      if (patch == null && original != null) patch = this.patches.get(original);
      if (patch == null) return basicClass;

      try {
         byte[] result = apply(patch, basicClass);
         Platform.log("Coremod patched " + patch.target);
         return result;
      } catch (Throwable failure) {
         Platform.log("Coremod failed to patch " + patch.target, failure);
         throw failure instanceof RuntimeException ? (RuntimeException)failure : new RuntimeException(failure);
      }
   }

   private static byte[] apply(SlinkyPatches.ClassEdits patch, byte[] original) {
      ClassNode node = new ClassNode();
      new ClassReader(original).accept(node, 4);
      int changes = 0;

      for (SlinkyPatches.MethodEdit edit : patch.edits) {
         MethodNode method = patch.find(node, edit);
         if (method == null) {
            Platform.log("WARN " + patch.target + ": method not found for " + edit.describe());
            continue;
         }
         int points = edit.apply(method);
         if (points == 0) {
            Platform.log("WARN " + patch.target + ": no injection point for " + edit.describe());
         } else if (edit.expected >= 0 && points != edit.expected) {
            Platform.log("WARN " + patch.target + ": " + points + " injection points (expected " + edit.expected + ") for " + edit.describe());
         }
         changes += points;
      }

      if (patch.hasEveryMethodEdits()) {
         int points = patch.applyEveryMethod(node);
         if (points != patch.getEveryMethodExpected()) {
            Platform.log("WARN " + patch.target + ": " + points + " injection points (expected "
               + patch.getEveryMethodExpected() + ") for " + patch.getEveryMethodLabel());
         }
         changes += points;
      }

      if (changes == 0) return original;

      TransformerEngine.GameClassWriter writer = new TransformerEngine.GameClassWriter(3);
      node.accept(writer);
      return writer.toByteArray();
   }
}
