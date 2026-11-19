package dev.slinky.inject.transform;

import dev.slinky.inject.NativeBridge;
import dev.slinky.inject.Platform;
import dev.slinky.shaded.asm.ClassReader;
import dev.slinky.shaded.asm.ClassWriter;
import dev.slinky.shaded.asm.tree.ClassNode;
import dev.slinky.shaded.asm.tree.MethodNode;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TransformerEngine {
   private int applied;
   private int failed;
   private int warnings;

   public void applyAll(List<SlinkyPatches.ClassEdits> patches) {
      for (SlinkyPatches.ClassEdits patch : patches) {
         try {
            this.apply(patch);
         } catch (Throwable var5) {
            this.failed++;
            Platform.log("Failed to patch " + patch.target, var5);
         }
      }

      Platform.log(String.format("Patched %d classes (%d failed, %d warnings)", this.applied, this.failed, this.warnings));
   }

   public int getFailed() {
      return this.failed;
   }

   private void warn(String message) {
      this.warnings++;
      Platform.log("WARN " + message);
   }

   private void apply(SlinkyPatches.ClassEdits patch) {
      String runtimeName = patch.getRuntimeTarget();

      Class<?> target;
      try {
         target = Class.forName(runtimeName.replace('/', '.'), false, Platform.getGameClassLoader());
      } catch (LinkageError | ClassNotFoundException var11) {
         this.failed++;
         Platform.log("Missing target class " + patch.target + " (" + runtimeName + ")");
         return;
      }

      byte[] original = NativeBridge.getClassBytes(target);
      if (original == null) {
         throw new IllegalStateException("Unable to capture bytecode of " + runtimeName);
      } else {
         ClassNode node = new ClassNode();
         new ClassReader(original).accept(node, 4);
         int changes = 0;

         for (SlinkyPatches.MethodEdit edit : patch.edits) {
            MethodNode method = patch.find(node, edit);
            if (method == null) {
               this.warn(patch.target + ": method not found for " + edit.describe());
            } else {
               int points = edit.apply(method);
               if (points == 0) {
                  this.warn(patch.target + ": no injection point for " + edit.describe());
               } else if (edit.expected >= 0 && points != edit.expected) {
                  this.warn(patch.target + ": " + points + " injection points (expected " + edit.expected + ") for " + edit.describe());
               }

               changes += points;
            }
         }

         if (patch.hasEveryMethodEdits()) {
            int points = patch.applyEveryMethod(node);
            if (points != patch.getEveryMethodExpected()) {
               this.warn(
                  patch.target + ": " + points + " injection points (expected " + patch.getEveryMethodExpected() + ") for " + patch.getEveryMethodLabel()
               );
            }

            changes += points;
         }

         if (changes != 0) {
            ClassWriter writer = new TransformerEngine.GameClassWriter(3);
            node.accept(writer);
            int error = NativeBridge.retransformClass(target, writer.toByteArray());
            if (error != 0) {
               throw new IllegalStateException(
                  "RetransformClasses returned " + error + (error == -1 ? " (load hook never matched the target)" : " (JVMTI error)")
               );
            } else {
               this.applied++;
            }
         }
      }
   }

   static final class GameClassWriter extends ClassWriter {
      private final ClassLoader loader;
      private final Map<String, TypeInfo> hierarchy = new HashMap<>();

      GameClassWriter(int flags) {
         super(flags);
         this.loader = Platform.getGameClassLoader();
      }

      @Override
      protected String getCommonSuperClass(String first, String second) {
         if (first.equals(second)) {
            return first;
         }

         try {
            if (isAssignableFrom(first, second)) {
               return first;
            }
            if (isAssignableFrom(second, first)) {
               return second;
            }

            TypeInfo firstInfo = info(first);
            TypeInfo secondInfo = info(second);
            if (firstInfo == null || secondInfo == null || firstInfo.isInterface || secondInfo.isInterface) {
               return "java/lang/Object";
            }

            String candidate = firstInfo.superName;
            while (candidate != null) {
               if (isAssignableFrom(candidate, second)) {
                  return candidate;
               }
               TypeInfo next = info(candidate);
               candidate = next == null ? null : next.superName;
            }
         } catch (Throwable ignored) {
         }

         return "java/lang/Object";
      }

      private boolean isAssignableFrom(String target, String source) {
         if (target.equals(source) || "java/lang/Object".equals(target)) {
            return true;
         }

         Set<String> visited = new HashSet<>();
         ArrayDeque<String> queue = new ArrayDeque<>();
         queue.add(source);

         while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) {
               continue;
            }
            if (target.equals(current)) {
               return true;
            }

            TypeInfo type = info(current);
            if (type == null) {
               continue;
            }
            if (type.superName != null) {
               queue.addLast(type.superName);
            }
            for (String iface : type.interfaces) {
               queue.addLast(iface);
            }
         }

         return false;
      }

      private TypeInfo info(String internalName) {
         if (hierarchy.containsKey(internalName)) {
            return hierarchy.get(internalName);
         }

         TypeInfo result = null;
         InputStream stream = null;
         try {
            stream = loader.getResourceAsStream(internalName + ".class");
            if (stream != null) {
               ClassReader reader = new ClassReader(stream);
               result = new TypeInfo(
                  reader.getSuperName(),
                  reader.getInterfaces(),
                  (reader.getAccess() & 0x0200) != 0
               );
            }
         } catch (Throwable ignored) {
         } finally {
            if (stream != null) {
               try {
                  stream.close();
               } catch (Throwable ignored) {
               }
            }
         }

         hierarchy.put(internalName, result);
         return result;
      }

      private static final class TypeInfo {
         final String superName;
         final String[] interfaces;
         final boolean isInterface;

         TypeInfo(String superName, String[] interfaces, boolean isInterface) {
            this.superName = superName;
            this.interfaces = interfaces == null ? new String[0] : interfaces;
            this.isInterface = isInterface;
         }
      }
   }

}
