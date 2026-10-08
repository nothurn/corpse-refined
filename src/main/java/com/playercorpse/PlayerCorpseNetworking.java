package com.playercorpse;

import com.playercorpse.client.PlayerCorpseClientEvents;
import com.playercorpse.client.PlayerCorpseHudArrow;
import com.playercorpse.entity.PlayerCorpseEntity;
import com.playercorpse.history.DeathHistoryEntry;
import com.playercorpse.history.PlayerCorpseDeathHistoryData;
import com.playercorpse.menu.PlayerCorpseMenu;
import com.playercorpse.registry.PlayerCorpseMenus;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload.Type;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public final class PlayerCorpseNetworking {
   private PlayerCorpseNetworking() {
   }

   public static void onRegisterPayloadHandlers(RegisterPayloadHandlersEvent event) {
      PayloadRegistrar registrar = event.registrar("2");
      registrar.playToServer(
         PlayerCorpseNetworking.TransferItemsPayload.TYPE,
         PlayerCorpseNetworking.TransferItemsPayload.STREAM_CODEC,
         PlayerCorpseNetworking::handleTransferItems
      );
      registrar.playToServer(
         PlayerCorpseNetworking.RequestDeathHistoryPayload.TYPE,
         PlayerCorpseNetworking.RequestDeathHistoryPayload.STREAM_CODEC,
         PlayerCorpseNetworking::handleRequestDeathHistory
      );
      registrar.playToClient(
         PlayerCorpseNetworking.DeathHistorySyncPayload.TYPE,
         PlayerCorpseNetworking.DeathHistorySyncPayload.STREAM_CODEC,
         (payload, context) -> PlayerCorpseClientEvents.handleDeathHistorySync(payload)
      );
      registrar.playToClient(
         PlayerCorpseNetworking.CorpseTrackerSyncPayload.TYPE,
         PlayerCorpseNetworking.CorpseTrackerSyncPayload.STREAM_CODEC,
         (payload, context) -> PlayerCorpseHudArrow.handleCorpseTrackerSync(payload)
      );
      registrar.playToServer(
         PlayerCorpseNetworking.SetGraveMarkersEnabledPayload.TYPE,
         PlayerCorpseNetworking.SetGraveMarkersEnabledPayload.STREAM_CODEC,
         PlayerCorpseNetworking::handleSetGraveMarkersEnabled
      );
      registrar.playToClient(
         PlayerCorpseNetworking.GraveSettingsSyncPayload.TYPE,
         PlayerCorpseNetworking.GraveSettingsSyncPayload.STREAM_CODEC,
         (payload, context) -> PlayerCorpseClientEvents.handleGraveSettingsSync(payload)
      );
   }

   private static void handleRequestDeathHistory(PlayerCorpseNetworking.RequestDeathHistoryPayload payload, IPayloadContext context) {
      if (context.player() instanceof ServerPlayer serverPlayer) {
         List var4 = PlayerCorpseDeathHistoryData.get(serverPlayer.serverLevel()).getEntries(serverPlayer.getUUID());
         PacketDistributor.sendToPlayer(
            serverPlayer,
            new PlayerCorpseNetworking.DeathHistorySyncPayload(
               var4, (Boolean)PlayerCorpseConfig.GRAVE_MARKERS_ENABLED.get(), canManageGraveMarkers(serverPlayer)
            ),
            new CustomPacketPayload[0]
         );
      }
   }

   private static void handleSetGraveMarkersEnabled(
      PlayerCorpseNetworking.SetGraveMarkersEnabledPayload payload, IPayloadContext context
   ) {
      if (context.player() instanceof ServerPlayer serverPlayer) {
         if (canManageGraveMarkers(serverPlayer)) {
            PlayerCorpseConfig.GRAVE_MARKERS_ENABLED.set(payload.enabled());
            PlayerCorpseConfig.SPEC.save();
         }

         PacketDistributor.sendToPlayer(
            serverPlayer,
            new PlayerCorpseNetworking.GraveSettingsSyncPayload(
               (Boolean)PlayerCorpseConfig.GRAVE_MARKERS_ENABLED.get(), canManageGraveMarkers(serverPlayer)
            ),
            new CustomPacketPayload[0]
         );
      }
   }

   private static boolean canManageGraveMarkers(ServerPlayer player) {
      return player.hasPermissions(2);
   }

   private static void handleTransferItems(PlayerCorpseNetworking.TransferItemsPayload payload, IPayloadContext context) {
      if (context.player() instanceof ServerPlayer serverPlayer) {
         if (serverPlayer.containerMenu.getType() == PlayerCorpseMenus.CORPSE_MENU.get() && serverPlayer.containerMenu instanceof PlayerCorpseMenu corpseMenu) {
            autoEquip(serverPlayer, corpseMenu.getEquipmentContainer());
            transferAllInto(serverPlayer, corpseMenu.getEquipmentContainer());
            restoreToOriginalSlots(serverPlayer, corpseMenu.getGeneralContainer(), corpseMenu.getGeneralOriginalSlots());
            transferAllInto(serverPlayer, corpseMenu.getGeneralContainer());
         }
      }
   }

   private static void autoEquip(ServerPlayer serverPlayer, Container equipmentContainer) {
      for (int i = 0; i < PlayerCorpseEntity.EQUIPMENT_SIZE; i++) {
         ItemStack stack = equipmentContainer.getItem(i);
         if (!stack.isEmpty()) {
            EquipmentSlot equipmentSlot = PlayerCorpseEntity.equipmentSlotAt(i);
            if (serverPlayer.getItemBySlot(equipmentSlot).isEmpty()) {
               serverPlayer.setItemSlot(equipmentSlot, stack);
               equipmentContainer.setItem(i, ItemStack.EMPTY);
            }
         }
      }

      equipmentContainer.setChanged();
   }

   /**
    * Bulk-transfer counterpart of PlayerCorpseMenu's single-click
    * quickMoveStack slot-preservation - tries each item's original
    * player-inventory slot first, same as the shift-click path, before the
    * generic transferAllInto() fallback below handles whatever's left.
    *
    * originalSlot < 36 guard (2026-08-11): serverPlayer.getInventory() is
    * the vanilla Inventory, whose getItem/setItem cover its FULL 41-slot
    * combined space (36 general + 4 armor + 1 offhand - confirmed against
    * the decompiled source), not just the 36-slot general range this
    * corpse's own general container occupies. Without this check, a stored
    * originalSlot of 36-40 (possible before the PlayerCorpseEvents fix that
    * now clamps it at the source - kept here too in case NBT-loaded data
    * from before that fix is still on disk) would write straight into the
    * player's live armor/offhand slot instead of their general inventory.
    */
   private static void restoreToOriginalSlots(ServerPlayer serverPlayer, Container generalContainer, int[] originalSlots) {
      if (originalSlots != null) {
         for (int corpseSlot = 0; corpseSlot < generalContainer.getContainerSize(); corpseSlot++) {
            ItemStack stack = generalContainer.getItem(corpseSlot);
            if (!stack.isEmpty()) {
               int originalSlot = originalSlots[corpseSlot];
               if (originalSlot >= 0 && originalSlot < 36 && serverPlayer.getInventory().getItem(originalSlot).isEmpty()) {
                  serverPlayer.getInventory().setItem(originalSlot, stack);
                  generalContainer.setItem(corpseSlot, ItemStack.EMPTY);
               }
            }
         }

         generalContainer.setChanged();
      }
   }

   private static void transferAllInto(ServerPlayer serverPlayer, Container container) {
      for (int slot = 0; slot < container.getContainerSize(); slot++) {
         ItemStack stack = container.getItem(slot);
         if (!stack.isEmpty()) {
            serverPlayer.getInventory().add(stack);
            if (stack.isEmpty()) {
               container.setItem(slot, ItemStack.EMPTY);
            }
         }
      }

      container.setChanged();
   }

   public record CorpseTrackerSyncPayload(boolean active, String dimensionId, double x, double y, double z) implements CustomPacketPayload {
      public static final Type<PlayerCorpseNetworking.CorpseTrackerSyncPayload> TYPE = new Type(
         ResourceLocation.fromNamespaceAndPath("playercorpse", "corpse_tracker_sync")
      );
      public static final StreamCodec<RegistryFriendlyByteBuf, PlayerCorpseNetworking.CorpseTrackerSyncPayload> STREAM_CODEC = StreamCodec.composite(
         ByteBufCodecs.BOOL,
         PlayerCorpseNetworking.CorpseTrackerSyncPayload::active,
         ByteBufCodecs.STRING_UTF8,
         PlayerCorpseNetworking.CorpseTrackerSyncPayload::dimensionId,
         ByteBufCodecs.DOUBLE,
         PlayerCorpseNetworking.CorpseTrackerSyncPayload::x,
         ByteBufCodecs.DOUBLE,
         PlayerCorpseNetworking.CorpseTrackerSyncPayload::y,
         ByteBufCodecs.DOUBLE,
         PlayerCorpseNetworking.CorpseTrackerSyncPayload::z,
         PlayerCorpseNetworking.CorpseTrackerSyncPayload::new
      );

      public Type<PlayerCorpseNetworking.CorpseTrackerSyncPayload> type() {
         return TYPE;
      }
   }

   public record DeathHistorySyncPayload(
      List<DeathHistoryEntry> entries, boolean graveMarkersEnabled, boolean canManageGraveMarkers
   ) implements CustomPacketPayload {
      public static final Type<PlayerCorpseNetworking.DeathHistorySyncPayload> TYPE = new Type(
         ResourceLocation.fromNamespaceAndPath("playercorpse", "death_history_sync")
      );
      public static final StreamCodec<RegistryFriendlyByteBuf, PlayerCorpseNetworking.DeathHistorySyncPayload> STREAM_CODEC = StreamCodec.composite(
         ByteBufCodecs.collection(ArrayList::new, DeathHistoryEntry.STREAM_CODEC),
         PlayerCorpseNetworking.DeathHistorySyncPayload::entries,
         ByteBufCodecs.BOOL,
         PlayerCorpseNetworking.DeathHistorySyncPayload::graveMarkersEnabled,
         ByteBufCodecs.BOOL,
         PlayerCorpseNetworking.DeathHistorySyncPayload::canManageGraveMarkers,
         PlayerCorpseNetworking.DeathHistorySyncPayload::new
      );

      public Type<PlayerCorpseNetworking.DeathHistorySyncPayload> type() {
         return TYPE;
      }
   }

   public record RequestDeathHistoryPayload() implements CustomPacketPayload {
      public static final Type<PlayerCorpseNetworking.RequestDeathHistoryPayload> TYPE = new Type(
         ResourceLocation.fromNamespaceAndPath("playercorpse", "request_death_history")
      );
      public static final StreamCodec<RegistryFriendlyByteBuf, PlayerCorpseNetworking.RequestDeathHistoryPayload> STREAM_CODEC = StreamCodec.unit(
         new PlayerCorpseNetworking.RequestDeathHistoryPayload()
      );

      public Type<PlayerCorpseNetworking.RequestDeathHistoryPayload> type() {
         return TYPE;
      }
   }

   public record TransferItemsPayload() implements CustomPacketPayload {
      public static final Type<PlayerCorpseNetworking.TransferItemsPayload> TYPE = new Type(
         ResourceLocation.fromNamespaceAndPath("playercorpse", "transfer_items")
      );
      public static final StreamCodec<RegistryFriendlyByteBuf, PlayerCorpseNetworking.TransferItemsPayload> STREAM_CODEC = StreamCodec.unit(
         new PlayerCorpseNetworking.TransferItemsPayload()
      );

      public Type<PlayerCorpseNetworking.TransferItemsPayload> type() {
         return TYPE;
      }
   }

   public record SetGraveMarkersEnabledPayload(boolean enabled) implements CustomPacketPayload {
      public static final Type<PlayerCorpseNetworking.SetGraveMarkersEnabledPayload> TYPE = new Type(
         ResourceLocation.fromNamespaceAndPath("playercorpse", "set_grave_markers_enabled")
      );
      public static final StreamCodec<RegistryFriendlyByteBuf, PlayerCorpseNetworking.SetGraveMarkersEnabledPayload> STREAM_CODEC = StreamCodec.composite(
         ByteBufCodecs.BOOL,
         PlayerCorpseNetworking.SetGraveMarkersEnabledPayload::enabled,
         PlayerCorpseNetworking.SetGraveMarkersEnabledPayload::new
      );

      public Type<PlayerCorpseNetworking.SetGraveMarkersEnabledPayload> type() {
         return TYPE;
      }
   }

   public record GraveSettingsSyncPayload(boolean enabled, boolean canManage) implements CustomPacketPayload {
      public static final Type<PlayerCorpseNetworking.GraveSettingsSyncPayload> TYPE = new Type(
         ResourceLocation.fromNamespaceAndPath("playercorpse", "grave_settings_sync")
      );
      public static final StreamCodec<RegistryFriendlyByteBuf, PlayerCorpseNetworking.GraveSettingsSyncPayload> STREAM_CODEC = StreamCodec.composite(
         ByteBufCodecs.BOOL,
         PlayerCorpseNetworking.GraveSettingsSyncPayload::enabled,
         ByteBufCodecs.BOOL,
         PlayerCorpseNetworking.GraveSettingsSyncPayload::canManage,
         PlayerCorpseNetworking.GraveSettingsSyncPayload::new
      );

      public Type<PlayerCorpseNetworking.GraveSettingsSyncPayload> type() {
         return TYPE;
      }
   }
}
