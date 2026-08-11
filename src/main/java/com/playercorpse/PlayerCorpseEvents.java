package com.playercorpse;

import com.mojang.logging.LogUtils;
import com.playercorpse.entity.DeathCauseCategory;
import com.playercorpse.entity.PlayerCorpseEntity;
import com.playercorpse.registry.PlayerCorpseEntities;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingDropsEvent;
import org.slf4j.Logger;

public final class PlayerCorpseEvents {
   private static final int MAX_UPWARD_SEARCH = 16;
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final double LYING_BODY_HALF_LENGTH = 0.9;
   private static final Map<UUID, Map<ItemStack, Integer>> PENDING_ORIGINAL_SLOTS = new HashMap<>();
   private static final Map<UUID, ItemStack> PENDING_HELD_ITEMS = new HashMap<>();

   @SubscribeEvent
   public void onLivingDeath(LivingDeathEvent event) {
      if (event.getEntity() instanceof ServerPlayer player && !player.level().isClientSide()) {
         Inventory inventory = player.getInventory();
         Map<ItemStack, Integer> slotByStack = new IdentityHashMap<>();

         for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) {
               slotByStack.put(stack, i);
            }
         }

         PENDING_ORIGINAL_SLOTS.put(player.getUUID(), slotByStack);
         PENDING_HELD_ITEMS.put(player.getUUID(), player.getMainHandItem().copy());
      }
   }

   @SubscribeEvent
   public void onLivingDrops(LivingDropsEvent event) {
      if (!event.getEntity().level().isClientSide() && event.getEntity() instanceof ServerPlayer player) {
         Map var27 = PENDING_ORIGINAL_SLOTS.remove(player.getUUID());
         ItemStack heldItemAtDeath = PENDING_HELD_ITEMS.remove(player.getUUID());
         if ((Boolean)PlayerCorpseConfig.ENABLED.get() && !event.getSource().is(DamageTypes.FELL_OUT_OF_WORLD)) {
            List<ItemStack> drops = new ArrayList<>();
            List<Integer> dropOriginalSlots = new ArrayList<>();

            for (ItemEntity itemEntity : event.getDrops()) {
               ItemStack stack = itemEntity.getItem();
               if (!stack.isEmpty()) {
                  drops.add(stack.copy());
                  Integer originalSlot = var27 != null ? (Integer)var27.get(stack) : null;
                  dropOriginalSlots.add(originalSlot != null ? originalSlot : -1);
               }
            }

            if (!drops.isEmpty()) {
               ServerLevel level = player.serverLevel();
               BlockPos spawnPos = findSafeCorpsePos(level, player.blockPosition());
               float yaw = player.getYRot();
               float yawRadians = yaw * (float) (Math.PI / 180.0);
               double forwardX = -Mth.sin(yawRadians);
               double forwardZ = Mth.cos(yawRadians);
               double anchorX = spawnPos.getX() + 0.5 - forwardX * 0.9;
               double anchorZ = spawnPos.getZ() + 0.5 - forwardZ * 0.9;
               PlayerCorpseEntity corpse = new PlayerCorpseEntity((EntityType<? extends PlayerCorpseEntity>)PlayerCorpseEntities.PLAYER_CORPSE.get(), level);
               corpse.setPos(anchorX, spawnPos.getY(), anchorZ);
               corpse.anchorHere();
               corpse.setYRot(yaw);
               corpse.setYHeadRot(yaw);
               corpse.setYBodyRot(yaw);
               corpse.setOwner(player.getUUID(), player.getGameProfile().getName());
               corpse.setDeathCauseCategory(DeathCauseCategory.fromDamageSource(event.getSource()));
               LOGGER.info("[PlayerCorpse-DEBUG] corpse {} heldItemAtDeath={}", corpse.getId(), heldItemAtDeath);
               if (heldItemAtDeath != null && !heldItemAtDeath.isEmpty()) {
                  corpse.setHeldItemForRender(heldItemAtDeath);
               }

               int nextFallbackSlot = 0;

               for (int i = 0; i < drops.size(); i++) {
                  ItemStack stack = drops.get(i);
                  int equipmentIndex = PlayerCorpseEntity.equipmentContainerIndex(player.getEquipmentSlotForItem(stack));
                  if (equipmentIndex >= 0 && corpse.getEquipment().getItem(equipmentIndex).isEmpty()) {
                     corpse.getEquipment().setItem(equipmentIndex, stack);
                  } else {
                     int originalSlot = dropOriginalSlots.get(i);
                     int targetSlot = originalSlot >= 0 ? PlayerCorpseEntity.generalSlotForPlayerInventorySlot(originalSlot) : -1;
                     if (targetSlot < 0 || !corpse.getInventory().getItem(targetSlot).isEmpty()) {
                        while (nextFallbackSlot < 36 && !corpse.getInventory().getItem(nextFallbackSlot).isEmpty()) {
                           nextFallbackSlot++;
                        }

                        targetSlot = nextFallbackSlot;
                     }

                     if (targetSlot >= 36) {
                        ItemEntity overflow = new ItemEntity(level, anchorX, spawnPos.getY() + 0.5, anchorZ, stack);
                        level.addFreshEntity(overflow);
                     } else {
                        corpse.getInventory().setItem(targetSlot, stack);
                        // Recovery fix (2026-08-11): originalSlot is read from Inventory.getContainerSize()'s
                        // FULL 41-slot space (36 general + 4 armor + 1 offhand - confirmed against the real
                        // decompiled Inventory source, compartments = [items(36), armor(4), offhand(1)]), not
                        // just the 36-slot general range generalSlotForPlayerInventorySlot() validates above.
                        // A worn armor/offhand item (raw index 36-40) that gets displaced into THIS general
                        // branch - e.g. a spare item of the same equipment type claimed the corpse's equipment
                        // slot first - would otherwise store that raw 36-40 index as its "original slot".
                        // PlayerCorpseMenu.menuSlotForPlayerInventoryIndex() already treats >=36 as invalid
                        // (returns -1), but PlayerCorpseNetworking.restoreToOriginalSlots() only checked >= 0
                        // and called serverPlayer.getInventory().setItem(originalSlot, ...) directly - for a
                        // stored value in 36-40 that writes straight into the player's live armor/offhand slot
                        // instead of their general inventory. Clamp to -1 here so no consumer ever sees an
                        // out-of-range value in the first place.
                        corpse.setGeneralOriginalSlot(targetSlot, originalSlot >= 0 && originalSlot < 36 ? originalSlot : -1);
                     }
                  }
               }

               level.addFreshEntity(corpse);
               PlayerCorpseCorpseTracker.setActiveCorpse(player, level, corpse);
               String deathCause = event.getSource().getLocalizedDeathMessage(player).getString();
               PlayerCorpseDeathHistory.record(level, player, spawnPos, corpse, deathCause);
               event.getDrops().clear();
            }
         }
      }
   }

   private static BlockPos findSafeCorpsePos(ServerLevel level, BlockPos deathPos) {
      MutableBlockPos pos = deathPos.mutable();

      for (int i = 0; i < 16; i++) {
         if (!isSolid(level, pos)) {
            return pos.immutable();
         }

         pos.move(0, 1, 0);
      }

      return deathPos;
   }

   private static boolean isSolid(ServerLevel level, BlockPos pos) {
      return !level.getBlockState(pos).getCollisionShape(level, pos).isEmpty();
   }
}
