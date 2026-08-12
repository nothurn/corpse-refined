package com.playercorpse.client.renderer;

import net.minecraft.util.Mth;

/**
 * Shared timing/easing curves for the decay renderer. See RECOVERY.md
 * section 7 for why plain smoothstep was rejected for the BUILD phase: it
 * has zero derivative at BOTH ends, so applied over a multi-minute phase it
 * looks static ~80% of the time and squeezes all the visible motion into a
 * short middle window.
 */
final class DecayCurves {
   private static final float BUILD_EASE_FRACTION = 0.12F;

   /**
    * 2026-08-12 mechanism redesign - the previous "long stable hold, then
    * sudden collapse" ashCollapseCurve (0.6/0.3 split, re-tuned from an even
    * more extreme 0.82/0.12 in the session before this one) is REMOVED, not
    * re-tuned again. Noah's stated expectation: the skeleton should
    * continuously LOSE material over the WHOLE ash phase, the same way
    * EROSION continuously erodes the shroud - not hold-then-collapse. Every
    * ash-phase timeline (texture erosion stage selection in
    * PlayerCorpseRenderer.ashTextureFor, and the structural
    * thresholds/ashPoseT below) now runs directly off raw
    * entity.getPhaseProgress(), the exact same linear mapping
    * CorpseShroudLayer already uses for EROSION's own stage selection - no
    * remap function at all. See BlendedTextureCache.erodeOver for the other
    * half of this redesign: the texture itself now actually loses alpha
    * (material erosion) instead of an opaque color overlay building up,
    * which is what let a "long stable hold" ever look motionless in the
    * first place - a genuinely continuous per-pixel erosion signal doesn't
    * need artificial temporal concentration to read as "something is always
    * happening".
    */
   /**
    * Structural-visibility thresholds, now in terms of raw
    * entity.getPhaseProgress() directly (see class doc above - no
    * intermediate remap). Order matches "Gliedmaßen -> Kopf -> Körper":
    * limbs vanish first, then head, then body last (body is what "bleibt
    * als letztes stehen"). Re-tuned 2026-08-12: spread EVENLY across the
    * whole phase (was 0.55/0.75/0.95, clustered in the last 45%) instead of
    * concentrated late - starts at 13% of real duration instead of 33%, so
    * structural loss is visible well within the first third of the phase,
    * not saved for the end. Exact numeric spacing is still my own judgment
    * call - RECOVERY.md names the three thresholds and their order but not
    * specific values - chosen so each part's own 0.22-wide ramp window
    * doesn't finish before the previous part's window starts (continuous
    * sequence, no dead gap) and BODY_AT leaves room before 1.0 for the ash
    * pile (see ASH_PILE_APPEAR_START below) to actually be visible before
    * the corpse discards. UNVERIFIED visually - flag for a real in-game
    * look.
    */
   static final float ASH_HIDE_LIMBS_AT = 0.35F;
   static final float ASH_HIDE_HEAD_AT = 0.60F;
   static final float ASH_HIDE_BODY_AT = 0.85F;
   private static final float ASH_POSE_RAMP_WINDOW = 0.22F;

   /**
    * 2026-08-12, new: nothing previously represented "a small ash pile
    * remains" - the model just fully vanished, and the grave marker then
    * appeared with no visual bridge between the two. ashPile (see
    * PlayerCorpseRenderer.buildAshSkeletonRoot/applyAshDeformation) scales
    * in over this window. Starts BEFORE ASH_HIDE_BODY_AT completes (0.85)
    * so the body's own vanish and the pile's appearance overlap into a
    * crossfade rather than leaving a gap, and ends at 0.95, leaving the
    * final 5% of the phase (~4.5s of the 90s default ASH_TICKS) as "fully
    * formed pile, nothing else" before completeAshAndDiscard() fires and
    * the grave marker spawns.
    */
   private static final float ASH_PILE_APPEAR_START = 0.80F;
   private static final float ASH_PILE_APPEAR_END = 0.95F;

   private DecayCurves() {
   }

   /**
    * 0 far before hideAt, ramps to 1 exactly at hideAt via smoothstep over a
    * window of ASH_POSE_RAMP_WINDOW immediately before it - so a part's pose
    * deformation (tilt/sink/splay) finishes ramping in at the exact moment
    * visibility hiding takes over, reading as "gives way, then is gone"
    * rather than an abrupt disappearance with no lead-in. Smoothstep is
    * deliberately used HERE (unlike buildAlphaCurve): this window is short
    * and local to one part's own transition, not a multi-minute phase, so
    * smoothstep's zero-derivative ends don't create the "looks static most
    * of the time" problem they would over a longer span - they just make
    * the start and end of this one brief transition feel settled.
    */
   static float ashPoseT(float effective, float hideAt) {
      float windowStart = hideAt - ASH_POSE_RAMP_WINDOW;
      if (effective <= windowStart) {
         return 0.0F;
      } else if (effective >= hideAt) {
         return 1.0F;
      } else {
         float u = (effective - windowStart) / ASH_POSE_RAMP_WINDOW;
         return u * u * (3.0F - 2.0F * u);
      }
   }

   /**
    * 0 before ASH_PILE_APPEAR_START, ramps to 1 by ASH_PILE_APPEAR_END via
    * plain smoothstep - drives the ash pile's own scale-in (see
    * PlayerCorpseRenderer.applyAshDeformation). Deliberately a separate,
    * explicit window rather than reusing ashPoseT: ashPoseT's "ramp UP TO a
    * hideAt threshold" semantics are about a part finishing its deformation
    * right as it disappears; this is the inverse (something new growing
    * in), clearer as its own small function than as a repurposed call.
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

   /**
    * Eases only in the first and last 12% of t, linear in between - unlike
    * smoothstep, this only spends a small fraction of the phase easing, so
    * the BUILD phase reads as "steadily happening" throughout instead of
    * "nothing, then a burst in the middle, then nothing".
    *
    * The two ease segments are parabolic (not smoothstep) specifically so
    * the slope matches the linear middle exactly at each 12% boundary -
    * smoothstep's own derivative is zero at BOTH ends of whatever range it's
    * applied to, which would have created a visible kink where it meets the
    * linear section instead of a seamless handoff.
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
