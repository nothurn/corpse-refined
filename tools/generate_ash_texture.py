#!/usr/bin/env python3
"""
generate_ash_texture.py

Generates the 9 ash-phase overlay textures (ash_0.png .. ash_8.png,
64x64, matching the player-skin UV layout the shroud_erosion_*.png textures
already use) for PlayerCorpse's ASH decay phase, per RECOVERY.md section 7.

RECOVERY.md says this reuses `make_noise_field` from `generate_shroud_texture.py`,
generalized for non-square canvases. That script did NOT survive the
2026-07-29 data loss (nothing under B:\Claude matches its name). This is a
from-scratch reimplementation of the same idea - make_noise_field below is
written generalized for non-square canvases from the start, since there was
no surviving square-only version to generalize FROM. Treat the noise
approach as a faithful re-derivation of the documented intent, not a
recovered original.

Pipeline per stage i in 0..8 (stage progress p = i/8):
  1. Build a per-pixel EXTREMITY WEIGHT map from the player skin's UV layout
     (1.0 = fingertip/rib-end/skull-crown, 0.0 = torso core) so those regions
     ash before the torso, per RECOVERY.md point 1. The exact UV boundaries
     below are the standard 64x64 Minecraft skin layout as best recalled -
     NOT verified against real in-game rendering, since this environment
     cannot launch Minecraft. Flag for a real visual check once this mod
     actually renders.
  2. make_noise_field: raw per-pixel random noise, smoothed (box blur).
     Smoothing alone leaves a bell-shaped ("normal-like") value distribution
     by the central limit theorem - averaging neighboring random samples
     clusters most output values near the middle of the range. That was the
     historical bug RECOVERY.md documents ("68% of pixels in 2 of 8 histogram
     buckets" - "nothing, then everything" instead of even crumbling), fixed
     by local histogram equalization (step 4) before thresholding, not by
     changing the blur itself.
  3. EXTREMITY_SHRINK: before equalizing, pixels in high-extremity regions
     get their noise deviation from the local mean SCALED UP (not down,
     despite the name - "shrink" refers to shrinking the region that stays
     uniform, not the noise itself). Historical bug: extremity regions
     clamped to 100% alpha with no variance once mostly ashed. Widening
     their noise spread keeps visible per-pixel randomness there even at
     high ash%, instead of a flat clamped block.
  4. Local histogram equalization on the (now extremity-widened) noise
     field, flattening its distribution so a linear stage-progress sweep
     reveals ash pixels evenly instead of in one sudden burst.
  5. Per-pixel ash threshold = equalized_noise, shifted down in
     high-extremity regions so those pixels cross their own threshold at a
     lower overall stage progress than torso-core pixels. Each pixel gets a
     small smooth transition band around its own threshold (not a hard
     step), so individual pixels still ramp in opacity rather than popping.
  6. Ashed pixels are colored DUST_HIGHLIGHT - a pale, desaturated color -
     NOT the rejected warm EMBER_COLOR (210, 95, 30). RECOVERY.md: "wieso
     sollte es glühen, wenn es eigentlich nur verfällt" (why would it glow,
     when it's actually just decaying).

Output alpha at each pixel represents how "ashed" that pixel is at this
stage - this is composited onto the skeleton texture via BlendedTextureCache
(RGB shown = DUST_HIGHLIGHT, blended by alpha over the skeleton base), never
rendered as its own separate transparent layer.

--- 2026-08-11 retune: "reads as pale disconnected fragments, not coherent
crumbling ash" (Noah confirmed this is the actual complaint - a visual LOOK
problem, not the ash-pacing/timing issue already fixed in session
2026-08-11c). Investigated concretely before changing anything (scratch
scripts, not preserved - this environment cannot render Minecraft, only
sample real pixel data and re-derive the generator's own arrays):

  - Sampled ash_base.png (real vanilla skeleton.png pixel data, from the
    BUG 3 fix) at the 5 UV regions the renderer actually samples: mean bone
    RGB is (167, 167, 167), a fairly neutral mid-grey. The old
    DUST_HIGHLIGHT (215, 210, 200) was 72.0 RGB-euclidean / +43 luma above
    that - a large, stark jump from mid-grey bone to near-white on any pixel
    that flips to full ash alpha. Confirmed this against the REAL composited
    color (BlendedTextureCache.compositeOver's actual src-over-dst formula,
    re-implemented and run against ash_base.png + the committed ash_2..
    ash_5.png), not just the raw overlay color.
  - Re-derived this script's own extremity_weight/equalized_noise arrays
    (importable, since this generator is deterministic and seeded) and
    traced alpha along columns running from an extremity region center
    (HEAD_TOP crown, RIGHT_ARM_HAND) straight into the immediately adjacent
    non-extremity pixels. Found a real, measurable seam: at stage 2 (of 8),
    the whole HEAD_TOP region was already alpha ~1.0 (fully opaque dust)
    while pixels 1-2px away in the core were still alpha 0.0 (completely
    untouched bone) - a swing from 0 to 1 in about 1-2 pixels, not a
    gradient. Measured properly (mean alpha of weight>=0.9 pixels minus mean
    alpha of their weight==0 neighbors within 4px, across the whole canvas,
    every stage): the old EXTREMITY_SHRINK=1.8/EXTREMITY_EARLY_BIAS=0.35
    combo produced a max gap of 0.360 (out of 1.0) - both knobs compound
    multiplicatively on the same weight=1.0 pixels (BIAS shifts their
    threshold down ~35%, SHRINK simultaneously widens their transition
    softness ~2.8x), so a deep-extremity pixel and its untouched neighbor
    can be on opposite sides of a near-binary flip within the same stage.
    This alone explains "disconnected fragments" independent of color: even
    at matched grey tones, a hard alpha edge reads as a separate floating
    piece.
  - Swept EXTREMITY_SHRINK/EXTREMITY_EARLY_BIAS candidates numerically
    against three invariants that must hold (extremities still ash ahead of
    the torso core at every stage; per-pixel variance in extremity regions
    stays well above 0, i.e. the historical "clamped flat block" bug from
    RECOVERY.md point 2 does not come back; overall alpha progression stays
    monotonic 0->8). SHRINK=1.0/BIAS=0.20 cut the max gap from 0.360 to
    0.276 (a ~23% reduction, and the early-stage gaps that matter most for
    "first visible ash" dropped even further - stage 0: 0.149->0.087, stage
    1: 0.288->0.214) while holding both invariants (extremity-ahead-of-core
    held at every stage with slack to spare; extremity variance at stage
    4/6 went UP, not down, vs. the old values - if anything more per-pixel
    grain survives, not less). Weakening EARLY_BIAS alone or both knobs
    further (tested down to BIAS=0.15) broke the extremity-ahead-of-core
    invariant at late stages - rejected.

  Net fix, both applied together since the investigation found two separate,
  independently-confirmed contributors to the same symptom:
    DUST_HIGHLIGHT: (215, 210, 200) -> (188, 184, 176) - keeps the same pale,
      desaturated, slightly-warm hue direction (still clearly reads as dust,
      not painted-on grey), but the offset above real bone tone is now ~28
      RGB-euclidean / +17 luma instead of ~72 / +43 - a ~60% reduction in
      the color jump when a pixel flips to full ash.
    EXTREMITY_SHRINK: 1.8 -> 1.0, EXTREMITY_EARLY_BIAS: 0.35 -> 0.20 - same
      documented purpose (extremities ash first, extremity regions keep
      per-pixel variance instead of clamping flat), less compounding, so the
      extremity-vs-core boundary spans a visible transition band instead of
      flipping almost the entire region to full opacity in 1-2 stages while
      the immediately adjacent core is still untouched.

  Still an untested visual judgment call, same as everything else in this
  file - this environment cannot render Minecraft. The numbers above are
  concrete, reproducible measurements of the actual generated pixel data,
  not guesses, but whether the result now reads as coherent crumbling ash
  in-game is Noah's call on the next playtest.

Usage: python generate_ash_texture.py
Requires: Pillow, numpy (pip install Pillow numpy)
"""

