package com.playercorpse.entity;

import com.mojang.logging.LogUtils;
import com.playercorpse.CorpsePlacement;
import com.playercorpse.menu.PlayerCorpseMenu;
import com.playercorpse.registry.PlayerCorpseMenus;
import java.util.Arrays;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.SynchedEntityData.Builder;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

/**
 * Left behind when a PlayerCorpseEntity finishes the ASH phase still holding
 * loot (RECOVERY.md section 7). Extends LivingEntity, same as the corpse
 * itself, so it never runs despawn checks and persists for free without a
 * SavedData/BlockEntity of its own - no gravity, invulnerable, anchored.
 * Reuses PlayerCorpseMenu/its screen/networking completely unchanged (see
 * that class - it only ever takes plain Container instances plus
 * PlayerCorpseEntity's static equipmentSlotAt/equipmentContainerIndex
 * helpers, never anything corpse-instance-specific), so this opens the same
 * loot GUI for free.
 */
public class PlayerGraveMarkerEntity extends LivingEntity {
   private static final Logger LOGGER = LogUtils.getLogger();
   private final SimpleContainer inventory = new SimpleContainer(PlayerCorpseEntity.CONTAINER_SIZE);
   private final SimpleContainer equipment = new SimpleContainer(PlayerCorpseEntity.EQUIPMENT_SIZE);
   private final int[] generalOriginalSlot = new int[PlayerCorpseEntity.CONTAINER_SIZE];
   private boolean placementValidated;

   public PlayerGraveMarkerEntity(EntityType<? extends PlayerGraveMarkerEntity> type, Level level) {
      super(type, level);
      Arrays.fill(this.generalOriginalSlot, -1);
      this.setNoGravity(true);
      this.noPhysics = true;
      this.blocksBuilding = false;
   }

   public void tick() {
      super.tick();
      if (!this.placementValidated && this.level() instanceof ServerLevel serverLevel) {
         this.placementValidated = true;
         Vec3 previousPosition = this.position();
         if (!CorpsePlacement.isSafeAndSupported(serverLevel, this)) {
            CorpsePlacement.findSafePosition(serverLevel, this, previousPosition).ifPresentOrElse(relocatedPosition -> {
               this.setPos(relocatedPosition.x, relocatedPosition.y, relocatedPosition.z);
               LOGGER.warn(
                  "[PlayerCorpse] Relocated loaded grave marker {} from unsafe position {} to {}",
                  this.getId(), previousPosition, relocatedPosition
               );
            }, () -> LOGGER.error(
               "[PlayerCorpse] Could not relocate grave marker {} from unsafe position {}", this.getId(), previousPosition
            ));
         }
      }

   }

   /**
    * Copies both source containers' contents into this marker's own, then
    * clears the source containers - RECOVERY.md: "überträgt beide Container
    * und leert danach die der Leiche, um doppeltes Droppen zu verhindern"
    * (clearing the source prevents the corpse's own remove() ground-drop
    * fallback from ALSO dropping the same items a second time once it
    * discards immediately after this runs).
    */
   public void transferFrom(SimpleContainer sourceInventory, SimpleContainer sourceEquipment, int[] sourceGeneralOriginalSlots) {
      for (int i = 0; i < sourceInventory.getContainerSize(); i++) {
         this.inventory.setItem(i, sourceInventory.getItem(i));
      }

      for (int i = 0; i < sourceEquipment.getContainerSize(); i++) {
         this.equipment.setItem(i, sourceEquipment.getItem(i));
      }

      System.arraycopy(
         sourceGeneralOriginalSlots, 0, this.generalOriginalSlot, 0, Math.min(sourceGeneralOriginalSlots.length, this.generalOriginalSlot.length)
      );
      sourceInventory.clearContent();
      sourceEquipment.clearContent();
   }

   private boolean isInventoryEmpty() {
      return this.inventory.isEmpty() && this.equipment.isEmpty();
   }

   public void knockback(double strength, double x, double z) {
   }

   public boolean isPushedByFluid() {
      return false;
   }

   public boolean isInvulnerableTo(DamageSource damageSource) {
      return true;
   }

   public boolean isPickable() {
      return true;
   }

   public boolean isPushable() {
      return false;
   }

   protected void doPush(Entity entity) {
   }

   public InteractionResult interact(Player player, InteractionHand hand) {
      if (hand != InteractionHand.MAIN_HAND) {
         return InteractionResult.PASS;
      } else {
         if (!this.level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
            Component title = Component.literal("Grave");
            serverPlayer.openMenu(
               new SimpleMenuProvider(
                  (containerId, playerInventory, p) -> new PlayerCorpseMenu(
                     (MenuType<?>)PlayerCorpseMenus.CORPSE_MENU.get(), containerId, playerInventory, this.equipment, this.inventory, this.generalOriginalSlot
                  ),
                  title
               )
            );
         }

         return InteractionResult.sidedSuccess(this.level().isClientSide());
      }
   }

   public void remove(RemovalReason reason) {
      if (!this.level().isClientSide() && !this.isInventoryEmpty()) {
         this.dropContainer(this.inventory);
         this.dropContainer(this.equipment);
      }

      super.remove(reason);
   }

   private void dropContainer(SimpleContainer container) {
      for (int i = 0; i < container.getContainerSize(); i++) {
         ItemStack stack = container.getItem(i);
         if (!stack.isEmpty()) {
            this.level().addFreshEntity(new ItemEntity(this.level(), this.getX(), this.getY(), this.getZ(), stack));
         }
      }

      container.clearContent();
   }

   public Iterable<ItemStack> getArmorSlots() {
      return List.of();
   }

   public ItemStack getItemBySlot(EquipmentSlot slot) {
      return ItemStack.EMPTY;
   }

   public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
   }

   public HumanoidArm getMainArm() {
      return HumanoidArm.RIGHT;
   }

   protected void defineSynchedData(Builder builder) {
      super.defineSynchedData(builder);
   }

   public void addAdditionalSaveData(CompoundTag compound) {
      compound.put("Items", this.inventory.createTag(this.registryAccess()));
      compound.put("Equipment", this.equipment.createTag(this.registryAccess()));
      compound.putIntArray("GeneralOriginalSlots", this.generalOriginalSlot);
   }

   public void readAdditionalSaveData(CompoundTag compound) {
      this.inventory.fromTag(compound.getList("Items", 10), this.registryAccess());
      if (compound.contains("Equipment")) {
         this.equipment.fromTag(compound.getList("Equipment", 10), this.registryAccess());
      }

      int[] savedOriginalSlots = compound.getIntArray("GeneralOriginalSlots");
      if (savedOriginalSlots.length == PlayerCorpseEntity.CONTAINER_SIZE) {
         System.arraycopy(savedOriginalSlots, 0, this.generalOriginalSlot, 0, PlayerCorpseEntity.CONTAINER_SIZE);
      }
   }
}
