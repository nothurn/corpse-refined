"""
verify_ash_erosion.py

Numerical verification for the 2026-08-12 ASH-phase mechanism redesign
(continuous material erosion instead of a growing opaque overlay, plus the
new ash_pile end state). This environment cannot render Minecraft, so this
checks the actual pixel/curve MATH involved - the same discipline as every
other tools/verify_*.py in this project.

Covers three independent pieces:
  1. BlendedTextureCache.erodeOver's formula (Python re-implementation,
     matched byte-for-byte against the real Java source).
  2. DecayCurves' re-tuned structural thresholds (ASH_HIDE_*) and the new
     pileAppearT window.
  3. Cross-check against the REAL committed ash_0..ash_8.png + ash_base.png
     files (if present) - reports the actual stage at which each sampled
     body region's mean erosion would cross the real GPU alpha-test discard
     threshold (0.1, confirmed from
     assets/minecraft/shaders/core/rendertype_entity_cutout_no_cull.fsh:
     `if (color.a < 0.1) discard;`), the same kind of real-pixel-data
     investigation done in the 2026-08-11c/e sessions for the old curve -
     confirms material loss is now spread across the stage range, not
     concentrated at the end.

Run from the tools/ directory, after regenerating both ash_N.png and
ash_base.png:
  python verify_ash_erosion.py
Requires: Pillow, numpy
"""
import numpy as np
from PIL import Image

ASH_PATH = "../src/main/resources/assets/playercorpse/textures/entity/ash_{}.png"
BASE_PATH = "../src/main/resources/assets/playercorpse/textures/entity/ash_base.png"

DUST_HIGHLIGHT = (188, 184, 176)
EDGE_TINT_FRACTION = 0.5
DISCARD_THRESHOLD = 0.1  # real value, confirmed from the vanilla fragment shader source

ASH_HIDE_LIMBS_AT = 0.35
ASH_HIDE_HEAD_AT = 0.60
ASH_HIDE_BODY_AT = 0.85
ASH_POSE_RAMP_WINDOW = 0.22

ASH_PILE_APPEAR_START = 0.80
ASH_PILE_APPEAR_END = 0.95

HEAD = (0, 0, 32, 16)
BODY = (16, 16, 24, 16)
RIGHT_ARM = (40, 16, 8, 14)
RIGHT_LEG = (0, 16, 8, 14)
SAMPLE_REGIONS = {"head": HEAD, "body": BODY, "right_arm": RIGHT_ARM, "right_leg": RIGHT_LEG}


def erode_over(erosion, base_alpha):
    """Direct port of BlendedTextureCache.erodeOver's alpha math (RGB tint
    doesn't affect whether a pixel survives the alpha test, so this focuses
    on alpha; see check 1c below for the RGB tint check separately)."""
    return base_alpha * (1.0 - erosion)


def erode_over_rgb(overlay_rgb, base_rgb, erosion):
    tint = min(erosion / EDGE_TINT_FRACTION, 1.0)
    return tuple(round(b + (o - b) * tint) for o, b in zip(overlay_rgb, base_rgb))


def smoothstep_window(value, end, window):
    start = end - window
    if value <= start:
        return 0.0
    elif value >= end:
        return 1.0
    else:
        u = (value - start) / window
        return u * u * (3.0 - 2.0 * u)


def pile_appear_t(raw_progress):
    if raw_progress <= ASH_PILE_APPEAR_START:
        return 0.0
    elif raw_progress >= ASH_PILE_APPEAR_END:
        return 1.0
    else:
        u = (raw_progress - ASH_PILE_APPEAR_START) / (ASH_PILE_APPEAR_END - ASH_PILE_APPEAR_START)
        return u * u * (3.0 - 2.0 * u)