import math
import random

import numpy as np
from PIL import Image

WIDTH = 64
HEIGHT = 64
STAGE_COUNT = 9

DUST_HIGHLIGHT = (188, 184, 176)
EDGE_SOFTNESS = 0.12
EXTREMITY_SHRINK = 1.0
EXTREMITY_EARLY_BIAS = 0.20
NOISE_BLUR_RADIUS = 2
RANDOM_SEED = 20260729

# Approximate standard 64x64 Minecraft player-skin UV regions used to build
# the extremity weight map. Format: (x0, y0, x1, y1) rectangles, exclusive of
# x1/y1. NOT pixel-verified in-game - see module docstring.
HEAD_TOP = (8, 0, 16, 8)
HEAD_BOTTOM = (16, 0, 24, 8)
BODY_FRONT_BOTTOM_EDGE = (20, 29, 28, 32)
BODY_BACK_BOTTOM_EDGE = (32, 29, 40, 32)
RIGHT_ARM_HAND = (44, 29, 48, 32)
RIGHT_ARM_HAND_BACK = (48, 29, 52, 32)
LEFT_ARM_HAND = (36, 45, 40, 48)
LEFT_ARM_HAND_BACK = (40, 45, 44, 48)
RIGHT_LEG_FOOT = (4, 29, 8, 32)
LEFT_LEG_FOOT = (20, 45, 24, 48)

