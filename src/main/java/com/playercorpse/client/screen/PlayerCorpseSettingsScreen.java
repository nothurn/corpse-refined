package com.playercorpse.client.screen;

import com.playercorpse.PlayerCorpseNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/** Small, extensible settings hub opened from Death History. */
@OnlyIn(Dist.CLIENT)
public final class PlayerCorpseSettingsScreen extends Screen {
   private final PlayerCorpseDeathHistoryScreen parent;
   private boolean graveMarkersEnabled;
   private boolean canManageGraveMarkers;
   private boolean confirmDisable;
   private Button graveMarkersButton;

   public PlayerCorpseSettingsScreen(
      PlayerCorpseDeathHistoryScreen parent, boolean graveMarkersEnabled, boolean canManageGraveMarkers
   ) {
      super(Component.literal("Corpse Settings"));
      this.parent = parent;
      this.graveMarkersEnabled = graveMarkersEnabled;
      this.canManageGraveMarkers = canManageGraveMarkers;
   }

   @Override
   protected void init() {
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      this.graveMarkersButton = this.addRenderableWidget(Button.builder(this.graveButtonLabel(), button -> this.toggleGraves())
         .bounds(centerX - 90, centerY - 10, 180, 20)
         .build());
      this.addRenderableWidget(Button.builder(Component.literal("Back"), button -> this.onClose())
         .bounds(centerX - 50, centerY + 70, 100, 20)
         .build());
      this.updateButtons();
   }

   private void toggleGraves() {
      if (this.graveMarkersEnabled && !this.confirmDisable) {
         this.confirmDisable = true;
         this.updateButtons();
         return;
      }

      boolean requestedValue = !this.graveMarkersEnabled;
      this.confirmDisable = false;
      this.updateButtons();
      PacketDistributor.sendToServer(new PlayerCorpseNetworking.SetGraveMarkersEnabledPayload(requestedValue));
   }

   private void updateButtons() {
      if (this.graveMarkersButton != null) {
         this.graveMarkersButton.active = this.canManageGraveMarkers;
         this.graveMarkersButton.setMessage(this.graveButtonLabel());
      }
   }

   private Component graveButtonLabel() {
      if (this.confirmDisable) {
         return Component.literal("CONFIRM DISABLE").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
      }

      return Component.literal("Grave markers: " + (this.graveMarkersEnabled ? "ON" : "OFF"))
         .withStyle(this.graveMarkersEnabled ? ChatFormatting.GREEN : ChatFormatting.GRAY);
   }

   public void updateGraveSettings(boolean enabled, boolean canManage) {
      this.graveMarkersEnabled = enabled;
      this.canManageGraveMarkers = canManage;
      this.confirmDisable = false;
      this.parent.updateGraveSettings(enabled, canManage);
      this.updateButtons();
   }

   @Override
   public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
      super.render(guiGraphics, mouseX, mouseY, partialTick);
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      guiGraphics.drawCenteredString(this.font, this.title, centerX, centerY - 72, 16777215);
      guiGraphics.drawCenteredString(this.font, "Grave markers", centerX, centerY - 42, 16777215);
      guiGraphics.drawCenteredString(this.font, "Creates a permanent, breakable grave after final decay.", centerX, centerY - 30, 11184810);

      if (this.confirmDisable) {
         guiGraphics.drawCenteredString(this.font, "DISABLE GRAVE CREATION?", centerX, centerY + 18, 16733525);
         guiGraphics.drawCenteredString(this.font, "Future corpses drop remaining items instead.", centerX, centerY + 30, 16755200);
         guiGraphics.drawCenteredString(this.font, "Existing graves always remain until broken.", centerX, centerY + 42, 16755200);
      } else {
         int infoColor = this.canManageGraveMarkers ? 8947848 : 16755200;
         String info = this.canManageGraveMarkers
            ? "Server setting. Existing graves never disappear automatically."
            : "Server setting (operator only). Existing graves never disappear automatically.";
         guiGraphics.drawCenteredString(this.font, info, centerX, centerY + 24, infoColor);
      }
   }

   @Override
   public void onClose() {
      Minecraft.getInstance().setScreen(this.parent);
   }

   @Override
   public boolean isPauseScreen() {
      return false;
   }
}
