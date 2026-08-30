package com.playercorpse.client.renderer;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import javax.annotation.Nullable;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import org.slf4j.Logger;

/**
 * Single-draw-call crossfade between two source stage textures, premixed
 * pixel-by-pixel on the CPU, cached per (entity, slot) and only regenerated
 * when (stageLow, stageHigh, frac, baseTexture) actually changes - in
 * practice once per tick at most, since phase progress only changes on tick
 * boundaries. Shared by the shroud erosion crossfade and the ash overlay
 * (RECOVERY.md section 7).
 *
 * WHY this exists, not just what it does: the historical "shroud flickers
 * back and forth for months" bug came from drawing two overlapping
 * translucent layers per frame - a low-stage and a high-stage texture with
 * complementary vertex alpha - to fake a crossfade. The GPU's draw order
 * between those two calls was never guaranteed, so which one painted on top
 * varied frame to frame, and the visible result flickered between the two
 * orderings. Blending the two source images into one real texture ahead of
 * time and issuing a single opaque-alpha draw call removes the ordering
 * question entirely, because there is only one draw call. It is also more
 * correct in its own right: the old two-draw approach's overlapping
 * complementary alphas mathematically bottom out around 75% combined opacity
 * at the midpoint of the fade (two non-fully-opaque layers can't sum past
 * that under standard "over" blending the way two DIFFERENT alpha values
 * would), which read as an unwanted dip in solidity exactly when the
 * crossfade should look most "in-between", not less present.
 *
 * When baseTexture is supplied, the blended (stageLow/stageHigh/frac) image
 * is ERODED onto it via erodeOver (see that method) rather than composited
 * as an opaque overlay - the base's own material progressively loses alpha
 * in proportion to the overlay's per-pixel value, instead of an increasingly
 * opaque highlight color being painted on top of an unchanging opaque base.
 * This is still how the ash overlay avoids being a second transparent layer
 * stacked on top of the skeleton texture ("Ergebnis vollständig deckend,
 * kein eigener Layer") - there is still only ever one draw, one texture, no
 * separate RenderLayer - but the RESULT is no longer forced fully opaque
 * (2026-08-12 redesign, see erodeOver's own doc for why the old
 * always-opaque formula made the ash phase read as a growing overlay rather
 * than genuine material loss).
 *
 * Accepted downside, per RECOVERY.md: the final blended texture generated
 * for a given (entity, slot) stays resident on the GPU for the rest of the
 * client session after that entity is removed, because RenderLayer has no
 * "entity removed" hook to trigger cleanup - a few KB per corpse. Every
 * regeneration WHILE the entity is alive properly releases its own previous
 * texture first (see getOrCreate below), so this leak is bounded to one
 * texture per (entity, slot) that ever existed, not one per tick.
 */
public final class BlendedTextureCache {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final Map<String, CacheEntry> CACHE = new HashMap<>();
   private static final Map<ResourceLocation, Float> MEAN_EROSION_CACHE = new HashMap<>();

   private BlendedTextureCache() {
   }

   /**
    * 2026-08-12c, new: mean alpha (= mean EROSION, per generate_ash_texture.py's own documented
    * meaning of that channel - see erodeOver's doc) across an entire ash_N.png stage texture,
    * loaded once and cached forever per ResourceLocation (these are static game assets, identical
    * for every corpse - unlike CACHE above, which is keyed per (entity, slot) because its inputs
    * genuinely vary per corpse).
    *
    * Exists so PlayerCorpseRenderer's ash-particle spawn rate (RECOVERY.md follow-up feature, not
    * in the original spec) can be driven by the SAME real per-pixel erosion data erodeOver itself
    * consumes, instead of an invented proxy signal - literally reads the alpha channel of the exact
    * two stage textures ashTextureFor() is already about to blend between, the same bytes erodeOver
    * turns into real holes. Whole-canvas mean (not restricted to the 5 UV regions the skeleton mesh
    * actually samples) is a deliberate simplification: the hat region and the ash_pile UV patch are
    * forced to alpha=0 in EVERY stage (generate_ash_texture.py's own PILE_PATCH override, and the
    * hat was never given real ash data either) - a caller that only ever wants the DELTA between two
    * stages (as this feature does) gets an unbiased result regardless, since a region that's
    * identically 0 in both stages contributes 0 to the difference either way. A caller wanting an
    * absolute per-stage value (not just a delta) would need the region-restricted version instead -
    * not needed for this feature, so not built.
    */
   public static float meanErosion(ResourceLocation stageTexture) {
      Float cached = MEAN_EROSION_CACHE.get(stageTexture);
      if (cached != null) {
         return cached;
      }

      NativeImage image = loadImage(stageTexture);
      long sum = 0L;
      int width = image.getWidth();
      int height = image.getHeight();
      for (int y = 0; y < height; y++) {
         for (int x = 0; x < width; x++) {
            sum += image.getPixelRGBA(x, y) >> 24 & 0xFF;
         }
      }

      float mean = width > 0 && height > 0 ? sum / 255.0F / (width * height) : 0.0F;
      MEAN_EROSION_CACHE.put(stageTexture, mean);
      return mean;
   }

