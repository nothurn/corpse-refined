package com.playercorpse.client;

import com.playercorpse.PlayerCorpseNetworking;
import com.playercorpse.client.renderer.GraveMarkerRenderer;
import com.playercorpse.client.renderer.PlayerCorpseRenderer;
import com.playercorpse.client.screen.PlayerCorpseDeathHistoryScreen;
import com.playercorpse.client.screen.PlayerCorpseSettingsScreen;
import com.playercorpse.client.screen.PlayerCorpseScreen;
import com.playercorpse.registry.PlayerCorpseEntities;
import com.playercorpse.registry.PlayerCorpseMenus;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterLayerDefinitions;
import net.neoforged.neoforge.client.event.EntityRenderersEvent.RegisterRenderers;

public final class PlayerCorpseClientEvents {
   private PlayerCorpseClientEvents() {
   }

   public static void onRegisterRenderers(RegisterRenderers event) {
      event.registerEntityRenderer((EntityType)PlayerCorpseEntities.PLAYER_CORPSE.get(), PlayerCorpseRenderer::new);
      event.registerEntityRenderer((EntityType)PlayerCorpseEntities.GRAVE_MARKER.get(), GraveMarkerRenderer::new);
   }

   public static void onRegisterLayerDefinitions(RegisterLayerDefinitions event) {
      event.registerLayerDefinition(GraveMarkerRenderer.GRAVE_MARKER_LAYER, GraveMarkerRenderer::createBodyLayer);
   }

   public static void onRegisterMenuScreens(RegisterMenuScreensEvent event) {
      event.register((MenuType)PlayerCorpseMenus.CORPSE_MENU.get(), PlayerCorpseScreen::new);
   }

   public static void handleDeathHistorySync(PlayerCorpseNetworking.DeathHistorySyncPayload payload) {
      Minecraft.getInstance().setScreen(
         new PlayerCorpseDeathHistoryScreen(payload.entries(), payload.graveMarkersEnabled(), payload.canManageGraveMarkers())
      );
   }

   public static void handleGraveSettingsSync(PlayerCorpseNetworking.GraveSettingsSyncPayload payload) {
      if (Minecraft.getInstance().screen instanceof PlayerCorpseSettingsScreen settingsScreen) {
         settingsScreen.updateGraveSettings(payload.enabled(), payload.canManage());
      } else if (Minecraft.getInstance().screen instanceof PlayerCorpseDeathHistoryScreen historyScreen) {
         historyScreen.updateGraveSettings(payload.enabled(), payload.canManage());
      }
   }
}
