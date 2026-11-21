package dev.slinky.module.impl;

import dev.slinky.client.ClientContext;
import dev.slinky.client.KeyState;
import dev.slinky.client.SemanticEvent;
import dev.slinky.client.action.ActionAccess;
import dev.slinky.client.hook.GameHooks;
import dev.slinky.client.rotation.RecoveredRandom;
import dev.slinky.inject.ReflectionHelper;
import dev.slinky.module.EvidenceModule;
import dev.slinky.module.Module;
import dev.slinky.module.ModuleCategory;
import dev.slinky.module.ModuleManager;
import dev.slinky.setting.BooleanSetting;
import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.network.play.client.C02PacketUseEntity;
import org.lwjgl.input.Mouse;

public final class AutoBlockModule extends EvidenceModule {
   private static final int RECENT_ATTACK_TICKS = 3;
   private static final int SLOT_SWAP_GRACE_TICKS = 2;
   private static final Field LAST_ATTACKER_TIME = ReflectionHelper.findField(
      EntityLivingBase.class,
      "lastAttackerTime",
      "field_142016_bo"
   );

   private final ModuleManager modules;
   private final RecoveredRandom random = RecoveredRandom.SHARED;
   private int cooldown;
   private int heldTicks;
   private int attackTicks;
   private int targetId;
   private boolean fakeAnimation;
   private boolean wroteAnimation;
   private boolean suppressUse;
   private final AtomicInteger lag = new AtomicInteger();
   private final AtomicInteger recentAttackTicks = new AtomicInteger();
   private int lastBlockableSlot = -1;
   private int swapGraceTicks;
   private boolean blocking;
   private boolean overrideKey;
   private int animationRemaining = 72000;

   public AutoBlockModule(ModuleManager modules) {
      super("autoblock", "Auto Block", ModuleCategory.COMBAT);
      this.modules = modules;
      this.add(new BooleanSetting("require_physical_left_mouse", "Require Physical LMB", false));
   }

   private static boolean holdingSword(ClientContext c) {
      return c.player() != null && ActionAccess.canBlock(c.player().getHeldItem());
   }

   private static KeyBinding useKey(ClientContext c) {
      return c.minecraft().gameSettings.keyBindUseItem;
   }

   private static boolean focused(ClientContext c) {
      return c.ready() && c.minecraft().inGameHasFocus && c.minecraft().currentScreen == null;
   }

   private static int lastAttackerTime(EntityLivingBase player) {
      try {
         return LAST_ATTACKER_TIME.getInt(player);
      } catch (IllegalAccessException failure) {
         throw new IllegalStateException("Cannot read last attacker time", failure);
      }
   }

   private int randomizedInt(float value) {
      int whole = (int)value;
      float fraction = value - (float)whole;
      return whole + (this.random.chance(fraction) ? 1 : 0);
   }

   private boolean targetInBlockRange() {
      EntityPlayer target = GameHooks.target();
      if (target == null) {
         return false;
      }

      float range = this.number("range");
      return GameHooks.targetDistanceSquared() < (double)(range * range);
   }

   private boolean attackInputActive(ClientContext c, boolean recentAttack) {
      if (Mouse.isButtonDown(0)) {
         return true;
      }
      return !this.flag("require_physical_left_mouse")
         && (c.minecraft().gameSettings.keyBindAttack.isKeyDown()
            || recentAttack || this.recentAttackTicks.get() > 0);
   }

   private boolean timerRangeBlocksUse() {
      Module module = this.modules.find("timerrange");
      return module instanceof TimerRangeModule && ((TimerRangeModule)module).blocksUse();
   }

   @Override
   public boolean listens(int type) {
      // packet send, module toggle, click pre, shutdown
      return type == 3 || type == 5 || type == 14 || type == 26;
   }

   @Override
   protected void onEnable(ClientContext c) {
      this.resetState(c, false);
   }

   @Override
   protected void onDisable(ClientContext c) {
      this.resetState(c, true);
   }

   @Override
   public void onWorldUnload(ClientContext c) {
      this.resetState(c, true);
   }

   @Override
   public void onClientTickStart(ClientContext c) {
      this.updateAutoBlock(c);
   }

   @Override
   public void onClientTick(ClientContext c) {
      // OneClient Slate applies fake animation on UPDATE_POST, after the
      // world's entity update. Client tick END is the closest stable Forge
      // equivalent and, importantly, runs once per game tick rather than once
      // per rendered frame.
      this.applyAutoBlockAnimation(c);
   }