   public static ResourceLocation getOrCreate(
      int entityId, String slot, ResourceLocation stageLow, ResourceLocation stageHigh, float frac, @Nullable ResourceLocation baseTexture
   ) {
      String cacheId = slot + "_" + entityId;
      CacheKey key = new CacheKey(stageLow, stageHigh, frac, baseTexture);
      CacheEntry existing = CACHE.get(cacheId);
      if (existing != null && existing.key.equals(key)) {
         return existing.location;
      }

      TextureManager textureManager = Minecraft.getInstance().getTextureManager();
      ResourceLocation location = ResourceLocation.fromNamespaceAndPath("playercorpse", "dynamic/" + cacheId);
      NativeImage blended = blend(stageLow, stageHigh, frac, baseTexture);
      DynamicTexture texture = new DynamicTexture(blended);
      texture.upload();
      // release() safely closes whatever was previously registered at this location (our own prior
      // generation for this entity/slot), so at most one live GPU texture per (entity, slot) exists
      // while the corpse is alive - only the final generation outlives despawn, per the accepted
      // tradeoff documented above.
      textureManager.release(location);
      textureManager.register(location, texture);
      CACHE.put(cacheId, new CacheEntry(key, location));
      return location;
   }

   private static NativeImage blend(ResourceLocation stageLow, ResourceLocation stageHigh, float frac, @Nullable ResourceLocation baseTexture) {
      NativeImage low = loadImage(stageLow);
      NativeImage high = loadImage(stageHigh);
      NativeImage base = baseTexture != null ? loadImage(baseTexture) : null;

      try {
         int width = low.getWidth();
         int height = low.getHeight();
         NativeImage result = new NativeImage(width, height, true);
         // baseTexture is not guaranteed to share stageLow/stageHigh's dimensions - e.g. the ash
         // overlay's base is vanilla's skeleton.png, a legacy 64x32 mob texture, composited under
         // 64x64 player-skin-format ash stage textures. Clamp reads into base's own bounds instead
         // of assuming equal size (that assumption is exactly what crashed with "outside of image
         // bounds" once a corpse reached ASH). This is provably inert for the skeleton case
         // specifically: SkeletonModel.createBodyLayer() declares LayerDefinition.create(mesh, 64,
         // 32) and every one of its cube texOffs falls within v=0-31 (confirmed by reading the
         // decompiled model), so the model never samples texture rows below its own declared
         // height in the first place - clamping the extra rows to the last real row of the base
         // image (rather than, say, treating them as transparent) is a safe, general fallback for
         // any mismatched baseTexture, not a skeleton-specific special case.
         int baseWidth = base != null ? base.getWidth() : 0;
         int baseHeight = base != null ? base.getHeight() : 0;

         for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
               int blended = lerpPixel(low.getPixelRGBA(x, y), high.getPixelRGBA(x, y), frac);
               if (base != null) {
                  int baseX = Math.min(x, baseWidth - 1);
                  int baseY = Math.min(y, baseHeight - 1);
                  blended = erodeOver(blended, base.getPixelRGBA(baseX, baseY));
               }

               result.setPixelRGBA(x, y, blended);
            }
         }

