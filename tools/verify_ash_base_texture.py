"""
Regression check for the BUG 3 fix: confirms ash_base.png
(src/main/resources/assets/playercorpse/textures/entity/ash_base.png) is
actually UV-correct for the player-skin layout the ash overlay and
BlendedTextureCache use, not just "some 64x64 image".

This environment cannot render Minecraft, so it cannot confirm the visual
result looks right - it confirms the structural properties that would make
it WRONG if violated:
  1. Exactly 64x64 (BlendedTextureCache's Math.min clamp should never
     actually trigger for this base texture anymore - if this fails, the
     old wrong-body-part bug is back in some form).
  2. left_arm/left_leg are an exact horizontal mirror of right_arm/right_leg
     (the same left=mirror(right) assumption SkeletonModel's own geometry
     makes) - not accidentally left blank/transparent or copy-pasted
     unmirrored.
  3. The five shared-texOffs regions (head, hat, body, right_arm, right_leg)
     are not empty/fully-transparent - real pixel data was actually copied
     in, not skipped.

Run from the tools/ directory after regenerating ash_base.png:
  python verify_ash_base_texture.py
Requires: Pillow (pip install Pillow)
"""
from PIL import Image

OUT_PATH = "../src/main/resources/assets/playercorpse/textures/entity/ash_base.png"

HEAD = (0, 0, 32, 16)
HAT = (32, 0, 32, 16)
BODY = (16, 16, 24, 16)
RIGHT_ARM = (40, 16, 8, 14)
RIGHT_LEG = (0, 16, 8, 14)
LEFT_ARM_DST = (32, 48, 8, 14)
LEFT_LEG_DST = (16, 48, 8, 14)
PILE_PATCH = (34, 3, 24, 9)


def region_pixels(img, box):
    x0, y0, w, h = box
    return [img.getpixel((x, y)) for y in range(y0, y0 + h) for x in range(x0, x0 + w)]


def mirrored_pixels(img, box):
    x0, y0, w, h = box
    return [img.getpixel((x0 + (w - 1 - dx), y0 + dy)) for dy in range(h) for dx in range(w)]


def main():
    img = Image.open(OUT_PATH).convert("RGBA")

    assert img.size == (64, 64), f"expected 64x64, got {img.size}"
    print(f"OK: ash_base.png is {img.size} - matches ash_N.png stage dimensions exactly")

    # hat is excluded here: skeleton.png's own hat region is legitimately fully
    # transparent in the real vanilla asset (skeletons have no hair/hat overlay
    # layer at all) - confirmed via inspect_uv_mapping.py against the real file,
    # not an artifact of this generator. Only assert non-transparency for
    # regions skeleton.png actually draws something in.
    for name, box in (("head", HEAD), ("body", BODY), ("right_arm", RIGHT_ARM), ("right_leg", RIGHT_LEG)):
        pixels = region_pixels(img, box)
        non_transparent = sum(1 for p in pixels if p[3] != 0)
        assert non_transparent > 0, f"{name} region is fully transparent - skeleton.png data was not copied in"
        print(f"OK: {name} region has real pixel data ({non_transparent}/{len(pixels)} non-transparent px)")

    right_arm_px = region_pixels(img, RIGHT_ARM)
    left_arm_px = mirrored_pixels(img, LEFT_ARM_DST)
    assert right_arm_px == left_arm_px, "left_arm is not an exact horizontal mirror of right_arm"
    print("OK: left_arm is an exact horizontal mirror of right_arm")

    right_leg_px = region_pixels(img, RIGHT_LEG)
    left_leg_px = mirrored_pixels(img, LEFT_LEG_DST)
    assert right_leg_px == left_leg_px, "left_leg is not an exact horizontal mirror of right_leg"
    print("OK: left_leg is an exact horizontal mirror of right_leg")

    # 2026-08-12, new: the reserved ash-pile UV patch (inside the hat rectangle, but never
    # simultaneously visible with it - see PlayerCorpseRenderer.buildAshSkeletonRoot) must be
    # fully opaque here, unlike the rest of the hat region - it needs real, stable pixel data
    # for the pile to actually be visible once ashPile scales in.
    pile_pixels = region_pixels(img, PILE_PATCH)
    non_transparent = sum(1 for p in pile_pixels if p[3] != 0)
    assert non_transparent == len(pile_pixels), (
        f"ash_pile UV patch is not fully opaque - {non_transparent}/{len(pile_pixels)} non-transparent, "
        "the pile would be partially invisible"
    )
    print(f"OK: ash_pile UV patch is fully opaque ({non_transparent}/{len(pile_pixels)} px)")

    print()
    print("All structural UV checks pass. Still unverified: the actual rendered")
    print("look (bone color/shading reading correctly through the ash overlay) -")
    print("this environment cannot launch Minecraft.")


if __name__ == "__main__":
    main()