   @Override
   public void onEvent(ClientContext c, SemanticEvent e) {
      if (e.type == 3) {
         // The pipeline also classifies queued packets when checking for release.
         if (e.packetAgeMillis == 0L && e.packet instanceof C02PacketUseEntity
            && ((C02PacketUseEntity)e.packet).getAction() == C02PacketUseEntity.Action.ATTACK) {
            this.recentAttackTicks.set(RECENT_ATTACK_TICKS);
         }
         if (this.lag.get() != 0) {
            e.dispatchMode = 1;
         }
      } else if (e.type == 14) {
         if (e.active) {
            this.recentAttackTicks.set(RECENT_ATTACK_TICKS);
         } else if (!holdingSword(c) && this.overrideKey && this.blocking && c.player() != null) {
            // A slot change can occur after tick START, with synthetic use already queued.
            e.cancelled = true;
            this.beginSlotSwap(c, this.recentAttackTicks.get() > 0);
            this.releaseSwappedUseKey(c);
         } else {
            if (!holdingSword(c) && this.swapGraceTicks == 0) {
               this.lag.set(0);
            }

            if (this.suppressUse && !this.blocking) {
               e.cancelled = true;
            }
         }
      } else if (e.type == 26 || e.type == 5 && e.module == this && !e.enabled) {
         this.resetState(c, true);
      }
   }

   // Kept for ClientEventBridge compatibility. The OneClient implementation
   // does not swallow LWJGL mouse events; it cancels rightClickMouse at
   // CLICK_PRE instead.
   public boolean swallowMouseEvent(ClientContext c) {
      return false;
   }

   public boolean isActive() {
      return this.isEnabled() && (this.blocking || this.lag.get() != 0);
   }

   private void beginSlotSwap(ClientContext c, boolean recentAttack) {
      if (recentAttack && this.lastBlockableSlot >= 0
         && c.player().inventory.currentItem != this.lastBlockableSlot) {
         this.swapGraceTicks = SLOT_SWAP_GRACE_TICKS;
      }
   }

   private void releaseSwappedUseKey(ClientContext c) {
      KeyBinding key = useKey(c);
      // Drop pending synthetic presses as well as the held override.
      if (ActionAccess.presses(key) > 0) {
         key.isPressed();
      }
      KeyState.restore(key);
      this.overrideKey = false;
   }

