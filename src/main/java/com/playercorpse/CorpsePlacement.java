package com.playercorpse;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Finds a nearby placement whose full entity bounds are collision-free and
 * outside fluids. A grounded position is always preferred; the dry-air
 * fallback exists for deaths in large lava/water pools where no bank is close
 * enough, because a visible floating corpse is still recoverable.
 */
public final class CorpsePlacement {
   private static final int SUPPORTED_SEARCH_RADIUS = 8;
   private static final int FALLBACK_SEARCH_RADIUS = 2;
   private static final double SUPPORT_PROBE_DEPTH = 0.125;
   private static final double VISIBLE_BODY_MARGIN = 0.125;
   private static final double BOUNDS_EPSILON = 1.0E-7;
   private static final int[] VERTICAL_OFFSETS = new int[]{
      0, 1, -1, 2, -2, 3, -3, 4, -4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16
   };

   private CorpsePlacement() {
   }

   public static Optional<Vec3> findSafePosition(ServerLevel level, Entity entity, Vec3 preferredPosition) {
      Optional<Vec3> supported = search(level, entity, preferredPosition, SUPPORTED_SEARCH_RADIUS, true);
      return supported.isPresent()
         ? supported
         : search(level, entity, preferredPosition, FALLBACK_SEARCH_RADIUS, false);
   }

   public static boolean isSafe(ServerLevel level, Entity entity) {
      return isSafe(level, entity, entity.getBoundingBox());
   }

   public static boolean isSafeAndSupported(ServerLevel level, Entity entity) {
      AABB bounds = entity.getBoundingBox();
      return isSafe(level, entity, bounds) && hasSupport(level, entity, bounds);
   }

   private static Optional<Vec3> search(ServerLevel level, Entity entity, Vec3 preferredPosition, int maxRadius, boolean requireSupport) {
      AABB currentBounds = entity.getBoundingBox();

      for (int radius = 0; radius <= maxRadius; radius++) {
         for (int verticalOffset : VERTICAL_OFFSETS) {
            double candidateY = preferredPosition.y + verticalOffset;

            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
               for (int offsetZ = -radius; offsetZ <= radius; offsetZ++) {
                  if (radius > 0 && Math.max(Math.abs(offsetX), Math.abs(offsetZ)) != radius) {
                     continue;
                  }

                  Vec3 candidate = new Vec3(preferredPosition.x + offsetX, candidateY, preferredPosition.z + offsetZ);
                  AABB candidateBounds = currentBounds.move(
                     candidate.x - entity.getX(), candidate.y - entity.getY(), candidate.z - entity.getZ()
                  );
                  if (candidateBounds.minY < level.getMinBuildHeight() || candidateBounds.maxY >= level.getMaxBuildHeight()) {
                     continue;
                  }

                  if (isSafe(level, entity, candidateBounds) && (!requireSupport || hasSupport(level, entity, candidateBounds))) {
                     return Optional.of(candidate);
                  }
               }
            }
         }
      }

      return Optional.empty();
   }

   private static boolean isSafe(ServerLevel level, Entity entity, AABB bounds) {
      if (!level.noCollision(entity, bounds)) {
         return false;
      }

      // The corpse model is slightly wider/longer than its interaction box, and a fluid
      // directly below the box does not geometrically intersect it. Include both that visible
      // overhang and the support layer so a corpse cannot be accepted with only its head on a
      // dry bank while the rendered body lies across lava or water.
      AABB fluidCheckBounds = new AABB(
         bounds.minX - VISIBLE_BODY_MARGIN + BOUNDS_EPSILON,
         bounds.minY - SUPPORT_PROBE_DEPTH + BOUNDS_EPSILON,
         bounds.minZ - VISIBLE_BODY_MARGIN + BOUNDS_EPSILON,
         bounds.maxX + VISIBLE_BODY_MARGIN - BOUNDS_EPSILON,
         bounds.maxY - BOUNDS_EPSILON,
         bounds.maxZ + VISIBLE_BODY_MARGIN - BOUNDS_EPSILON
      );
      return BlockPos.betweenClosedStream(fluidCheckBounds)
         .allMatch(pos -> level.hasChunkAt(pos) && level.getFluidState(pos).isEmpty());
   }

   private static boolean hasSupport(ServerLevel level, Entity entity, AABB bounds) {
      return !level.noCollision(entity, bounds.move(0.0, -SUPPORT_PROBE_DEPTH, 0.0));
   }
}
