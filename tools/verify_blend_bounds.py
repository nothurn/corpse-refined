"""
Standalone numerical re-implementation of BlendedTextureCache.blend()'s pixel
math (lerpPixel/compositeOver/pack, and the base-bounds clamping), run
against synthetic images shaped like the real crash: a 64x64 stageLow/stageHigh
pair (ash_N.png format) composited onto a 64x32 base (skeleton.png's actual
legacy mob-texture format). This environment can't launch Minecraft or call
the real Java code, so this is the closest available check that the
clamping logic (a) never indexes outside the base image's real bounds and
(b) produces a correctly-sized, fully-opaque result - the two properties the
crash report showed were violated before that fix.

NOTE (BUG 3 fix, see .ai-brain.md): the real ASH-phase call site no longer
actually hits this 64x64-vs-64x32 mismatch in practice - PlayerCorpseRenderer
now passes ash_base.png (64x64, UV-correct, see
tools/generate_ash_base_texture.py) instead of vanilla skeleton.png as the
base texture, so BlendedTextureCache's clamp is never exercised by that
caller anymore. This test still matters: the clamp itself remains real,
correct, general-purpose defensive code in blend() for any future mismatched
baseTexture, and this is the only thing that regression-tests it. See
tools/verify_ash_base_texture.py for the check that's actually load-bearing
for the current ASH-phase base texture's correctness.
"""
import numpy as np

STAGE_W, STAGE_H = 64, 64
BASE_W, BASE_H = 64, 32


def pack(r, g, b, a):
    # Matches BlendedTextureCache.pack(): R in the low byte, then G, B, A high byte.
    return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF)


def unpack(px):
    return px & 0xFF, (px >> 8) & 0xFF, (px >> 16) & 0xFF, (px >> 24) & 0xFF


def lerp_pixel(frm, to, t):
    fr, fg, fb, fa = unpack(frm)
    tr, tg, tb, ta = unpack(to)
    r = round(fr + (tr - fr) * t)
    g = round(fg + (tg - fg) * t)
    b = round(fb + (tb - fb) * t)
    a = round(fa + (ta - fa) * t)
    return pack(r, g, b, a)


def composite_over(overlay, base):
    or_, og, ob, oa = unpack(overlay)
    br, bg, bb, _ = unpack(base)
    alpha = oa / 255.0
    r = round(or_ * alpha + br * (1.0 - alpha))
    g = round(og * alpha + bg * (1.0 - alpha))
    b = round(ob * alpha + bb * (1.0 - alpha))
    return pack(r, g, b, 255)


def make_synthetic(width, height, seed):
    rng = np.random.default_rng(seed)
    out = np.zeros((height, width), dtype=np.uint32)
    for y in range(height):
        for x in range(width):
            r, g, b, a = rng.integers(0, 256, size=4)
            out[y, x] = pack(int(r), int(g), int(b), int(a))
    return out


def blend(low, high, frac, base):
    """Direct port of BlendedTextureCache.blend(), including the fix:
    base reads are clamped into base's own bounds rather than assumed to
    match stageLow/stageHigh's dimensions."""
    height, width = low.shape
    result = np.zeros((height, width), dtype=np.uint32)
    base_h, base_w = (base.shape if base is not None else (0, 0))

    for y in range(height):
        for x in range(width):
            blended = lerp_pixel(int(low[y, x]), int(high[y, x]), frac)
            if base is not None:
                base_x = min(x, base_w - 1)
                base_y = min(y, base_h - 1)
                blended = composite_over(blended, int(base[base_y, base_x]))
            result[y, x] = blended
    return result


def main():
    low = make_synthetic(STAGE_W, STAGE_H, seed=1)
    high = make_synthetic(STAGE_W, STAGE_H, seed=2)
    base = make_synthetic(BASE_W, BASE_H, seed=3)

    # 1. This is the exact shape mismatch from the crash report (64,32) vs (64,64) -
    #    confirm it no longer raises (the old code's unclamped base.getPixelRGBA(x, y)
    #    for y >= 32 is exactly what threw "outside of image bounds").
    result = blend(low, high, 0.5, base)

    assert result.shape == (STAGE_H, STAGE_W), f"expected {(STAGE_H, STAGE_W)}, got {result.shape}"

    alphas = (result >> 24) & 0xFF
    assert np.all(alphas == 255), "compositeOver must force full opacity everywhere"

    # 2. Rows within base's real bounds (y < 32) must match compositing against the
    #    real base pixel at that (x, y) - the fix must not perturb in-bounds behavior.
    for y in (0, 15, 31):
        for x in (0, 10, 63):
            expected = composite_over(lerp_pixel(int(low[y, x]), int(high[y, x]), 0.5), int(base[y, x]))
            actual = int(result[y, x])
            assert actual == expected, f"in-bounds mismatch at ({x},{y}): {actual:#010x} != {expected:#010x}"

    # 3. Rows beyond base's real bounds (y >= 32) must clamp to base's last real row (y=31),
    #    not crash and not silently read garbage/zero.
    for y in (32, 48, 63):
        for x in (0, 10, 63):
            expected = composite_over(lerp_pixel(int(low[y, x]), int(high[y, x]), 0.5), int(base[31, x]))
            actual = int(result[y, x])
            assert actual == expected, f"clamped-row mismatch at ({x},{y}): {actual:#010x} != {expected:#010x}"

    # 4. base=None path (the shroud erosion crossfade never passes a baseTexture) must be
    #    completely untouched by the fix: plain lerp, no compositing, alpha preserved from lerp.
    no_base_result = blend(low, high, 0.5, None)
    for y in (0, 32, 63):
        for x in (0, 32, 63):
            expected = lerp_pixel(int(low[y, x]), int(high[y, x]), 0.5)
            actual = int(no_base_result[y, x])
            assert actual == expected, f"no-base mismatch at ({x},{y}): {actual:#010x} != {expected:#010x}"

    print("OK: 64x64 stages + 64x32 base blended without error")
    print(f"  result shape = {result.shape} (expected {(STAGE_H, STAGE_W)})")
    print("  all output pixels fully opaque (alpha=255): PASS")
    print("  in-bounds (y<32) rows match direct base compositing: PASS")
    print("  out-of-bounds (y>=32) rows clamp to base's last real row: PASS")
    print("  base=None path (shroud crossfade) unaffected by the fix: PASS")


if __name__ == "__main__":
    main()
