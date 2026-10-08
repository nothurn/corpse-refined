package com.playercorpse.registry;

import com.playercorpse.entity.PlayerGraveMarkerEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;

public final class PlayerCorpseAttributes {
   private PlayerCorpseAttributes() {
   }

   public static void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
      event.put((EntityType)PlayerCorpseEntities.PLAYER_CORPSE.get(), LivingEntity.createLivingAttributes().build());
      // Easy to miss, doesn't show up at compile time (RECOVERY.md section 7): any LivingEntity
      // subtype needs its own attribute registration here or it NPEs at construction.
      event.put(
         (EntityType)PlayerCorpseEntities.GRAVE_MARKER.get(),
         LivingEntity.createLivingAttributes().add(Attributes.MAX_HEALTH, PlayerGraveMarkerEntity.MAX_DURABILITY).build()
      );
   }
}
