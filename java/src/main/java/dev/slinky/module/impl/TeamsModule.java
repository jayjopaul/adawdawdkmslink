package dev.slinky.module.impl;

import dev.slinky.client.ClientContext;
import dev.slinky.client.RelationshipService;
import dev.slinky.client.SemanticEvent;
import dev.slinky.module.EvidenceModule;
import dev.slinky.module.ModuleCategory;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.scoreboard.Team;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IChatComponent;

public final class TeamsModule extends EvidenceModule {
   public static final String[] COLORS = new String[]{
      "Black",
      "Dark blue",
      "Dark green",
      "Dark aqua",
      "Dark red",
      "Dark purple",
      "Gold",
      "Light gray",
      "Dark gray",
      "Light blue",
      "Light green",
      "Light aqua",
      "Light red",
      "Light purple",
      "Yellow",
      "White"
   };

   public TeamsModule() {
      super("teams", "Teams", ModuleCategory.UTILITY);
   }

   private static boolean colorCode(char c) {
      return (c - '0' & 65535) < 10 || (c - 'a' & 65535) < 6 || c == 'r';
   }

   private static boolean nameCharacter(char c) {
      return c == '_' || (c - '0' & 65535) < 10 || ((c & '\uffdf') - 65 & 65535) < 26;
   }

   public static char prefixColor(String text) {
      char result = 0;
      boolean section = false;

      for (int i = 0; i < text.length(); i++) {
         char c = text.charAt(i);
         boolean escaped = section;
         section = c == 167;
         if (c != 167) {
            if (escaped) {
               if (colorCode(c)) {
                  result = c;
               }
            } else if (nameCharacter(c)) {
               return result;
            }
         }
      }

      return '\u0000';
   }

   public static char trailingColor(String text) {
      char selected = 0;
      boolean valid = false;

      for (int i = text.length() - 1; i >= 0; i--) {
         char c = text.charAt(i);
         if (c == 167) {
            if (valid) {
               return selected;
            }

            valid = false;
         } else {
            valid = colorCode(c);
            if (valid) {
               selected = c;
            }
         }
      }

      return 'f';
   }

   public static char entityColor(Entity entity) {
      char initial = prefixColor(entity.getName());
      if (initial != 0) {
         return initial;
      } else {
         IChatComponent component = entity.getDisplayName();
         return component instanceof ChatComponentText ? trailingColor(((ChatComponentText)component).getChatComponentText_TextValue()) : 'f';
      }
   }

   @Override
   public boolean listens(int type) {
      return type == 0;
   }

   @Override
   public void onEvent(ClientContext context, SemanticEvent event) {
      this.refresh(context);
   }

   private static char formattedColor(String text) {
      if (text == null) {
         return 0;
      }
      char color = prefixColor(text);
      return color == 'r' ? 0 : color;
   }

   private static char playerColor(ClientContext context, EntityPlayer player) {
      NetworkPlayerInfo info = context.minecraft().getNetHandler().getPlayerInfo(player.getUniqueID());

      if (info != null) {
         IChatComponent display = info.getDisplayName();
         if (display != null) {
            char color = formattedColor(display.getFormattedText());
            if (color != 0) {
               return color;
            }
         }

         ScorePlayerTeam team = info.getPlayerTeam();
         if (team != null) {
            String profileName = info.getGameProfile() == null ? player.getName() : info.getGameProfile().getName();
            char color = formattedColor(ScorePlayerTeam.formatPlayerName(team, profileName));
            if (color != 0) {
               return color;
            }

            color = formattedColor(team.getColorPrefix() + profileName + team.getColorSuffix());
            if (color != 0) {
               return color;
            }
         }
      }

      IChatComponent display = player.getDisplayName();
      if (display != null) {
         char color = formattedColor(display.getFormattedText());
         if (color != 0) {
            return color;
         }
      }

      return formattedColor(player.getName());
   }

   public boolean isTeammate(ClientContext context, EntityPlayer player) {
      if (!this.isEnabled() || context == null || !context.ready() || player == null || player == context.player()) {
         return false;
      }

      EntityPlayer self = context.player();

      // 1) Vanilla entity-level team relationship.
      if (self.isOnSameTeam(player)) {
         return true;
      }

      // 2) World scoreboard membership by player name.
      ScorePlayerTeam selfScoreTeam = context.world().getScoreboard().getPlayersTeam(self.getName());
      ScorePlayerTeam otherScoreTeam = context.world().getScoreboard().getPlayersTeam(player.getName());
      if (selfScoreTeam != null && otherScoreTeam != null
         && (selfScoreTeam == otherScoreTeam
            || selfScoreTeam.getRegisteredName().equals(otherScoreTeam.getRegisteredName()))) {
         return true;
      }

      // 3) Tab-list team relationship.
      NetworkPlayerInfo selfInfo = context.minecraft().getNetHandler().getPlayerInfo(self.getUniqueID());
      NetworkPlayerInfo playerInfo = context.minecraft().getNetHandler().getPlayerInfo(player.getUniqueID());
      if (selfInfo != null && playerInfo != null) {
         Team selfTeam = selfInfo.getPlayerTeam();
         Team otherTeam = playerInfo.getPlayerTeam();
         if (selfTeam != null && otherTeam != null
            && (selfTeam == otherTeam
               || selfTeam.isSameTeam(otherTeam)
               || selfTeam.getRegisteredName().equals(otherTeam.getRegisteredName()))) {
            return true;
         }
      }

      // 4) BedWars-style formatted-name color fallback.
      if (this.flag("match_self_color")) {
         char selfColor = playerColor(context, self);
         char otherColor = playerColor(context, player);
         if (selfColor != 0 && otherColor != 0 && selfColor == otherColor) {
            return true;
         }

         int low = otherColor & 255;
         int index = ((byte)low >= 97 ? 169 : 208) + low & 0xFF;
         if (otherColor != 0 && index < COLORS.length && this.names("team_colors").contains(COLORS[index])) {
            return true;
         }
      }

      return false;
   }

   public void refresh(ClientContext context) {
      if (context.ready()) {
         for (EntityPlayer player : context.world().playerEntities) {
            if (player != context.player()) {
               RelationshipService.set(player, 1, this.isTeammate(context, player));
            }
         }
      }
   }

   @Override
   protected void onDisable(ClientContext context) {
      RelationshipService.clear(1);
   }

   @Override
   public void onWorldUnload(ClientContext context) {
      RelationshipService.clear(1);
   }
}
