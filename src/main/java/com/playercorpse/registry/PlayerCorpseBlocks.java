package com.playercorpse.registry;

import com.playercorpse.PlayerCorpseMod;
import com.playercorpse.block.GraveMarkerBlock;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class PlayerCorpseBlocks {
   public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, PlayerCorpseMod.MOD_ID);
   public static final DeferredHolder<Block, GraveMarkerBlock> GRAVE_MARKER = BLOCKS.register(
      "grave_marker",
      () -> new GraveMarkerBlock(BlockBehaviour.Properties.of().strength(2.0F).sound(SoundType.WOOD).noOcclusion())
   );

   private PlayerCorpseBlocks() {
   }
}
