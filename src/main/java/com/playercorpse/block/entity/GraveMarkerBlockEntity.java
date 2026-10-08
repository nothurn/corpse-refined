package com.playercorpse.block.entity;

import com.playercorpse.entity.PlayerCorpseEntity;
import com.playercorpse.menu.PlayerCorpseMenu;
import com.playercorpse.registry.PlayerCorpseBlockEntities;
import com.playercorpse.registry.PlayerCorpseBlocks;
import com.playercorpse.registry.PlayerCorpseMenus;
import java.util.Arrays;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class GraveMarkerBlockEntity extends BlockEntity implements MenuProvider {
   private final SimpleContainer inventory = this.createBoundContainer(PlayerCorpseEntity.CONTAINER_SIZE);
   private final SimpleContainer equipment = this.createBoundContainer(PlayerCorpseEntity.EQUIPMENT_SIZE);
   private final int[] generalOriginalSlots = new int[PlayerCorpseEntity.CONTAINER_SIZE];

   public GraveMarkerBlockEntity(BlockPos pos, BlockState state) {
      super(PlayerCorpseBlockEntities.GRAVE_MARKER.get(), pos, state);
      Arrays.fill(this.generalOriginalSlots, -1);
      this.inventory.addListener(container -> this.setChanged());
      this.equipment.addListener(container -> this.setChanged());
   }

   private SimpleContainer createBoundContainer(int size) {
      return new SimpleContainer(size) {
         @Override
         public boolean stillValid(Player player) {
            return GraveMarkerBlockEntity.this.level != null
               && !GraveMarkerBlockEntity.this.isRemoved()
               && GraveMarkerBlockEntity.this.level.getBlockEntity(GraveMarkerBlockEntity.this.worldPosition) == GraveMarkerBlockEntity.this
               && GraveMarkerBlockEntity.this.level.getBlockState(GraveMarkerBlockEntity.this.worldPosition).is(PlayerCorpseBlocks.GRAVE_MARKER.get())
               && player.distanceToSqr(
                  GraveMarkerBlockEntity.this.worldPosition.getX() + 0.5,
                  GraveMarkerBlockEntity.this.worldPosition.getY() + 0.5,
                  GraveMarkerBlockEntity.this.worldPosition.getZ() + 0.5
               ) <= 64.0;
         }
      };
   }

   public void transferFrom(SimpleContainer sourceInventory, SimpleContainer sourceEquipment, int[] sourceOriginalSlots) {
      for (int i = 0; i < sourceInventory.getContainerSize(); i++) {
         this.inventory.setItem(i, sourceInventory.getItem(i));
      }

      for (int i = 0; i < sourceEquipment.getContainerSize(); i++) {
         this.equipment.setItem(i, sourceEquipment.getItem(i));
      }

      System.arraycopy(sourceOriginalSlots, 0, this.generalOriginalSlots, 0, Math.min(sourceOriginalSlots.length, this.generalOriginalSlots.length));
      sourceInventory.clearContent();
      sourceEquipment.clearContent();
      this.setChanged();
   }

   public void dropContents() {
      if (this.level != null && !this.level.isClientSide()) {
         Containers.dropContents(this.level, this.worldPosition, this.inventory);
         Containers.dropContents(this.level, this.worldPosition, this.equipment);
         this.inventory.clearContent();
         this.equipment.clearContent();
         this.setChanged();
      }
   }

   @Override
   public Component getDisplayName() {
      return Component.literal("Grave");
   }

   @Override
   public AbstractContainerMenu createMenu(int containerId, Inventory playerInventory, Player player) {
      return new PlayerCorpseMenu(
         PlayerCorpseMenus.CORPSE_MENU.get(), containerId, playerInventory, this.equipment, this.inventory, this.generalOriginalSlots
      );
   }

   @Override
   protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
      super.saveAdditional(tag, registries);
      tag.put("Items", this.inventory.createTag(registries));
      tag.put("Equipment", this.equipment.createTag(registries));
      tag.putIntArray("GeneralOriginalSlots", this.generalOriginalSlots);
   }

   @Override
   protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
      super.loadAdditional(tag, registries);
      this.inventory.fromTag(tag.getList("Items", 10), registries);
      this.equipment.fromTag(tag.getList("Equipment", 10), registries);
      int[] savedOriginalSlots = tag.getIntArray("GeneralOriginalSlots");
      if (savedOriginalSlots.length == this.generalOriginalSlots.length) {
         System.arraycopy(savedOriginalSlots, 0, this.generalOriginalSlots, 0, this.generalOriginalSlots.length);
      }

   }
}
