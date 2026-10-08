package com.playercorpse.client.renderer;

import net.minecraft.util.Mth;

/**
 * Shared easing curves for effects that genuinely need interpolation.
 * Existing skeleton parts deliberately have no ASH pose curve: their shape and
 * pose stay stable while per-pixel erosion and particles represent material loss.
 */
final class DecayCurves {
   private static final float BUILD_EASE_FRACTION = 0.12F;
   private static final float ASH_PILE_APPEAR_START = 0.72F;
   private static final float ASH_PILE_APPEAR_END = 0.86F;
   private static final float ASH_BONE_CLEANUP_AT = ASH_PILE_APPEAR_END;

   private DecayCurves() {
   }

   /**
    * The ash pile is new geometry rather than an existing bone. It scales in
    * near the end of ASH while the last texture pixels disappear.
    */
   static float pileAppearT(float rawProgress) {
      if (rawProgress <= ASH_PILE_APPEAR_START) {
         return 0.0F;
      } else if (rawProgress >= ASH_PILE_APPEAR_END) {
         return 1.0F;
      } else {
         float u = (rawProgress - ASH_PILE_APPEAR_START) / (ASH_PILE_APPEAR_END - ASH_PILE_APPEAR_START);
         return u * u * (3.0F - 2.0F * u);
      }
   }

   static boolean showAshBones(float rawProgress) {
      return rawProgress < ASH_BONE_CLEANUP_AT;
   }

   /**
    * Eases only in the first and last 12% of t and remains linear in between,
    * so the multi-minute shroud build does not look static for most of its run.
    */
   static float buildAlphaCurve(float t) {
      t = Mth.clamp(t, 0.0F, 1.0F);
      float ease = BUILD_EASE_FRACTION;
      float middleSlope = 1.0F / (1.0F - ease);
      if (t < ease) {
         return middleSlope / (2.0F * ease) * t * t;
      } else if (t > 1.0F - ease) {
         float tFromEnd = 1.0F - t;
         return 1.0F - middleSlope / (2.0F * ease) * tFromEnd * tFromEnd;
      } else {
         return middleSlope * ease / 2.0F + middleSlope * (t - ease);
      }
   }
}