EXTREMITY_REGIONS = [
    HEAD_TOP,
    HEAD_BOTTOM,
    BODY_FRONT_BOTTOM_EDGE,
    BODY_BACK_BOTTOM_EDGE,
    RIGHT_ARM_HAND,
    RIGHT_ARM_HAND_BACK,
    LEFT_ARM_HAND,
    LEFT_ARM_HAND_BACK,
    RIGHT_LEG_FOOT,
    LEFT_LEG_FOOT,
]


def build_extremity_weight_map(width, height):
    """1.0 at the center of an extremity region, fading to 0.0 a few pixels
    outside it, so the transition into the torso core is soft rather than a
    hard rectangle edge."""
    weight = np.zeros((height, width), dtype=np.float64)
    falloff = 3.0
    for (x0, y0, x1, y1) in EXTREMITY_REGIONS:
        cx = (x0 + x1) / 2.0
        cy = (y0 + y1) / 2.0
        half_w = max((x1 - x0) / 2.0, 1.0)
        half_h = max((y1 - y0) / 2.0, 1.0)
        for y in range(height):
            for x in range(width):
                dx = max(abs(x + 0.5 - cx) - half_w, 0.0)
                dy = max(abs(y + 0.5 - cy) - half_h, 0.0)
                dist = math.sqrt(dx * dx + dy * dy)
                w = max(0.0, 1.0 - dist / falloff)
                if w > weight[y, x]:
                    weight[y, x] = w
    return weight


def make_noise_field(width, height, rng):
    """Generalized (non-square-safe) smoothed value-noise field in [0, 1].
    Raw uniform noise, box-blurred - deliberately NOT yet flattened in
    distribution; see local_histogram_equalize for that step."""
    raw = np.array([[rng.random() for _ in range(width)] for _ in range(height)], dtype=np.float64)
    return box_blur(raw, NOISE_BLUR_RADIUS)


def box_blur(field, radius):
    height, width = field.shape
    padded = np.pad(field, radius, mode="reflect")
    out = np.zeros_like(field)
    window = 2 * radius + 1
    for y in range(height):
        for x in range(width):
            block = padded[y : y + window, x : x + window]
            out[y, x] = block.mean()
    return out


