package com.playercorpse.client.screen;

import com.mojang.authlib.GameProfile;
import com.playercorpse.client.PlayerCorpseSkinResolver;
import com.playercorpse.history.DeathHistoryEntry;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.RemotePlayer;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
public class PlayerCorpseDeathHistoryScreen extends Screen {
   private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
   private static final int PREVIEW_WIDTH = 60;
   private static final int PREVIEW_HEIGHT = 90;
   private static final int PREVIEW_SCALE = 30;
   private final List<DeathHistoryEntry> entries;
   private int index;
   private Button previousButton;
   private Button nextButton;
   private boolean graveMarkersEnabled;
   private boolean canManageGraveMarkers;
   private int previewEntryIndex = -1;
   private PlayerCorpseDeathHistoryScreen.HistorySkinPlayer previewEntity;

   public PlayerCorpseDeathHistoryScreen(List<DeathHistoryEntry> entries, boolean graveMarkersEnabled, boolean canManageGraveMarkers) {
      super(Component.literal("Death History"));
      this.entries = entries;
      this.graveMarkersEnabled = graveMarkersEnabled;
      this.canManageGraveMarkers = canManageGraveMarkers;
   }

   protected void init() {
      int centerX = this.width / 2;
      int buttonY = this.height / 2 + 70;
      this.previousButton = (Button)this.addRenderableWidget(Button.builder(Component.literal("< Previous"), button -> {
         this.index--;
         this.updateButtons();
      }).bounds(centerX - 110, buttonY, 70, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("Items"), button -> {
         if (!this.entries.isEmpty()) {
            Minecraft.getInstance().setScreen(new DeathHistoryItemsScreen(this, this.entries.get(this.index)));
         }
      }).bounds(centerX - 35, buttonY, 70, 20).build());
      this.nextButton = (Button)this.addRenderableWidget(Button.builder(Component.literal("Next >"), button -> {
         this.index++;
         this.updateButtons();
      }).bounds(centerX + 40, buttonY, 70, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("Settings..."), button -> Minecraft.getInstance().setScreen(
         new PlayerCorpseSettingsScreen(this, this.graveMarkersEnabled, this.canManageGraveMarkers)
      )).bounds(centerX - 105, buttonY + 26, 100, 20).build());
      this.addRenderableWidget(Button.builder(Component.literal("Close"), button -> this.onClose()).bounds(centerX + 5, buttonY + 26, 100, 20).build());
      this.updateButtons();
   }

   private void updateButtons() {
      this.previousButton.active = this.index > 0;
      this.nextButton.active = this.index < this.entries.size() - 1;
   }

   public void updateGraveSettings(boolean enabled, boolean canManage) {
      this.graveMarkersEnabled = enabled;
      this.canManageGraveMarkers = canManage;
   }

   public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.render(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int top = this.height / 2 - 100;
      if (this.entries.isEmpty()) {
         guiGraphics.drawCenteredString(this.font, "No recorded deaths.", centerX, top + 20, 16777215);
      } else {
         DeathHistoryEntry entry = this.entries.get(this.index);
         int previewX1 = centerX - 30;
         int previewY1 = top;
         InventoryScreen.renderEntityInInventoryFollowsMouse(
            guiGraphics, previewX1, previewY1, previewX1 + 60, previewY1 + 90, 30, 0.0625F, mouseX, mouseY, this.previewEntity(entry)
         );
         int textY = top + 90 + 6;
         guiGraphics.drawCenteredString(this.font, DATE_FORMAT.format(Instant.ofEpochMilli(entry.timestampMillis())), centerX, textY, 11184810);
         guiGraphics.drawCenteredString(
            this.font, dimensionLabel(entry.position().dimensionId()) + " · " + formatCoords(entry.position()), centerX, textY + 12, 11184810
         );
         guiGraphics.drawCenteredString(this.font, entry.deathCause(), centerX, textY + 24, 13421772);
         guiGraphics.drawCenteredString(this.font, distanceLabel(entry.position()), centerX, textY + 36, 11184810);
         int buttonY = this.height / 2 + 70;
         guiGraphics.drawCenteredString(this.font, this.index + 1 + " / " + this.entries.size(), centerX, buttonY - 10, 8947848);
      }
   }

   private PlayerCorpseDeathHistoryScreen.HistorySkinPlayer previewEntity(DeathHistoryEntry entry) {
      if (this.previewEntity == null || this.previewEntryIndex != this.index) {
         GameProfile profile = new GameProfile(entry.playerUuid(), entry.playerName());
         ClientLevel level = Minecraft.getInstance().level;
         this.previewEntity = new PlayerCorpseDeathHistoryScreen.HistorySkinPlayer(level, profile);
         this.previewEntryIndex = this.index;
      }

      return this.previewEntity;
   }

   private static String dimensionLabel(String dimensionId) {
      return switch (dimensionId) {
         case "minecraft:overworld" -> "Overworld";
         case "minecraft:the_nether" -> "Nether";
         case "minecraft:the_end" -> "The End";
         default -> fallbackDimensionLabel(dimensionId);
      };
   }

   private static String fallbackDimensionLabel(String dimensionId) {
      int colon = dimensionId.indexOf(58);
      String path = colon >= 0 ? dimensionId.substring(colon + 1) : dimensionId;
      StringBuilder label = new StringBuilder();

      for (String word : path.split("_")) {
         if (!word.isEmpty()) {
            if (!label.isEmpty()) {
               label.append(' ');
            }

            label.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
         }
      }

      return label.isEmpty() ? path : label.toString();
   }

   private static String formatCoords(DeathHistoryEntry.Position position) {
      return String.format("%.0f, %.0f, %.0f", position.x(), position.y(), position.z());
   }

   private static String distanceLabel(DeathHistoryEntry.Position position) {
      LocalPlayer player = Minecraft.getInstance().player;
      if (player != null && player.level() != null) {
         String currentDimensionId = player.level().dimension().location().toString();
         if (!currentDimensionId.equals(position.dimensionId())) {
            return "Different dimension";
         }

         double dx = player.getX() - position.x();
         double dy = player.getY() - position.y();
         double dz = player.getZ() - position.z();
         double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
         return String.format("%.0f blocks away", distance);
      } else {
         return "";
      }
   }

   public boolean isPauseScreen() {
      return false;
   }

   private static final class HistorySkinPlayer extends RemotePlayer {
      private final GameProfile profile;

      HistorySkinPlayer(ClientLevel level, GameProfile profile) {
         super(level, profile);
         this.profile = profile;
      }

      public PlayerSkin getSkin() {
         return PlayerCorpseSkinResolver.resolveSkin(this.profile.getId(), this.profile.getName());
      }
   }
}
