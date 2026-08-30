"""
verify_new_features.py

Numerical verification for the 2026-08-12c feature session (environmental
decay speed, ash-particle erosion coupling, optional loot-free grave
marker). This environment cannot render or build Minecraft, so - same
discipline as every other tools/verify_*.py in this project - this checks
the actual MATH and, where real committed assets exist, the actual pixel
data involved. It does NOT and CANNOT confirm compile success or in-game
behavior; see each feature's own code comments for what remains genuinely
unverified (the Biome#getTemperature API call in particular).

Run from the tools/ directory:
  python verify_new_features.py
Requires: Pillow, numpy (only for part 2)
"""
import numpy as np
from PIL import Image

ASH_PATH = "../src/main/resources/assets/playercorpse/textures/entity/ash_{}.png"

WATER_MULTIPLIER = 1.5
COLD_MULTIPLIER = 0.6
COLD_THRESHOLD = 0.15


def environmental_decay_rate(in_water, cold):
    """Direct port of PlayerCorpseEntity#environmentalDecayRate's formula."""
    rate = 1.0
    if in_water:
        rate *= WATER_MULTIPLIER
    if cold:
        rate *= COLD_MULTIPLIER
    return rate


def mean_erosion(stage):
    image = np.array(Image.open(ASH_PATH.format(stage)).convert("RGBA"))
    return image[:, :, 3].astype(np.float64).mean() / 255.0


def should_spawn_marker(inventory_empty, always_spawn_flag):
    """Direct port of completeAshAndDiscard's shouldSpawnMarker boolean."""
    return (not inventory_empty) or always_spawn_flag


def main():
    print("=== 1. environmentalDecayRate (Feature A) ===")

    baseline = environmental_decay_rate(False, False)
    assert baseline == 1.0, f"no water, no cold should be exactly 1.0x, got {baseline}"
    print(f"OK: baseline (no water, not cold) rate = {baseline}x - unchanged from pre-feature behavior")

    water_only = environmental_decay_rate(True, False)
    assert water_only == WATER_MULTIPLIER
    print(f"OK: water only = {water_only}x (faster, as intended)")

    cold_only = environmental_decay_rate(False, True)
    assert cold_only == COLD_MULTIPLIER
    print(f"OK: cold only = {cold_only}x (slower, as intended)")

    both = environmental_decay_rate(True, True)
    expected_both = WATER_MULTIPLIER * COLD_MULTIPLIER
    assert abs(both - expected_both) < 1e-9
    print(f"OK: water AND cold (e.g. icy water) = {both:.3f}x - multiplicative combination, "
          f"net effect is {'faster' if both > 1.0 else 'slower' if both < 1.0 else 'unchanged'} than baseline "
          "(a real, honest consequence of multiplying two independent effects together, not separately tuned "
          "for this specific combination - flag for Noah's call if 0.9x reads as 'basically normal' rather "
          "than a distinct third state)")

    # A corpse permanently in cold water would need duration/rate real ticks to finish a phase -
    # confirm that's a longer real-world wait than baseline, consistent with rate < 1.0 meaning slower.
    duration = 2400  # e.g. decay_ticks default
    real_ticks_needed = duration / both
    assert real_ticks_needed > duration
    print(f"OK: a phase with duration={duration} ticks takes {real_ticks_needed:.0f} real ticks at the "
          f"combined {both:.3f}x rate (longer than baseline, consistent with rate < 1.0 = slower decay)")

    print()
    print("=== 2. Ash-particle erosion coupling (Feature B), against REAL committed ash_N.png ===")
    try:
        means = [mean_erosion(i) for i in range(9)]
    except FileNotFoundError:
        print("SKIPPED: ash_N.png stages not found")
        means = None

    if means is not None:
        for i, m in enumerate(means):
            print(f"  stage {i}: mean erosion (=mean alpha) = {m:.4f}")

        assert all(means[i] <= means[i + 1] + 1e-9 for i in range(len(means) - 1)), \
            "mean erosion must be non-decreasing stage to stage (erosion only ever increases)"
        print("OK: mean erosion is monotonically non-decreasing stage 0->8 (matches erosion's own "
              "documented meaning - it never reverses)")

        deltas = [means[i + 1] - means[i] for i in range(len(means) - 1)]
        for i, d in enumerate(deltas):
            print(f"  delta stage {i}->{i + 1}: {d:.4f} (-> particle count this transition = "
                  f"round({d:.4f} * 60) = {round(max(d, 0.0) * 60)})")
        assert all(d >= -1e-9 for d in deltas), "a delta went negative - erosion decreased somewhere, real bug"
        print("OK: every stage-to-stage delta is non-negative, so the particle-count formula "
              "(max(delta, 0) * ASH_PARTICLE_COUNT_SCALE) never needs its max(...,0) clamp in practice - "
              "that clamp is defensive-only against this real data, not covering an active case")

        spread = max(deltas) - min(deltas)
        print(f"  delta range across the 8 transitions: {min(deltas):.4f} to {max(deltas):.4f} "
              f"(spread {spread:.4f}) - this IS the actual variance the particle rate will show over the "
              "phase; confirms honestly whether 'coupled to the erosion signal' currently reads as "
              "meaningfully varying or close to flat, given the current stage textures")

    print()
    print("=== 3. Optional loot-free grave marker (Feature C) - truth table ===")
    cases = [
        (False, False, True, "has loot, flag off -> spawns (original RECOVERY.md behavior, unchanged)"),
        (False, True, True, "has loot, flag on -> spawns (flag is irrelevant when loot exists)"),
        (True, False, False, "empty, flag off -> does NOT spawn (original RECOVERY.md behavior, unchanged)"),
        (True, True, True, "empty, flag on -> spawns anyway (the new opt-in memorial behavior)"),
    ]
    for inventory_empty, flag, expected, description in cases:
        actual = should_spawn_marker(inventory_empty, flag)
        assert actual == expected, f"{description}: expected {expected}, got {actual}"
        print(f"OK: {description}")


if __name__ == "__main__":
    main()
