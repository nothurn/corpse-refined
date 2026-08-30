package com.playercorpse;

import com.playercorpse.entity.PlayerCorpseEntity;
import com.playercorpse.history.DeathHistoryEntry;
import com.playercorpse.history.PlayerCorpseDeathHistoryData;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

final class PlayerCorpseDeathHistory {
   private PlayerCorpseDeathHistory() {
   }

   static void record(ServerLevel level, ServerPlayer player, PlayerCorpseEntity corpse, String deathCause) {
      List<ItemStack> items = new ArrayList<>();

      for (int i = 0; i < corpse.getEquipment().getContainerSize(); i++) {
         ItemStack stack = corpse.getEquipment().getItem(i);
         if (!stack.isEmpty()) {
            items.add(stack.copy());
         }
      }

      for (int i = 0; i < corpse.getInventory().getContainerSize(); i++) {
         ItemStack stack = corpse.getInventory().getItem(i);
         if (!stack.isEmpty()) {
            items.add(stack.copy());
         }
      }

      DeathHistoryEntry.Position position = new DeathHistoryEntry.Position(
         level.dimension().location().toString(), corpse.getX(), corpse.getY(), corpse.getZ()
      );
      DeathHistoryEntry entry = new DeathHistoryEntry(
         System.currentTimeMillis(), player.getUUID(), player.getGameProfile().getName(), position, deathCause, items
      );
      PlayerCorpseDeathHistoryData.get(level).addEntry(player.getUUID(), entry);
   }
}
