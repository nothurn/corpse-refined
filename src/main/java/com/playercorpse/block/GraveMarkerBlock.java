package com.playercorpse.block;

import com.mojang.serialization.MapCodec;
import com.playercorpse.block.entity.GraveMarkerBlockEntity;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

public final class GraveMarkerBlock extends BaseEntityBlock {
   public static final MapCodec<GraveMarkerBlock> CODEC = simpleCodec(GraveMarkerBlock::new);
   private static final VoxelShape SHAPE = Shapes.or(
      box(7.0, 0.0, 7.0, 9.0, 16.0, 9.0),
      box(3.0, 10.0, 7.0, 13.0, 12.0, 9.0)
   );

   public GraveMarkerBlock(BlockBehaviour.Properties properties) {
      super(properties);
   }

   @Override
   protected MapCodec<? extends BaseEntityBlock> codec() {
      return CODEC;
   }

   @Override
   protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
      return SHAPE;
   }

   @Override
   protected RenderShape getRenderShape(BlockState state) {
      return RenderShape.MODEL;
   }

   @Override
   protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
      if (!level.isClientSide() && level.getBlockEntity(pos) instanceof GraveMarkerBlockEntity grave) {
         player.openMenu(grave);
      }

      return InteractionResult.sidedSuccess(level.isClientSide());
   }

   @Override
   protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
      if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof GraveMarkerBlockEntity grave) {
         grave.dropContents();
      }

      super.onRemove(state, level, pos, newState, isMoving);
   }

   @Nullable
   @Override
   public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
      return new GraveMarkerBlockEntity(pos, state);
   }
}
