package com.playercorpse;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.DoubleValue;
import net.neoforged.neoforge.common.ModConfigSpec.LongValue;

public final class PlayerCorpseConfig {
   private static final Builder BUILDER = new Builder();
   public static final BooleanValue ENABLED = BUILDER.comment("Master switch. If false, deaths behave exactly as vanilla (normal item drops, no corpse).")
      .define("enabled", true);
   public static final LongValue HOLD_FRESH_TICKS = BUILDER.comment(
         new String[]{
            "Ticks a fresh corpse holds its normal player-skin appearance before the shroud begins",
            "forming (see decay_ticks below). Default of 2400 is 2 real-world minutes.",
            "NOTE: if you're testing this value and the observed timing doesn't match, check",
            "whether the vanilla /tick rate command has been used to change the server's tick",
            "speed away from the default 20/sec - that affects every tick-based timer in the game",
            "equally, this mod's included, and isn't something this config can compensate for."
         }
      )
      .defineInRange("hold_fresh_ticks", 2400L, 1L, Long.MAX_VALUE);
   public static final LongValue DECAY_TICKS = BUILDER.comment(
         new String[]{
            "Ticks for the white shroud to build up over the corpse once hold_fresh_ticks elapses.",
            "The model reference silently switches from the player's skin to the real skeleton",
            "underneath partway through this window, hidden by the shroud - see",
            "PlayerCorpseRenderer's BUILD-phase rendering. Purely cosmetic; stored items remain",
            "fully retrievable throughout. Default of 2400 is 2 real-world minutes."
         }
      )
      .defineInRange("decay_ticks", 2400L, 1L, Long.MAX_VALUE);
   public static final LongValue HOLD_FULL_SHROUD_TICKS = BUILDER.comment(
         new String[]{
            "Ticks the shroud holds at full, unbroken opacity once fully built, before erosion",
            "begins (see shroud_erosion_ticks below). The model swap to skeleton is already done",
            "and hidden by this point, so this window can absorb any residual visual settling",
            "without ever showing a seam. Default of 1800 is 1.5 real-world minutes."
         }
      )
      .defineInRange("hold_full_shroud_ticks", 1800L, 1L, Long.MAX_VALUE);
   public static final LongValue SHROUD_EROSION_TICKS = BUILDER.comment(
         new String[]{
            "Ticks the shroud takes to wear away once hold_full_shroud_ticks elapses. The shroud",
            "keeps its player-shaped silhouette throughout this, gradually developing more and",
            "unevenly-placed holes (not a uniform fade) until it's fully gone and only the skeleton",
            "remains. Default of 2400 is 2 real-world minutes."
         }
      )
      .defineInRange("shroud_erosion_ticks", 2400L, 1L, Long.MAX_VALUE);
   public static final LongValue HOLD_FULL_SKELETON_TICKS = BUILDER.comment(
         new String[]{
            "Ticks the bare skeleton holds once the shroud is fully eroded, before ash decay begins",
            "(see ash_ticks below). Default of 1800 is 1.5 real-world minutes."
         }
      )
      .defineInRange("hold_full_skeleton_ticks", 1800L, 1L, Long.MAX_VALUE);
   public static final LongValue ASH_TICKS = BUILDER.comment(
         new String[]{
            "Ticks for the bare skeleton to crumble to ash and the corpse to be removed. If any",
            "items remain at that point, a PlayerGraveMarkerEntity is left behind holding them -",
            "see RECOVERY.md section 7. Material is lost continuously across the whole phase",
            "(erosion-based texture alpha, same mechanism EROSION uses), not held stable then",
            "suddenly collapsed. Default of 1800 is 1.5 real-world minutes.",
            "Total default time from fresh corpse to ash (sum of all six phases) is 12,600 ticks",
            "= 10.5 real-world minutes."
         }
      )
      .defineInRange("ash_ticks", 1800L, 1L, Long.MAX_VALUE);
   public static final LongValue EMPTY_DECAY_TICKS = BUILDER.comment(
         new String[]{
            "Ticks a corpse waits, once BOTH its equipment and general inventory are completely",
            "empty, before it fast-forwards straight to the HOLD_FULL_SKELETON phase (skipping the",
            "early cosmetic HOLD_FRESH/BUILD/HOLD_FULL_SHROUD/EROSION phases, since there's no loot",
            "left to protect by lingering in them). From there it decays and despawns exactly like",
            "any other corpse - since it's empty, no grave marker is left behind when it finally",
            "reaches ash. Never counts down while any item remains. Default of 1200 is 1 real-world",
            "minute."
         }
      )
      .defineInRange("empty_decay_ticks", 1200L, 1L, Long.MAX_VALUE);
   public static final BooleanValue FORCE_DESPAWN_ENABLED = BUILDER.comment(
         new String[]{
            "Safety net only, OFF by default: if enabled, a corpse still holding items after",
            "force_despawn_ticks have passed since it spawned is removed anyway (its items are",
            "dropped on the ground, never deleted - see PlayerCorpseEntity#remove). Unlike reaching",
            "ash naturally, this does NOT spawn a grave marker - it's a last-resort ceiling for",
            "servers that want a hard cap on abandoned corpses, not part of the normal decay story.",
            "Intended only for servers that want a hard ceiling on how long an abandoned corpse can",
            "persist."
         }
      )
      .define("force_despawn_enabled", false);
   public static final LongValue FORCE_DESPAWN_TICKS = BUILDER.comment(
         new String[]{
            "Only used if force_despawn_enabled is true. Default of 51840000 is 30 real-world days -",
            "deliberately very long so this never surprises anyone who hasn't explicitly opted in."
         }
      )
      .defineInRange("force_despawn_ticks", 51840000L, 1L, Long.MAX_VALUE);
   public static final BooleanValue DECAY_ITEM_LOSS_ENABLED = BUILDER.comment(
         new String[]{
            "EXPERIMENTAL, default OFF, and currently NOT implemented: intended to let decay actually",
            "remove a percentage of the corpse's stored items over time, on top of the purely cosmetic",
            "skeleton-visual decay above. Left as a reserved config placeholder pending a decision on",
            "whether to build this out; toggling it currently has no effect."
         }
      )
      .define("decay_item_loss_enabled", false);
   public static final DoubleValue DECAY_ITEM_LOSS_PERCENT = BUILDER.comment(
         "Reserved for the same not-yet-implemented feature as decay_item_loss_enabled above."
      )
      .defineInRange("decay_item_loss_percent", 0.25, 0.0, 1.0);
   public static final BooleanValue HUD_ARROW_ENABLED = BUILDER.comment(
         new String[]{
            "Whether a small HUD arrow pointing toward your most recent not-yet-looted, not-fully-",
            "decayed corpse is shown during normal gameplay. Purely client-side and cosmetic - has",
            "no effect on corpse behavior itself."
         }
      )
      .define("hud_arrow_enabled", true);
   public static final ModConfigSpec SPEC = BUILDER.build();

   private PlayerCorpseConfig() {
   }

   static {
      BUILDER.push("general");
      BUILDER.pop();
      BUILDER.push("experimental");
      BUILDER.pop();
      BUILDER.push("client");
      BUILDER.pop();
   }
}
