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
 * is composited fully opaquely onto it via standard "src-over" alpha
 * compositing, rather than returned as its own semi-transparent image - this
 * is how the ash overlay avoids being a second transparent layer stacked on
 * top of the skeleton texture ("Ergebnis vollständig deckend, kein eigener
 * Layer").
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

   private BlendedTextureCache() {
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
                  blended = compositeOver(blended, base.getPixelRGBA(baseX, baseY));
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
    * BUG 3 fix (2026-08-11): previously forced output alpha to a flat 255
    * regardless of the base pixel's own alpha - correct wherever base is
    * genuinely opaque (every region ash_base.png actually copied real
    * skeleton.png pixel data into: head, body, right_arm, right_leg, and
    * left_arm/left_leg via SkeletonModel's own UV-mirror of
    * right_arm/right_leg), but WRONG for the one region
    * SkeletonModel actually samples that ash_base.png deliberately leaves
    * fully transparent: the hat cube's texOffs (32,0)-(64,16), left blank
    * because real skeleton.png has no hat/hair pixel data at all (skeletons
    * don't have hair - confirmed by tools/verify_ash_base_texture.py's own
    * explicit exclusion of "hat" from its non-transparency assertions).
    *
    * Concrete failure, confirmed with real pixel data (tools/trace_blend.py
    * equivalent, run against the actual committed ash_0.png/ash_base.png):
    * at hat-region coordinates like (44,4), ash_base's pixel is (0,0,0,0)
    * and ash_0's overlay pixel there is (215,210,200,0) (no ash yet). The
    * old code computed r=g=b=0 (100% weighted toward base since overlay
    * alpha=0) and then hard-set alpha=255 - i.e. a fully OPAQUE BLACK
    * pixel, for a region that should stay fully transparent until ash
    * itself puts some opacity there. Rendered through entityCutoutNoCull
    * (NO_TRANSPARENCY - alpha-test discard, no GL blending, confirmed
    * against the real decompiled RenderType source), every one of those
    * pixels passes the alpha test and paints fully opaque: the hat cube -
    * visible for the vast majority of the ASH phase's real duration, since
    * DecayCurves.ashCollapseCurve/ASH_HIDE_HEAD_AT keeps effective progress
    * low (and hat.visible=true) for the first 82% of the phase - rendered
    * as a solid black box enclosing the head.
    *
    * Fix: compute a real output alpha as standard "src over dst" (dst may
    * itself be transparent), and only force full opacity when the
    * destination (base) actually contributes real, opaque data. Where base
    * alpha is already 255 (every load-bearing region), outA reduces to
    * exactly 1.0 and this produces byte-identical results to the old code -
    * this only changes behavior for base pixels that are genuinely
    * transparent, i.e. exactly the hat region.
    */
   private static int compositeOver(int overlay, int base) {
      int or = overlay & 0xFF;
      int og = overlay >> 8 & 0xFF;
      int ob = overlay >> 16 & 0xFF;
      int oa = overlay >> 24 & 0xFF;
      int br = base & 0xFF;
      int bg = base >> 8 & 0xFF;
      int bb = base >> 16 & 0xFF;
      int ba = base >> 24 & 0xFF;
      float srcAlpha = oa / 255.0F;
      float dstAlpha = ba / 255.0F;
      float outAlpha = srcAlpha + dstAlpha * (1.0F - srcAlpha);
      int r;
      int g;
      int b;
      if (outAlpha <= 0.0F) {
         r = g = b = 0;
      } else {
         r = Math.round((or * srcAlpha + br * dstAlpha * (1.0F - srcAlpha)) / outAlpha);
         g = Math.round((og * srcAlpha + bg * dstAlpha * (1.0F - srcAlpha)) / outAlpha);
         b = Math.round((ob * srcAlpha + bb * dstAlpha * (1.0F - srcAlpha)) / outAlpha);
      }

      return pack(r, g, b, Math.round(outAlpha * 255.0F));
   }

   private static int pack(int r, int g, int b, int a) {
      return (a & 0xFF) << 24 | (b & 0xFF) << 16 | (g & 0xFF) << 8 | (r & 0xFF);
   }

   private static NativeImage loadImage(ResourceLocation location) {
      try {
         Resource resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(location);

         try (InputStream stream = resource.open()) {
            NativeImage image = NativeImage.read(stream);
            // TEMPORARY DIAGNOSTIC LOGGING (2026-08-11) - NOT permanent. Proves
            // a real image with real pixel data loaded, as opposed to silently
            // falling into the catch block below and returning a blank 1x1
            // fallback (which would look exactly like "the fix didn't take" in
            // game while the source code looks correct).
            LOGGER.info("[PlayerCorpse-DIAG-TEXLOAD] loaded {} successfully: {}x{}", location, image.getWidth(), image.getHeight());
            return image;
         }
      } catch (IOException e) {
         LOGGER.warn("[PlayerCorpse-DIAG-TEXLOAD] FAILED to load {} - falling back to blank 1x1 image: {}", location, e.toString());
         return new NativeImage(1, 1, true);
      }
   }

   private record CacheKey(ResourceLocation stageLow, ResourceLocation stageHigh, float frac, @Nullable ResourceLocation baseTexture) {
   }

   private record CacheEntry(CacheKey key, ResourceLocation location) {
   }
}
