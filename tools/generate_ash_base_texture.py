#!/usr/bin/env python3
"""
generate_ash_base_texture.py

BUG 3 fix: derives ash_base.png (64x64, player-skin UV layout) from vanilla
skeleton.png, replacing skeleton.png itself as ASH phase's BlendedTextureCache
base texture.

Why skeleton.png itself was wrong, not just crash-prone: it is a legacy 64x32
mob texture with its OWN UV layout, not a cropped 64x64 player skin. Read the
real decompiled sources (HumanoidModel.createMesh, SkeletonModel's own
createDefaultSkeletonMesh override, PlayerModel.createMesh - all pulled from
the NeoFormRuntime sourcesWithNeoForge cache, not assumed) and confirmed via
actual pixel data (tools/inspect_uv_mapping.py, run against skeleton.png
extracted from minecraft_1.21.1_client.jar) that:

  part        SkeletonModel texOffs   PlayerModel texOffs      same region?
  head        (0,0)   [inherited]     (0,0)   [inherited]      YES
  hat         (32,0)  [inherited]     (32,0)  [inherited]      YES
  body        (16,16) [inherited]     (16,16) [inherited]      YES
  right_arm   (40,16) [own override]  (40,16) [inherited]      YES
  right_leg   (0,16)  [inherited]     (0,16)  [inherited]      YES
  left_arm    (40,16) mirrored        (32,48) [own, y>=32]     NO
  left_leg    (0,16)  mirrored        (16,48) [own, y>=32]     NO

Five of six body parts share the EXACT same texOffs between the two models -
skeleton.png is correct data for those, just squeezed into a shorter 64x32
canvas. Only left_arm/left_leg live at y>=32 in the modern player-skin
layout, a row range skeleton.png does not have at all - the crash-fix's
Math.min clamp made those reads safe, but they land on skeleton.png's row 31,
which is the BODY's own bottom-edge pixel data (confirmed by
inspect_uv_mapping.py), not anything leg- or arm-shaped. That is the actual
"verbugt" visual bug: not a crash, a wrong-body-part read.

This tool builds a real 64x64 base texture that is UV-correct everywhere the
ash overlay and BlendedTextureCache ever sample it:
  1. Copies skeleton.png's real pixel data 1:1 for the five shared-texOffs
     regions (head, hat, body, right_arm, right_leg) - genuine Mojang bone
     shading, not synthesized.
  2. Derives left_arm/left_leg by horizontally mirroring the corresponding
     real right_arm/right_leg pixel data - the exact same left=mirror(right)
     assumption SkeletonModel's own geometry already makes for those two
     parts (createDefaultSkeletonMesh calls .mirror() on the SAME texOffs
     for left_arm/left_leg), just baked into a texture instead of applied
     live to geometry.
  3. Leaves every other pixel (jacket/sleeve/pants overlay regions, ear,
     cloak - none of which PlayerCorpseRenderer's ash path or corpse model
     ever use) transparent.

Once ash_base.png is 64x64 and UV-correct, BlendedTextureCache.blend()'s
Math.min clamp is never actually exercised by this call site again (base
and stage dimensions now match exactly) - the clamp itself stays in
BlendedTextureCache as valid general-purpose defensive code for any future
mismatched baseTexture, it's just not what makes THIS texture correct
anymore.

Requires a local copy of vanilla skeleton.png - NOT committed to this repo
(Mojang-owned asset). Extract it yourself, e.g.:
  jar xf <path-to-client.jar> assets/minecraft/textures/entity/skeleton/skeleton.png

Usage: python generate_ash_base_texture.py <path-to-vanilla-skeleton.png>
Requires: Pillow (pip install Pillow)
"""
import random
import sys

from PIL import Image

PILE_NOISE_SEED = 20260812
PILE_NOISE_SPREAD = 14  # +/- per channel, small enough to stay clearly one solid pile color

OUT_PATH = "../src/main/resources/assets/playercorpse/textures/entity/ash_base.png"