   private void updateAutoBlock(ClientContext c) {
      boolean recentAttack = this.recentAttackTicks.getAndUpdate(ticks -> Math.max(0, ticks - 1)) > 0;
      this.fakeAnimation = false;

      if (this.wroteAnimation) {
         if (c.player() != null && !c.player().isUsingItem()) {
            ActionAccess.useCount(c.player(), 0);
         }
         this.wroteAnimation = false;
      }

      if (this.cooldown != 0) {
         --this.cooldown;
      }

      if (!focused(c)) {
         this.swapGraceTicks = 0;
         this.lastBlockableSlot = -1;
         if (this.overrideKey && c.minecraft() != null && c.minecraft().gameSettings != null) {
            KeyState.restore(useKey(c));
            this.overrideKey = false;
         }

         this.suppressUse = false;
         this.lag.set(0);
         return;
      }

      int hurtTime = c.player().hurtTime;
      boolean sword = holdingSword(c);
      this.suppressUse = false;

      boolean suspendBlock = false;
      if (sword) {
         if (this.swapGraceTicks > 0 && c.player().inventory.currentItem != this.lastBlockableSlot) {
            this.blocking = false;
         }
         this.lastBlockableSlot = c.player().inventory.currentItem;
         this.swapGraceTicks = 0;
      } else {
         if (this.blocking && this.overrideKey) {
            this.beginSlotSwap(c, recentAttack || this.recentAttackTicks.get() > 0);
            this.releaseSwappedUseKey(c);
         }
         suspendBlock = this.swapGraceTicks > 0;
         if (suspendBlock) {
            --this.swapGraceTicks;
         } else {
            this.lastBlockableSlot = -1;
         }
      }

      boolean animationEligible = false;
      boolean keyOwnedThisTick = false;
      boolean invalid = false;

      if (!sword) {
         invalid = this.overrideKey;
      } else if ((this.flag("require_left_mouse_pressed") && !this.attackInputActive(c, recentAttack))
         || this.timerRangeBlocksUse()) {
         invalid = true;
      } else {
         this.suppressUse = this.flag("require_right_mouse_pressed");
         boolean rightPressed = Mouse.isButtonDown(1);

         // Slate deliberately expects the physical RMB state to match this
         // option exactly: when "require RMB" is off, manually holding RMB
         // yields control back to vanilla instead of fighting the user.
         if (this.suppressUse != rightPressed) {
            invalid = true;
         } else {
            animationEligible = true;

            if (hurtTime == 0 && this.flag("require_damage")) {
               invalid = true;
            } else if (hurtTime > this.randomizedInt(this.number("max_hurt_time"))) {
               invalid = true;
            } else {
               KeyBinding key = useKey(c);
               KeyState.set(key, this.blocking);
               keyOwnedThisTick = true;
               if (this.blocking) {
                  ActionAccess.increment(key);
               }
            }
         }
      }

      if (invalid) {
         KeyBinding key = useKey(c);
         KeyState.set(key, !this.suppressUse && KeyState.physical(key));
      }

      if (sword || invalid) {
         this.overrideKey = keyOwnedThisTick;
      }

      this.fakeAnimation = animationEligible
         && this.flag("fake_block_animation")
         && (!this.flag("fake_block_animation_require_target") || this.targetInBlockRange());

      if (hurtTime == 10) {
         this.cooldown = 0;
      }

      ++this.attackTicks;
      boolean attackSignal = false;
      if (Integer.compareUnsigned(this.attackTicks, 10) >= 0
         && c.player().ticksExisted == lastAttackerTime(c.player()) + 1) {
         this.attackTicks = 0;
         attackSignal = true;
      }

      EntityPlayer target = GameHooks.target();
      int nextTargetId = target == null ? 0 : target.getEntityId();
      if (nextTargetId != this.targetId) {
         this.targetId = nextTargetId;
         this.attackTicks = 0;
         attackSignal = true;
      }

      if (this.lag.get() != 0) {
         if (attackSignal && this.flag("lag_flush_attack")) {
            this.lag.set(0);
         } else {
            this.lag.getAndDecrement();
         }

         if (this.flag("lag_reblock") && this.lag.get() == 0) {
            this.cooldown = 0;
         }
      }

      if (suspendBlock) {
         return;
      }

      if (this.blocking) {
         int held = ++this.heldTicks;
         int maximum = this.randomizedInt(this.number("max_hold_time"));

         if (keyOwnedThisTick && held < maximum) {
            return;
         }

         if (sword) {
            KeyBinding key = useKey(c);
            KeyState.set(key, false);
            this.overrideKey = true;
            if (keyOwnedThisTick) {
               // Undo the synthetic press queued above before the hold ended.
               key.isPressed();
            }
         }

         if (this.random.chance(this.number("lag_chance"))) {
            this.lag.set(this.randomizedInt(this.number("lag_duration")));
         }

         this.blocking = false;
         if (sword && c.player().isBlocking()) {
            // Release the sword before other tick listeners check item use for attacks.
            c.minecraft().playerController.onStoppedUsingItem(c.player());
         }
         return;
      }

      if (!keyOwnedThisTick || this.cooldown != 0 || !this.targetInBlockRange()) {
         return;
      }

      KeyBinding key = useKey(c);
      KeyState.set(key, true);
      ActionAccess.increment(key);
      this.overrideKey = true;
      this.blocking = true;
      this.cooldown = 10;
      this.heldTicks = 0;
      this.lag.set(0);
   }

   private void applyAutoBlockAnimation(ClientContext c) {
      if (!this.fakeAnimation) {
         this.animationRemaining = 72000;
         return;
      }

      if (c.player() == null) {
         return;
      }

      this.wroteAnimation = true;
      --this.animationRemaining;
      ActionAccess.useCount(c.player(), this.animationRemaining);
   }

   private void resetState(ClientContext c, boolean restorePhysicalKey) {
      if (restorePhysicalKey && this.overrideKey && c != null
         && c.minecraft() != null && c.minecraft().gameSettings != null) {
         KeyState.restore(useKey(c));
      }

      if (this.wroteAnimation && c != null && c.player() != null && !c.player().isUsingItem()) {
         ActionAccess.useCount(c.player(), 0);
      }

      this.cooldown = 0;
      this.heldTicks = 0;
      this.attackTicks = 0;
      this.targetId = 0;
      this.fakeAnimation = false;
      this.wroteAnimation = false;
      this.suppressUse = false;
      this.lag.set(0);
      this.recentAttackTicks.set(0);
      this.lastBlockableSlot = -1;
      this.swapGraceTicks = 0;
      this.blocking = false;
      this.overrideKey = false;
      this.animationRemaining = 72000;
   }
}
