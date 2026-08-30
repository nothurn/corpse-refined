package com.playercorpse;

import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.ModConfigSpec.BooleanValue;
import net.neoforged.neoforge.common.ModConfigSpec.Builder;
import net.neoforged.neoforge.common.ModConfigSpec.DoubleValue;
import net.neoforged.neoforge.common.ModConfigSpec.LongValue;

public final class PlayerCorpseConfig {
   private static final Builder BUILDER = new Builder();
   private static final long DEFAULT_HOLD_FRESH_TICKS = 4800L;
   private static final long DEFAULT_BUILD_TICKS = 2400L;
   private static final long DEFAULT_HOLD_FULL_SHROUD_TICKS = 2400L;
   private static final long DEFAULT_SHROUD_EROSION_TICKS = 3600L;
   private static final long DEFAULT_HOLD_FULL_SKELETON_TICKS = 2400L;
   private static final long DEFAULT_ASH_TICKS = 2400L;
   public static final BooleanValue ENABLED = BUILDER.comment("Master switch. If false, deaths behave exactly as vanilla (normal item drops, no corpse).")
      .define("enabled", true);
   public static final LongValue HOLD_FRESH_TICKS = BUILDER.comment(
         new String[]{
            "Ticks a fresh corpse holds its normal player-skin appearance before the shroud begins",
            "forming (see decay_ticks below). Default of 4800 is 4 real-world minutes.",
            "NOTE: if you're testing this value and the observed timing doesn't match, check",
            "whether the vanilla /tick rate command has been used to change the server's tick",
            "speed away from the default 20/sec - that affects every tick-based timer in the game",
            "equally, this mod's included, and isn't something this config can compensate for."
         }
      )
      .defineInRange("hold_fresh_ticks", DEFAULT_HOLD_FRESH_TICKS, 1L, Long.MAX_VALUE);
   public static final LongValue DECAY_TICKS = BUILDER.comment(
         new String[]{
            "Ticks for the white shroud to build up over the corpse once hold_fresh_ticks elapses.",
            "The model reference silently switches from the player's skin to the real skeleton",
            "underneath partway through this window, hidden by the shroud - see",
            "PlayerCorpseRenderer's BUILD-phase rendering. Purely cosmetic; stored items remain",
            "fully retrievable throughout. Default of 2400 is 2 real-world minutes."
         }
      )
      .defineInRange("decay_ticks", DEFAULT_BUILD_TICKS, 1L, Long.MAX_VALUE);
   public static final LongValue HOLD_FULL_SHROUD_TICKS = BUILDER.comment(
         new String[]{
            "Ticks the shroud holds at full, unbroken opacity once fully built, before erosion",
            "begins (see shroud_erosion_ticks below). The model swap to skeleton is already done",
            "and hidden by this point, so this window can absorb any residual visual settling",
            "without ever showing a seam. Default of 2400 is 2 real-world minutes."
         }
      )
      .defineInRange("hold_full_shroud_ticks", DEFAULT_HOLD_FULL_SHROUD_TICKS, 1L, Long.MAX_VALUE);
   public static final LongValue SHROUD_EROSION_TICKS = BUILDER.comment(
         new String[]{
            "Ticks the shroud takes to wear away once hold_full_shroud_ticks elapses. The shroud",
            "keeps its player-shaped silhouette throughout this, gradually developing more and",
            "unevenly-placed holes (not a uniform fade) until it's fully gone and only the skeleton",
            "remains. Default of 3600 is 3 real-world minutes."
         }
      )
      .defineInRange("shroud_erosion_ticks", DEFAULT_SHROUD_EROSION_TICKS, 1L, Long.MAX_VALUE);
   public static final LongValue HOLD_FULL_SKELETON_TICKS = BUILDER.comment(
         new String[]{
            "Ticks the bare skeleton holds once the shroud is fully eroded, before ash decay begins",
            "(see ash_ticks below). Default of 2400 is 2 real-world minutes."
         }
      )
      .defineInRange("hold_full_skeleton_ticks", DEFAULT_HOLD_FULL_SKELETON_TICKS, 1L, Long.MAX_VALUE);
   public static final LongValue ASH_TICKS = BUILDER.comment(
         new String[]{
            "Ticks for the bare skeleton to crumble to ash and the corpse to be removed. If any",
            "items remain at that point, a PlayerGraveMarkerEntity is left behind holding them -",
            "see RECOVERY.md section 7. Material is lost continuously across the whole phase",
            "(erosion-based texture alpha, same mechanism EROSION uses), not held stable then",
            "suddenly collapsed. Default of 2400 is 2 real-world minutes.",
            "Total default time from fresh corpse to ash (sum of all six phases) is 18,000 ticks",
            "= 15 real-world minutes. Exact former 10.5- and 30-minute presets are migrated once",
            "without overwriting individually customised timings."
         }
      )
      .defineInRange("ash_ticks", DEFAULT_ASH_TICKS, 1L, Long.MAX_VALUE);
   public static final LongValue EMPTY_DECAY_TICKS = BUILDER.comment(
         new String[]{
            "Ticks a corpse waits, once BOTH its equipment and general inventory are completely",
            "empty, before it fast-forwards straight to the HOLD_FULL_SKELETON phase (skipping the",
            "early cosmetic HOLD_FRESH/BUILD/HOLD_FULL_SHROUD/EROSION phases, since there's no loot",
            "left to protect by lingering in them). From there it decays and despawns exactly like",
            "any other corpse - since it's empty, no grave marker is left behind when it finally",
            "reaches ash, UNLESS always_spawn_grave_marker below is enabled, in which case an empty",
            "memorial marker spawns anyway. Never counts down while any item remains. Default of 1200",
            "is 1 real-world minute."
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

   // --- 2026-08-12c: new feature configs, not part of the original RECOVERY.md spec. ---

   public static final DoubleValue WATER_DECAY_SPEED_MULTIPLIER = BUILDER.comment(
         new String[]{
            "Decay speed multiplier applied on every tick the corpse is submerged in water (checked",
            "via Entity#isInWater()). 1.0 = no effect, 2.0 = decays twice as fast while underwater.",
            "Combines multiplicatively with cold_decay_speed_multiplier below if both apply at once",
            "(e.g. a corpse in icy water) - a game-design judgment call, not derived from any real-",
            "world spec, since none exists for this. See PlayerCorpseEntity#environmentalDecayRate",
            "for how this is applied. Default of 1.5 is a 50% speedup, deliberately noticeable but",
            "not extreme."
         }
      )
      .defineInRange("water_decay_speed_multiplier", 1.5, 1.0, 10.0);
   public static final DoubleValue COLD_DECAY_SPEED_MULTIPLIER = BUILDER.comment(
         new String[]{
            "Decay speed multiplier applied on every tick the corpse sits in a biome cold enough to",
            "snow (Biome#coldEnoughToSnow at the corpse's own block position, which applies vanilla's",
            "height-adjusted 0.15 temperature threshold). This call was verified against the real",
            "decompiled Minecraft 1.21.1 source.",
            "1.0 = no effect, 0.5 = decays at half speed while cold. Default of 0.6 is a real but not",
            "overwhelming slowdown."
         }
      )
      .defineInRange("cold_decay_speed_multiplier", 0.6, 0.05, 1.0);
   public static final BooleanValue ALWAYS_SPAWN_GRAVE_MARKER = BUILDER.comment(
         new String[]{
            "If true, a PlayerGraveMarkerEntity is always left behind once a corpse reaches ash, even",
            "if its inventory is completely empty (a plain memorial cross, holding nothing) - RECOVERY.md",
            "section 7's original spec only ever called for one when loot remained, and that is still",
            "this setting's default (false). NOTE: an empty corpse fast-forwards straight to",
            "HOLD_FULL_SKELETON via empty_decay_ticks above and skips the early cosmetic phases before",
            "it ever reaches ash either way - this setting only changes what happens once ash IS",
            "reached, not that fast-forward."
         }
      )
      .define("always_spawn_grave_marker", false);

   public static final ModConfigSpec SPEC = BUILDER.build();

   private static boolean decayDefaultsChecked;

   /**
    * Replaces only the exact former 10.5-minute test preset or the exact
    * 30-minute preset that superseded it. Any individually customised timing
    * remains untouched.
    */
   public static synchronized void migrateDecayDefaults() {
      if (decayDefaultsChecked) {
         return;
      }

      decayDefaultsChecked = true;
      boolean formerTestPreset = HOLD_FRESH_TICKS.get() == 2400L
         && DECAY_TICKS.get() == 2400L
         && HOLD_FULL_SHROUD_TICKS.get() == 1800L
         && SHROUD_EROSION_TICKS.get() == 2400L
         && HOLD_FULL_SKELETON_TICKS.get() == 1800L
         && ASH_TICKS.get() == 1800L;
      boolean formerThirtyMinutePreset = HOLD_FRESH_TICKS.get() == 12000L
         && DECAY_TICKS.get() == 6000L
         && HOLD_FULL_SHROUD_TICKS.get() == 6000L
         && SHROUD_EROSION_TICKS.get() == 6000L
         && HOLD_FULL_SKELETON_TICKS.get() == 3600L
         && ASH_TICKS.get() == 2400L;
      if (formerTestPreset || formerThirtyMinutePreset) {
         HOLD_FRESH_TICKS.set(DEFAULT_HOLD_FRESH_TICKS);
         DECAY_TICKS.set(DEFAULT_BUILD_TICKS);
         HOLD_FULL_SHROUD_TICKS.set(DEFAULT_HOLD_FULL_SHROUD_TICKS);
         SHROUD_EROSION_TICKS.set(DEFAULT_SHROUD_EROSION_TICKS);
         HOLD_FULL_SKELETON_TICKS.set(DEFAULT_HOLD_FULL_SKELETON_TICKS);
         ASH_TICKS.set(DEFAULT_ASH_TICKS);
         SPEC.save();
      }
   }

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