# texOffs rectangles as (x0, y0, w, h) - w/h are the standard Minecraft cube
# UV-unwrap size for each part's box dimensions (width = 2*(dx+dz), height =
# dy+dz), confirmed against the real decompiled sources, not guessed.
HEAD = (0, 0, 32, 16)  # texOffs(0,0), 8x8x8 head cube
HAT = (32, 0, 32, 16)  # texOffs(32,0), 8x8x8 hat cube (cubeDeformation.extend doesn't change UV size)
BODY = (16, 16, 24, 16)  # texOffs(16,16), 8x12x4 body cube
RIGHT_ARM = (40, 16, 8, 14)  # texOffs(40,16), 2x12x2 (SkeletonModel's own override, matches PlayerModel non-slim)
RIGHT_LEG = (0, 16, 8, 14)  # texOffs(0,16), 2x12x2, shared by SkeletonModel and PlayerModel

LEFT_ARM_DST = (32, 48, 8, 14)  # PlayerModel-only texOffs(32,48)
LEFT_LEG_DST = (16, 48, 8, 14)  # PlayerModel-only texOffs(16,48)

COPY_REGIONS = (HEAD, HAT, BODY, RIGHT_ARM, RIGHT_LEG)

# 2026-08-12, new: reserved swatch for PlayerCorpseRenderer's "ash_pile" ModelPart
# (buildAshSkeletonRoot, texOffs(34,3), box 6x3x6 -> UV footprint 24x9 =
# 2*(6+6) wide, 3+6 tall, matching CubeListBuilder's standard box-UV formula).
# Deliberately sits INSIDE the hat cube's own (32,0)-(64,16) UV rectangle - safe
# because the hat/head is always hidden (ASH_HIDE_HEAD_AT) well before the pile
# starts appearing (ASH_PILE_APPEAR_START), so the two are never visible at the
# same time and never compete for the same pixels on screen, only in the atlas.
# Painted with an OPAQUE, DUST_HIGHLIGHT-adjacent color here so the pile texture
# exists independent of generate_ash_texture.py's erosion sweep - see that
# script for the matching change that keeps this exact patch at alpha=0 in
# every ash_N.png stage overlay, so erodeOver's erosion formula never touches
# it (the pile is meant to be what's left AFTER erosion, not itself erodable).
PILE_PATCH = (34, 3, 24, 9)
# Same hue direction as DUST_HIGHLIGHT (188,184,176) but a bit darker/more
# saturated - a "settled pile of debris" should read a little denser/greyer
# than the pale in-progress dusting color, not identical to it.
PILE_COLOR = (150, 145, 136, 255)


def copy_region(src, dst, box):
    x0, y0, w, h = box
    dst.paste(src.crop((x0, y0, x0 + w, y0 + h)), (x0, y0))


def mirror_region(src, dst, src_box, dst_box):
    sx0, sy0, sw, sh = src_box
    dx0, dy0, _, _ = dst_box
    region = src.crop((sx0, sy0, sx0 + sw, sy0 + sh)).transpose(Image.FLIP_LEFT_RIGHT)
    dst.paste(region, (dx0, dy0))


def paint_pile_patch(dst, box, color):
    """Fills box with color, plus small per-pixel noise so the pile reads as a
    textured mound of debris rather than a flat plastic-looking block - still
    clearly one solid, opaque color at a glance, since PILE_NOISE_SPREAD is
    small relative to the color values themselves."""
    x0, y0, w, h = box
    rng = random.Random(PILE_NOISE_SEED)
    pixels = dst.load()
    r0, g0, b0, a0 = color
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            jitter = rng.randint(-PILE_NOISE_SPREAD, PILE_NOISE_SPREAD)
            r = max(0, min(255, r0 + jitter))
            g = max(0, min(255, g0 + jitter))
            b = max(0, min(255, b0 + jitter))
            pixels[x, y] = (r, g, b, a0)


def main():
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(1)

    skeleton = Image.open(sys.argv[1]).convert("RGBA")
    if skeleton.size != (64, 32):
        print(f"expected a 64x32 skeleton.png, got {skeleton.size} - is this really vanilla skeleton.png?")
        sys.exit(1)

    out = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    for box in COPY_REGIONS:
        copy_region(skeleton, out, box)

    mirror_region(skeleton, out, RIGHT_ARM, LEFT_ARM_DST)
    mirror_region(skeleton, out, RIGHT_LEG, LEFT_LEG_DST)
    paint_pile_patch(out, PILE_PATCH, PILE_COLOR)

    out.save(OUT_PATH)
    print(f"wrote {OUT_PATH}")


if __name__ == "__main__":
    main()
