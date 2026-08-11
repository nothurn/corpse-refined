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
    * Re-tuned 2026-08-11 (Noah's in-game report: "nothing happening, then
    * sudden fragments, then rapid full crumble" - matched the pacing
    * predicted from sampling ash_0/ash_1 alpha deltas the session before:
    * with the old 0.82/0.12 split, most of the model changed 0-1/255 in
    * alpha across the ENTIRE first 73.8s of a 90s default ASH_TICKS phase,
    * since raw progress in [0, 0.82] only ever produced effective in
    * [0, 0.12] - stage index (effective*8) never left stage 0. Old slope
    * ratio between the two segments was (0.88/0.18)/(0.12/0.82) ~= 33.4x -
    * a near-vertical cliff, not a gradual speed-up.
    *
    * New values keep the SAME two-segment shape RECOVERY.md asks for ("long
    * stable hold that suddenly gives way, like a sand structure" - a real
    * rate change is still the intended effect, see ashCollapseCurve below),
    * just far less extreme: slope ratio (0.70/0.40)/(0.30/0.60) = 3.5x.
    * effective=0.30 (stage ~2.4 of 8) is now reached by 60% of real
    * duration (54s of 90s default) instead of effective=0.12 (stage <1) at
    * 82% (73.8s) - visible stage transitions now start well within the
    * first minute rather than being invisible until the last 16 seconds.
    * The final 40% of duration still visibly accelerates (3.5x the first
    * segment's rate) to keep the "give way" payoff, just not a 33x cliff.
    */
   private static final float ASH_STABLE_DURATION_FRACTION = 0.6F;
   private static final float ASH_STABLE_VISIBLE_PROGRESS_FRACTION = 0.3F;

   /**
    * Structural-visibility thresholds, in terms of the SAME effective
    * (post-ashCollapseCurve) progress value that drives the texture blend -
    * RECOVERY.md section 7 point 5 is explicit that pose deformation must
    * use this one timeline, not a second independent one. Order matches
    * "Gliedmaßen -> Kopf -> Körper": limbs vanish first, then head, then
    * body last (body is what "bleibt als letztes stehen"). Exact numeric
    * spacing is my own judgment call - RECOVERY.md names the three
    * thresholds and their order but not specific values - chosen so each
    * part's own 0.22-wide ramp window doesn't finish before the previous
    * part's window starts, so the sequence reads as continuous rather than
    * having a dead gap. UNVERIFIED visually - flag for a real in-game look.
    */
   static final float ASH_HIDE_LIMBS_AT = 0.55F;
   static final float ASH_HIDE_HEAD_AT = 0.75F;
   static final float ASH_HIDE_BODY_AT = 0.95F;
   private static final float ASH_POSE_RAMP_WINDOW = 0.22F;

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
    * Remaps raw ASH-phase progress so the first ASH_STABLE_DURATION_FRACTION
    * of the phase's real duration produces ASH_STABLE_VISIBLE_PROGRESS_FRACTION
    * of visible collapse, with the remaining duration covering the rest -
    * reads as a hold that gives way faster toward the end, like a sand
    * structure, rather than a steady shrink. Deliberately a hard slope
    * change at the breakpoint, not smoothed - unlike buildAlphaCurve, a rate
    * change here IS the intended effect, not something to avoid. See the
    * constants' own doc comment for why these specific values, not the
    * original, much more extreme 0.82/0.12 split.
    */
   static float ashCollapseCurve(float rawProgress) {
      rawProgress = Mth.clamp(rawProgress, 0.0F, 1.0F);
      if (rawProgress <= ASH_STABLE_DURATION_FRACTION) {
         return rawProgress / ASH_STABLE_DURATION_FRACTION * ASH_STABLE_VISIBLE_PROGRESS_FRACTION;
      } else {
         float t = (rawProgress - ASH_STABLE_DURATION_FRACTION) / (1.0F - ASH_STABLE_DURATION_FRACTION);
         return ASH_STABLE_VISIBLE_PROGRESS_FRACTION + t * (1.0F - ASH_STABLE_VISIBLE_PROGRESS_FRACTION);
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
