package com.playercorpse.menu;

import com.mojang.datafixers.util.Pair;
import com.playercorpse.entity.PlayerCorpseEntity;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

public class PlayerCorpseMenu extends AbstractContainerMenu {
   public static final int EQUIPMENT_SLOTS = PlayerCorpseEntity.EQUIPMENT_SIZE;
   public static final int GENERAL_SLOTS = 36;
   private static final ResourceLocation[] EQUIPMENT_EMPTY_ICONS = new ResourceLocation[]{
      InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
      InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
      InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
      InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS,
      InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD
   };
   private final Container equipment;
   private final Container general;
   private final int[] generalOriginalSlots;
   private static final int CORPSE_SLOTS_END = EQUIPMENT_SLOTS + 36;

   public PlayerCorpseMenu(MenuType<?> type, int containerId, Inventory playerInventory) {
      this(type, containerId, playerInventory, new SimpleContainer(EQUIPMENT_SLOTS), new SimpleContainer(36), null);
   }

   public PlayerCorpseMenu(MenuType<?> type, int containerId, Inventory playerInventory, Container equipment, Container general, int[] generalOriginalSlots) {
      super(type, containerId);
      checkContainerSize(equipment, EQUIPMENT_SLOTS);
      checkContainerSize(general, 36);
      this.equipment = equipment;
      this.general = general;
      this.generalOriginalSlots = generalOriginalSlots;
      equipment.startOpen(playerInventory.player);
      general.startOpen(playerInventory.player);
      int equipmentSlotY = 35;

      for (int i = 0; i < EQUIPMENT_SLOTS; i++) {
         EquipmentSlot equipmentSlot = PlayerCorpseEntity.equipmentSlotAt(i);
         ResourceLocation emptyIcon = EQUIPMENT_EMPTY_ICONS[i];
         int x = equipmentSlot == EquipmentSlot.OFFHAND ? 8 + i * 18 + 18 : 8 + i * 18;
         this.addSlot(
            (Slot)(equipmentSlot == EquipmentSlot.OFFHAND
               ? new PlayerCorpseMenu.OffhandSlot(equipment, i, x, equipmentSlotY, emptyIcon)
               : new PlayerCorpseMenu.ArmorPieceSlot(equipment, i, x, equipmentSlotY, equipmentSlot, emptyIcon, playerInventory.player))
         );
      }

      int mainStorageSlotY = 57;

      for (int row = 0; row < 3; row++) {
         for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(general, col + row * 9, 8 + col * 18, mainStorageSlotY + row * 18));
         }
      }

      int toolsSlotY = 115;
      int toolsSlotBaseIndex = 27;

      for (int col = 0; col < 9; col++) {
         this.addSlot(new Slot(general, toolsSlotBaseIndex + col, 8 + col * 18, toolsSlotY));
      }

      int mainInvY = 184;

      for (int row = 0; row < 3; row++) {
         for (int col = 0; col < 9; col++) {
            this.addSlot(new Slot(playerInventory, col + row * 9 + 9, 8 + col * 18, mainInvY + row * 18));
         }
      }

      int hotbarY = 242;

      for (int col = 0; col < 9; col++) {
         this.addSlot(new Slot(playerInventory, col, 8 + col * 18, hotbarY));
      }
   }

   public Container getEquipmentContainer() {
      return this.equipment;
   }

   public Container getGeneralContainer() {
      return this.general;
   }

   public int[] getGeneralOriginalSlots() {
      return this.generalOriginalSlots;
   }

   /**
    * Inverse of PlayerCorpseEntity.generalSlotForPlayerInventorySlot, but
    * mapping to THIS menu's own slot-list position rather than a corpse
    * general-container index - see the addSlot loops in the constructor:
    * the 3 main-inventory rows are added first (Inventory index 9-35, in
    * row-major order) immediately after CORPSE_SLOTS_END, then the hotbar
    * row last (Inventory index 0-8). player.getInventory()'s own index
    * space (0-8 hotbar, 9-35 main) is exactly what
    * generalOriginalSlots[] stores (see PlayerCorpseEvents/
    * PlayerCorpseEntity.setGeneralOriginalSlot) - this converts that stored
    * index into a slot position moveItemStackTo can target directly.
    */
   private static int menuSlotForPlayerInventoryIndex(int playerInventoryIndex) {
      if (playerInventoryIndex >= 0 && playerInventoryIndex < 9) {
         return CORPSE_SLOTS_END + 27 + playerInventoryIndex;
      } else {
         return playerInventoryIndex >= 9 && playerInventoryIndex < 36 ? CORPSE_SLOTS_END + (playerInventoryIndex - 9) : -1;
      }
   }

   public ItemStack quickMoveStack(Player player, int index) {
      ItemStack result = ItemStack.EMPTY;
      Slot slot = (Slot)this.slots.get(index);
      if (slot != null && slot.hasItem()) {
         ItemStack original = slot.getItem();
         result = original.copy();
         if (index < CORPSE_SLOTS_END) {
            // Slot-preserving recovery: an item pulled from the corpse's general container tries its
            // death-time original player-inventory slot FIRST (empty -> placed there; occupied by a
            // compatible stack with room -> merges there; anything else -> falls through untouched to
            // the normal first-available-slot search below, exactly like before this existed).
            // Equipment slots have no "original slot" concept (each equipment index already has one
            // fixed meaning), so this only applies to the general range.
            if (index >= EQUIPMENT_SLOTS && this.generalOriginalSlots != null) {
               int originalPlayerSlot = this.generalOriginalSlots[index - EQUIPMENT_SLOTS];
               int targetMenuSlot = originalPlayerSlot >= 0 ? menuSlotForPlayerInventoryIndex(originalPlayerSlot) : -1;
               if (targetMenuSlot >= 0) {
                  this.moveItemStackTo(original, targetMenuSlot, targetMenuSlot + 1, false);
               }
            }

            if (!original.isEmpty() && !this.moveItemStackTo(original, CORPSE_SLOTS_END, this.slots.size(), true)) {
               return ItemStack.EMPTY;
            }
         } else if (!this.moveItemStackTo(original, EQUIPMENT_SLOTS, CORPSE_SLOTS_END, false)) {
            return ItemStack.EMPTY;
         }

         if (original.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
         } else {
            slot.setChanged();
         }

         return result;
      } else {
         return result;
      }
   }

   public boolean stillValid(Player player) {
      return this.equipment.stillValid(player) && this.general.stillValid(player);
   }

   public void removed(Player player) {
      super.removed(player);
      this.equipment.stopOpen(player);
      this.general.stopOpen(player);
   }

   private static class ArmorPieceSlot extends Slot {
      private final EquipmentSlot equipmentSlot;
      private final ResourceLocation emptyIcon;
      private final Player contextPlayer;

      ArmorPieceSlot(Container container, int slotIndex, int x, int y, EquipmentSlot equipmentSlot, ResourceLocation emptyIcon, Player contextPlayer) {
         super(container, slotIndex, x, y);
         this.equipmentSlot = equipmentSlot;
         this.emptyIcon = emptyIcon;
         this.contextPlayer = contextPlayer;
      }

      public boolean mayPlace(ItemStack stack) {
         return stack.canEquip(this.equipmentSlot, this.contextPlayer);
      }

      public int getMaxStackSize() {
         return 1;
      }

      public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
         return Pair.of(InventoryMenu.BLOCK_ATLAS, this.emptyIcon);
      }
   }

   private static class OffhandSlot extends Slot {
      private final ResourceLocation emptyIcon;

      OffhandSlot(Container container, int slotIndex, int x, int y, ResourceLocation emptyIcon) {
         super(container, slotIndex, x, y);
         this.emptyIcon = emptyIcon;
      }

      public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
         return Pair.of(InventoryMenu.BLOCK_ATLAS, this.emptyIcon);
      }
   }
}