         return result;
      } finally {
         low.close();
         high.close();
         if (base != null) {
            base.close();
         }
      }
   }

   /**
    * NativeImage.getPixelRGBA/setPixelRGBA pack each pixel as R in the low
    * byte, then G, then B, then A in the high byte (confirmed against this
    * same class's existing logTextureStats method, which already unpacks
    * this exact layout) - NOT the same convention as FastColor.ARGB32's
    * vertex-tint packing used elsewhere in this renderer, so this method
    * deliberately does its own bit math rather than reusing that helper.
    */
   private static int lerpPixel(int from, int to, float t) {
      int fr = from & 0xFF;
      int fg = from >> 8 & 0xFF;
      int fb = from >> 16 & 0xFF;
      int fa = from >> 24 & 0xFF;
      int tr = to & 0xFF;
      int tg = to >> 8 & 0xFF;
      int tb = to >> 16 & 0xFF;
      int ta = to >> 24 & 0xFF;
      int r = Math.round(fr + (tr - fr) * t);
      int g = Math.round(fg + (tg - fg) * t);
      int b = Math.round(fb + (tb - fb) * t);
      int a = Math.round(fa + (ta - fa) * t);
      return pack(r, g, b, a);
   }

   /**
    * 2026-08-12 mechanism redesign - replaces the old "src over dst" overlay
    * compositing entirely (not a re-tune; see .ai-brain.md and DecayCurves'
    * class doc for the full design reasoning). The old formula
    * (outAlpha = srcAlpha + dstAlpha*(1-srcAlpha)) mathematically CANNOT
    * produce an output alpha below the base's own alpha - wherever the
    * skeleton base is opaque (dstAlpha=1, i.e. every load-bearing region),
    * outAlpha reduces to exactly 1.0 regardless of how much "ash" is
    * composited in. That's why the ash phase visually read as an
    * increasingly opaque highlight color painted on top of an unchanging
    * opaque surface ("a growing overlay"), never as the surface itself
    * losing material - the math structurally could not produce that result.
    *
    * New interpretation: overlay alpha (from ash_N.png) is no longer "how
    * much ash to paint over this pixel" but "how ERODED this pixel already
    * is" - 0 = still intact material, 1 = fully gone. Output alpha is the
    * base's own alpha multiplied down by (1 - erosion), so a fully-eroded
    * pixel genuinely goes to 0 alpha. Rendered through entityCutoutNoCull
    * (confirmed straight from the real shader source,
    * assets/minecraft/shaders/core/rendertype_entity_cutout_no_cull.fsh:
    * `if (color.a < 0.1) discard;` - alpha-tested, no blending), a pixel
    * with baseAlpha=1 is discarded outright - a real hole in the geometry -
    * once its own erosion exceeds 0.9. Since ash_N.png's per-pixel
    * threshold is already randomized/equalized across the whole canvas (see
    * generate_ash_texture.py), different pixels cross that 0.9 point at
    * different overall phase-progress values, so across thousands of pixels
    * this reads as continuous, staggered crumbling rather than a single
    * instant or a uniform fade.
    *
    * 2026-08-12b - REMOVED the RGB tint-toward-DUST_HIGHLIGHT this method
    * used to apply as erosion rose (previously capped at EDGE_TINT_FRACTION).
    * Root cause of Noah's actual playtest report ("a layer forms over the
    * skeleton, the real skin doesn't disappear"): entityCutoutNoCull is
    * ALPHA-TESTED, not alpha-blended (see the discard-threshold paragraph
    * above) - every alpha value from 1.0 down to just above 0.1 renders
    * IDENTICALLY, fully opaque, no partial transparency at all. So for the
    * ~90% of a pixel's erosion journey before it actually crosses the
    * discard threshold, the ONLY visible change was the tint - the pixel
    * stayed fully solid and just changed color toward pale dust. That reads
    * exactly as "a coating forming on top", not "material disappearing",
    * because nothing was actually becoming transparent at any point before
    * the instant discard. There is no way to sell a gradual fade under this
    * render type - only genuine per-pixel appear/disappear. Tint removed
    * entirely: a pixel now stays its real base color (bone/skeleton tone)
    * unchanged until the moment its own erosion crosses 0.9, at which point
    * it's simply gone - real holes in real material, nothing painted on.
    * This drops RECOVERY.md section 7 point 2's "pale colour at the decay
    * edge" intent - that effect needs actual alpha blending to read
    * correctly, which this single-draw-call/no-separate-RenderLayer design
    * (deliberately kept, see class doc) cannot provide. Noah's own report
    * takes priority over that original spec detail: real disappearing over
    * a color gradient that turned out to look like the opposite of decay.
    *
    * Where base alpha is already 0 (the hat region, and the ash-pile UV
    * patch reserved inside it) outAlpha stays 0 regardless of erosion -
    * unchanged by this edit, same as before.
    */
   private static int erodeOver(int overlay, int base) {
      int oa = overlay >> 24 & 0xFF;
      int br = base & 0xFF;
      int bg = base >> 8 & 0xFF;
      int bb = base >> 16 & 0xFF;
      int ba = base >> 24 & 0xFF;
      float erosion = oa / 255.0F;
      float baseAlpha = ba / 255.0F;
      float outAlpha = baseAlpha * (1.0F - erosion);
      return pack(br, bg, bb, Math.round(outAlpha * 255.0F));
   }

   private static int pack(int r, int g, int b, int a) {
      return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
   }

   private static NativeImage loadImage(ResourceLocation location) {
      try {
         Resource resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(location);

         try (InputStream stream = resource.open()) {
            return NativeImage.read(stream);
         }
      } catch (IOException e) {
         LOGGER.warn("[PlayerCorpse] Failed to load texture {}; using a transparent fallback: {}", location, e.toString());
         return new NativeImage(1, 1, true);
      }
   }

   private record CacheKey(ResourceLocation stageLow, ResourceLocation stageHigh, float frac, @Nullable ResourceLocation baseTexture) {
   }

   private record CacheEntry(CacheKey key, ResourceLocation location) {
   }
}
