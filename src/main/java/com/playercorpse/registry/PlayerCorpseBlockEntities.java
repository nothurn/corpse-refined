package com.playercorpse.registry;

import com.playercorpse.PlayerCorpseMod;
import com.playercorpse.block.entity.GraveMarkerBlockEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PlayerCorpseBlockEntities {
   public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES = DeferredRegister.create(
      Registries.BLOCK_ENTITY_TYPE, PlayerCorpseMod.MOD_ID
   );
   public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<GraveMarkerBlockEntity>> GRAVE_MARKER = BLOCK_ENTITY_TYPES.register(
      "grave_marker",
      () -> BlockEntityType.Builder.of(GraveMarkerBlockEntity::new, PlayerCorpseBlocks.GRAVE_MARKER.get()).build(null)
   );

   private PlayerCorpseBlockEntities() {
   }
}