def apply_extremity_shrink(noise_field, extremity_weight):
    """Widens each pixel's deviation from the field's global mean, scaled up
    by how high that pixel's extremity weight is - see module docstring
    point 3. Re-clips to [0, 1] afterward since widening can push values out
    of range."""
    mean = float(noise_field.mean())
    scale = 1.0 + EXTREMITY_SHRINK * extremity_weight
    widened = mean + (noise_field - mean) * scale
    return np.clip(widened, 0.0, 1.0)


def local_histogram_equalize(field, tile_size=16):
    """Simple tiled local histogram equalization: within each tile, rank
    every pixel by value and remap it to its rank fraction, then blend tiles
    at their borders isn't attempted here (tiles are small relative to the
    64x64 canvas and EDGE_SOFTNESS already smooths the final threshold, so
    hard tile seams in the underlying noise aren't visually load-bearing).
    This directly targets the documented bug: un-equalized smoothed noise is
    bell-shaped (values cluster mid-range), so a linear progress sweep would
    reveal "nothing, then everything" instead of gradually and evenly."""
    height, width = field.shape
    out = np.zeros_like(field)
    for ty in range(0, height, tile_size):
        for tx in range(0, width, tile_size):
            y1 = min(ty + tile_size, height)
            x1 = min(tx + tile_size, width)
            tile = field[ty:y1, tx:x1]
            flat = tile.flatten()
            order = np.argsort(flat)
            ranks = np.empty_like(order, dtype=np.float64)
            ranks[order] = np.arange(len(flat))
            equalized = ranks / max(len(flat) - 1, 1)
            out[ty:y1, tx:x1] = equalized.reshape(tile.shape)
    return out


def smooth_threshold(progress, threshold, softness):
    """Soft step centered on `threshold`, width `softness`, instead of a hard
    boolean compare - gives each pixel its own gradual opacity ramp as
    overall stage progress crosses that pixel's individual threshold."""
    t = (progress - threshold) / softness + 0.5
    return float(np.clip(t, 0.0, 1.0))


def generate_stage(stage_index, extremity_weight, equalized_noise):
    progress = stage_index / (STAGE_COUNT - 1)
    threshold = equalized_noise * (1.0 - EXTREMITY_EARLY_BIAS * extremity_weight)
    # Softness itself widens with extremity weight: EXTREMITY_SHRINK's actual job is keeping
    # per-pixel variance visible in these regions for longer, not just shifting them earlier -
    # a wider per-pixel transition band does that far more directly than only widening the
    # pre-equalization noise (verified: with a fixed softness, extremity regions fully saturated
    # to a flat, variance-free block by stage 4 - exactly the historical bug this is meant to avoid).
    softness = EDGE_SOFTNESS * (1.0 + EXTREMITY_SHRINK * extremity_weight)
    image = Image.new("RGBA", (WIDTH, HEIGHT), (0, 0, 0, 0))
    pixels = image.load()
    for y in range(HEIGHT):
        for x in range(WIDTH):
            alpha_fraction = smooth_threshold(progress, threshold[y, x], softness[y, x])
            alpha = round(alpha_fraction * 255)
            pixels[x, y] = (DUST_HIGHLIGHT[0], DUST_HIGHLIGHT[1], DUST_HIGHLIGHT[2], alpha)
    return image


def main():
    rng = random.Random(RANDOM_SEED)
    extremity_weight = build_extremity_weight_map(WIDTH, HEIGHT)
    raw_noise = make_noise_field(WIDTH, HEIGHT, rng)
    widened_noise = apply_extremity_shrink(raw_noise, extremity_weight)
    equalized_noise = local_histogram_equalize(widened_noise)

    for i in range(STAGE_COUNT):
        image = generate_stage(i, extremity_weight, equalized_noise)
        out_path = f"../src/main/resources/assets/playercorpse/textures/entity/ash_{i}.png"
        image.save(out_path)
        print(f"wrote {out_path}")


if __name__ == "__main__":
    main()