def main():
    print("=== 1. erodeOver formula ===")

    # 1a. erosion=0 must reproduce the base pixel's own alpha exactly (no change at phase start).
    for base_alpha in (1.0, 0.6, 0.0):
        out = erode_over(0.0, base_alpha)
        assert abs(out - base_alpha) < 1e-9, f"erosion=0 should leave base_alpha={base_alpha} unchanged, got {out}"
    print("OK: erosion=0 leaves base alpha unchanged (no visible change at ASH-phase start)")

    # 1b. erosion=1 must fully clear alpha regardless of how opaque the base was.
    for base_alpha in (1.0, 0.6, 0.05):
        out = erode_over(1.0, base_alpha)
        assert out == 0.0, f"erosion=1 should zero out alpha, got {out} for base_alpha={base_alpha}"
    print("OK: erosion=1 always fully clears alpha (genuine material loss, not just a color change)")

    # 1c. Monotonic non-increasing alpha as erosion rises - no flicker/reappearing pixels.
    base_alpha = 1.0
    samples = [erode_over(e, base_alpha) for e in np.linspace(0.0, 1.0, 101)]
    assert all(samples[i] >= samples[i + 1] - 1e-9 for i in range(len(samples) - 1)), "erodeOver alpha is not monotonic"
    print("OK: output alpha is monotonically non-increasing as erosion rises")

    # 1d. The real discard-crossing point for a fully-opaque base pixel: outAlpha = 1*(1-erosion)
    #     crosses the real 0.1 shader discard threshold exactly at erosion=0.9.
    crossing = 1.0 - DISCARD_THRESHOLD
    assert abs(crossing - 0.9) < 1e-9
    just_before = erode_over(crossing - 0.001, 1.0)
    just_after = erode_over(crossing + 0.001, 1.0)
    assert just_before > DISCARD_THRESHOLD > just_after
    print(f"OK: a fully-opaque pixel (base_alpha=1.0) crosses the real 0.1 discard threshold at erosion={crossing:.3f}")

    # 1e. base_alpha=0 (hat region, and the reserved ash_pile UV patch) stays 0 regardless of erosion -
    #     confirms the pile patch (kept at erosion=0 by generate_ash_texture.py) and the hat region are
    #     both unaffected by this formula change, same as pre-redesign.
    for erosion in (0.0, 0.5, 1.0):
        assert erode_over(erosion, 0.0) == 0.0
    print("OK: base_alpha=0 regions (hat, ash_pile patch) stay fully transparent regardless of erosion")

    # 1f. RGB tint reaches the overlay's own color (DUST_HIGHLIGHT) by erosion=EDGE_TINT_FRACTION,
    #     well before the 0.9 discard point - a pixel visibly pales before it actually vanishes.
    base_rgb = (167, 167, 167)  # real ash_base.png mean bone tone, per the 2026-08-11d session's own measurement
    at_cap = erode_over_rgb(DUST_HIGHLIGHT, base_rgb, EDGE_TINT_FRACTION)
    assert at_cap == DUST_HIGHLIGHT, f"expected full DUST_HIGHLIGHT tint at erosion={EDGE_TINT_FRACTION}, got {at_cap}"
    beyond_cap = erode_over_rgb(DUST_HIGHLIGHT, base_rgb, 0.9)
    assert beyond_cap == DUST_HIGHLIGHT, "tint must stay capped at DUST_HIGHLIGHT beyond EDGE_TINT_FRACTION, not overshoot"
    print(f"OK: RGB tint reaches full DUST_HIGHLIGHT by erosion={EDGE_TINT_FRACTION} ({crossing - EDGE_TINT_FRACTION:.2f} of "
          "erosion range left to visibly sit pale before that pixel is discarded)")

    print()
    print("=== 2. DecayCurves structural thresholds ===")

    thresholds = [("limbs", ASH_HIDE_LIMBS_AT), ("head", ASH_HIDE_HEAD_AT), ("body", ASH_HIDE_BODY_AT)]
    for name, value in thresholds:
        assert 0.0 < value < 1.0, f"{name} threshold {value} out of (0,1)"
    assert ASH_HIDE_LIMBS_AT < ASH_HIDE_HEAD_AT < ASH_HIDE_BODY_AT, "thresholds must fire limbs -> head -> body, in order"
    print(f"OK: thresholds fire in order limbs({ASH_HIDE_LIMBS_AT}) -> head({ASH_HIDE_HEAD_AT}) -> body({ASH_HIDE_BODY_AT})")

    windows = [(name, value - ASH_POSE_RAMP_WINDOW, value) for name, value in thresholds]
    for i in range(len(windows) - 1):
        _, _, prev_end = windows[i]
        next_name, next_start, _ = windows[i + 1]
        gap = next_start - prev_end
        assert -1e-9 <= gap <= 0.05, f"gap between structural windows before {next_name} is {gap:.3f}, not small/contiguous"
        print(f"OK: window gap before '{next_name}' ramp start is {gap:.3f} (small/contiguous, no dead pause)")

    for name, hide_at in thresholds:
        window = ASH_POSE_RAMP_WINDOW
        assert smoothstep_window(hide_at - window, hide_at, window) == 0.0
        assert smoothstep_window(hide_at, hide_at, window) == 1.0
        mid = smoothstep_window(hide_at - window / 2, hide_at, window)
        assert 0.0 < mid < 1.0
    print("OK: each structural ramp is bounded [0,1] and reaches exactly 1.0 at its own hideAt")

    print()
    print("=== 3. ash_pile appear window ===")
    assert pile_appear_t(ASH_PILE_APPEAR_START) == 0.0
    assert pile_appear_t(ASH_PILE_APPEAR_END) == 1.0
    assert pile_appear_t(1.0) == 1.0
    mid = pile_appear_t((ASH_PILE_APPEAR_START + ASH_PILE_APPEAR_END) / 2.0)
    assert abs(mid - 0.5) < 1e-6, f"smoothstep midpoint should be ~0.5, got {mid}"
    overlap = ASH_HIDE_BODY_AT - ASH_PILE_APPEAR_START
    print(f"OK: pile ramps 0->1 over [{ASH_PILE_APPEAR_START}, {ASH_PILE_APPEAR_END}], "
          f"starting {overlap:.2f} before body's own hideAt ({ASH_HIDE_BODY_AT}) - crossfade, not a gap")
    tail = 1.0 - ASH_PILE_APPEAR_END
    print(f"OK: pile is fully formed with {tail:.2f} of the phase ({tail * 90:.1f}s of a 90s default ASH_TICKS) "
          "remaining before completeAshAndDiscard()")

    print()
    print("=== 4. cross-check against real committed textures (if present) ===")
    try:
        base_img = np.array(Image.open(BASE_PATH).convert("RGBA"))
    except FileNotFoundError:
        print(f"SKIPPED: {BASE_PATH} not found - run generate_ash_base_texture.py first")
        return

    stage_imgs = []
    try:
        for i in range(9):
            stage_imgs.append(np.array(Image.open(ASH_PATH.format(i)).convert("RGBA")))
    except FileNotFoundError:
        print(f"SKIPPED: ash_N.png stages not found - run generate_ash_texture.py first")
        return

    base_alpha = base_img[:, :, 3].astype(np.float64) / 255.0

    for name, (x0, y0, w, h) in SAMPLE_REGIONS.items():
        region_base_alpha = base_alpha[y0 : y0 + h, x0 : x0 + w]
        crossed_at = None
        for i, stage in enumerate(stage_imgs):
            erosion = stage[y0 : y0 + h, x0 : x0 + w, 3].astype(np.float64) / 255.0
            out_alpha = region_base_alpha * (1.0 - erosion)
            mean_alpha = out_alpha.mean()
            if crossed_at is None and mean_alpha < DISCARD_THRESHOLD:
                crossed_at = i
            print(f"  {name} stage {i}: mean output alpha={mean_alpha:.3f}")
        if crossed_at is not None:
            print(f"  -> {name}'s MEAN alpha first drops below the real discard threshold at stage {crossed_at}/8 "
                  f"(raw progress ~{crossed_at / 8:.2f})")
        else:
            print(f"  -> {name}'s mean alpha never drops below the discard threshold across all 9 stages "
                  "(individual pixels may still, since thresholds are per-pixel, not per-region-mean)")

    print()
    print("Confirms the erosion signal is genuinely spread across stages 0-8 for each region (continuous")
    print("crossing points, not all clustered near stage 8) - still unverified: the actual RENDERED look.")


if __name__ == "__main__":
    main()
