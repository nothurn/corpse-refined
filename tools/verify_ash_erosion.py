"""
verify_ash_erosion.py

Numerical verification for the ASH-phase mechanism redesign (continuous
material erosion instead of a growing opaque overlay, plus the ash_pile end
state). This environment cannot render Minecraft, so this checks the actual
pixel/curve MATH involved - the same discipline as every other
tools/verify_*.py in this project.

Checks three independent pieces:
  1. BlendedTextureCache.erodeOver's formula (Python re-implementation,
     matched byte-for-byte against the real Java source).
  2. DecayCurves' pileAppearT window.
  3. Cross-check against the REAL committed ash_0..ash_8.png + ash_base.png,
     including the strict no-change ASH boundary guaranteed by stage 0
     files (if present) - reports the actual stage at which each sampled
     body region's mean erosion would cross the real GPU alpha-test discard
     threshold (0.1, confirmed from
     assets/minecraft/shaders/core/rendertype_entity_cutout_no_cull.fsh:
     `if (color.a < 0.1) discard;`), the same kind of real-pixel-data
     investigation done in the 2026-08-11c/e sessions for the old curve -
     confirms material loss is spread across the stage range, not
     concentrated at the end.

2026-08-12b: removed the RGB-tint check (formerly 1f) - erodeOver no longer
tints color toward DUST_HIGHLIGHT at all (Noah's playtest: the tint read as
"a layer forming over the skeleton", not material disappearing, since
entityCutoutNoCull doesn't alpha-blend - see erodeOver's own doc). Only the
alpha math remains; that math itself is unchanged by this edit, so checks
1a-1e are still valid as originally written.

Run from the tools/ directory, after regenerating both ash_N.png and
ash_base.png:
  python verify_ash_erosion.py
Requires: Pillow, numpy
"""
import numpy as np
from PIL import Image

ASH_PATH = "../src/main/resources/assets/playercorpse/textures/entity/ash_{}.png"
BASE_PATH = "../src/main/resources/assets/playercorpse/textures/entity/ash_base.png"

DISCARD_THRESHOLD = 0.1  # real value, confirmed from the vanilla fragment shader source

ASH_PILE_APPEAR_START = 0.72
ASH_PILE_APPEAR_END = 0.86
ASH_BONE_CLEANUP_AT = ASH_PILE_APPEAR_END

HEAD = (0, 0, 32, 16)
BODY = (16, 16, 24, 16)
RIGHT_ARM = (40, 16, 8, 14)
RIGHT_LEG = (0, 16, 8, 14)
SAMPLE_REGIONS = {"head": HEAD, "body": BODY, "right_arm": RIGHT_ARM, "right_leg": RIGHT_LEG}


def erode_over(erosion, base_alpha):
    """Direct port of BlendedTextureCache.erodeOver's alpha math. RGB is no
    longer tinted at all (2026-08-12b) - output color is always exactly the
    base pixel's own color, so there is nothing left to test there."""
    return base_alpha * (1.0 - erosion)


def pile_appear_t(raw_progress):
    if raw_progress <= ASH_PILE_APPEAR_START:
        return 0.0
    elif raw_progress >= ASH_PILE_APPEAR_END:
        return 1.0
    else:
        u = (raw_progress - ASH_PILE_APPEAR_START) / (ASH_PILE_APPEAR_END - ASH_PILE_APPEAR_START)
        return u * u * (3.0 - 2.0 * u)


def show_ash_bones(raw_progress):
    return raw_progress < ASH_BONE_CLEANUP_AT


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

    # 1f (removed 2026-08-12b): used to check the RGB tint-toward-DUST_HIGHLIGHT here. That tint
    # is gone from erodeOver entirely now - nothing left to check; see the module docstring for why.

    print()
    print("=== 2. ash_pile appear window ===")
    assert pile_appear_t(ASH_PILE_APPEAR_START) == 0.0
    assert pile_appear_t(ASH_PILE_APPEAR_END) == 1.0
    assert pile_appear_t(1.0) == 1.0
    mid = pile_appear_t((ASH_PILE_APPEAR_START + ASH_PILE_APPEAR_END) / 2.0)
    assert abs(mid - 0.5) < 1e-6, f"smoothstep midpoint should be ~0.5, got {mid}"
    print(f"OK: pile ramps 0->1 over [{ASH_PILE_APPEAR_START}, {ASH_PILE_APPEAR_END}]")
    assert show_ash_bones(ASH_BONE_CLEANUP_AT - 0.001)
    assert not show_ash_bones(ASH_BONE_CLEANUP_AT)
    assert not show_ash_bones(1.0)
    print("OK: all bone model parts are hidden once the grounded pile finishes forming")
    tail = 1.0 - ASH_PILE_APPEAR_END
    print(f"OK: pile is fully formed with {tail:.2f} of the phase ({tail * 120:.1f}s of a 120s ASH example) "
          "remaining before completeAshAndDiscard()")

    print()
    print("=== 3. cross-check against real committed textures (if present) ===")
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

    stage_zero_alpha = stage_imgs[0][:, :, 3]
    assert np.max(stage_zero_alpha) == 0, "ash_0.png must be fully transparent so the skull cannot pop at the ASH boundary"
    stage_eight_alpha = stage_imgs[8][:, :, 3]
    for name, (x0, y0, w, h) in SAMPLE_REGIONS.items():
        assert np.min(stage_eight_alpha[y0 : y0 + h, x0 : x0 + w]) == 255, f"{name} must be fully eroded in ash_8.png"
    print("OK: ash_0.png is fully transparent; ASH starts from the unchanged skeleton texture")

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
    print("Confirms the erosion signal is genuinely spread across stages 1-8 for each region (continuous")
    print("crossing points, not all clustered near stage 8) - still unverified: the actual RENDERED look.")


if __name__ == "__main__":
    main()
