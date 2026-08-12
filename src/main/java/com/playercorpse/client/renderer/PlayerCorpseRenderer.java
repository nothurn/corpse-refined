package com.playercorpse.client.renderer;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.playercorpse.client.PlayerCorpseSkinResolver;
import com.playercorpse.entity.DecayPhase;
import com.playercorpse.entity.PlayerCorpseEntity;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import net.minecraft.client.model.geom.builders.CubeDeformation;
import net.minecraft.client.model.geom.builders.CubeListBuilder;
import net.minecraft.client.model.geom.builders.LayerDefinition;
import net.minecraft.client.model.geom.builders.MeshDefinition;
import net.minecraft.client.model.geom.builders.PartDefinition;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.EntityRendererProvider.Context;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.client.renderer.entity.layers.ItemInHandLayer;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.PlayerSkin;
import net.minecraft.client.resources.PlayerSkin.Model;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.util.Mth;
import net.minecraft.util.FastColor.ARGB32;
import net.minecraft.world.entity.EquipmentSlot;
import org.slf4j.Logger;
import javax.annotation.Nullable;

public class PlayerCorpseRenderer extends LivingEntityRenderer<PlayerCorpseEntity, HumanoidModel<PlayerCorpseEntity>> {
   private static final Logger LOGGER = LogUtils.getLogger();
   private static final ResourceLocation SKELETON_TEXTURE = ResourceLocation.withDefaultNamespace("textures/entity/skeleton/skeleton.png");
   /**
    * BUG 3 fix: the ASH-phase BlendedTextureCache base texture used to be
    * SKELETON_TEXTURE directly, but that's a legacy 64x32 mob texture whose
    * UV layout only matches the modern 64x64 player-skin layout (the layout
    * ash_N.png/shroud_erosion_N.png are authored against) for
    * head/hat/body/right_arm/right_leg - confirmed against the real
    * decompiled HumanoidModel/SkeletonModel/PlayerModel sources.
    * left_arm/left_leg live at y>=32 in the player-skin layout, a row range
    * skeleton.png doesn't have; every read there got clamped (by
    * BlendedTextureCache's crash fix) to skeleton.png's row 31, which is
    * literally the BODY's own bottom-edge pixel data (confirmed with real
    * pixel reads, tools/inspect_uv_mapping.py) - not a crash, but a wrong
    * body part composited onto the left arm/leg silhouette.
    * ash_base.png (tools/generate_ash_base_texture.py) is a real 64x64
    * texture in the correct UV layout: skeleton.png's actual pixel data
    * copied 1:1 for the five shared-texOffs regions, and left_arm/left_leg
    * derived by mirroring the real right_arm/right_leg data (the same
    * left=mirror(right) assumption SkeletonModel's own geometry already
    * makes for those two parts). Matching dimensions also mean
    * BlendedTextureCache's clamp is never actually exercised by this call
    * site anymore - it stays in BlendedTextureCache as valid defensive code
    * for any other future mismatched baseTexture, just not load-bearing here.
    */
   private static final ResourceLocation ASH_BASE_TEXTURE = ResourceLocation.fromNamespaceAndPath("playercorpse", "textures/entity/ash_base.png");
   private static final int SHROUD_STAGE_COUNT = 9;
   private static final ResourceLocation[] SHROUD_STAGE_TEXTURES = new ResourceLocation[9];
   private static final int ASH_STAGE_COUNT = 9;
   private static final ResourceLocation[] ASH_STAGE_TEXTURES = new ResourceLocation[9];
   private final Set<Integer> loggedHeldItemIds = new HashSet<>();
   /**
    * TEMPORARY DIAGNOSTIC LOGGING (2026-08-11) - NOT permanent, remove once
    * the ash-texture bug is actually root-caused from Noah's next in-game
    * log. Tracks the last decay phase logged per corpse so a phase change
    * (and in particular, whether the client ever actually observes ASH at
    * all) shows up in logs/latest.log exactly once per transition instead of
    * once per frame.
    */
   private static final Map<Integer, DecayPhase> DIAG_LAST_LOGGED_PHASE = new HashMap<>();
   private final PlayerModel<PlayerCorpseEntity> wideModel = (PlayerModel<PlayerCorpseEntity>)this.model;
   private final PlayerModel<PlayerCorpseEntity> slimModel;
   private final HumanoidModel<PlayerCorpseEntity> skeletonModel;
   /**
    * 2026-08-11 phase-boundary POP fix. `skeletonModel` above is baked via
    * `context.bakeLayer(ModelLayers.SKELETON)`, which resolves to vanilla's
    * own `SkeletonModel.createBodyLayer()` - `LayerDefinition.create(mesh,
    * 64, 32)`, confirmed from the real decompiled source. `ModelPart.Cube`/
    * `Polygon` bake each vertex's normalized UV as texCoordV / texHeight
    * ONCE, at this bake call (confirmed from `ModelPart.Polygon`'s
    * constructor: `v1/textureHeight`, `v2/textureHeight`) - never
    * recomputed at render time against whatever texture is actually bound.
    * `getTextureLocation()` binds the real 64x32 `SKELETON_TEXTURE` for
    * every phase up through HOLD_FULL_SKELETON (matches `skeletonModel`'s
    * baked assumption exactly - looks correct), but the INSTANT phase
    * becomes ASH it switches to `ashTextureFor()`'s output, which
    * `BlendedTextureCache.blend()` always sizes to match `ash_N.png` (64x64
    * per `generate_ash_texture.py`) - double the baked height. Every
    * already-baked v = texCoordV/32 then samples row (v*64) of the actual
    * bound 64-tall texture instead of the intended row texCoordV out of 32 -
    * e.g. right_arm's side faces (baked v 18-30/32) land on rows 36-60 of a
    * 64-tall image, which is either fully transparent or a completely
    * unrelated region in `ash_base.png`. Quantified before fixing (scratch
    * script, not preserved): every sampled part landed 60-100% on fully
    * transparent pixels once bound against the real 64x64 ash texture - an
    * instant, hard visual corruption at the exact tick the phase flips,
    * independent of how subtle the ash overlay itself is at frac=0. This is
    * the root cause of "clean skeleton, then instant pop to a
    * weird/malformed shape, then it keeps decaying from there" - a genuinely
    * different bug from the color/seam tuning done in the previous two
    * sessions, not a residual symptom of either.
    *
    * Fix: bake a SEPARATE copy of the exact same skeleton geometry (same
    * texOffs, same thin 2x12x2 limb overrides - duplicated from
    * `SkeletonModel.createDefaultSkeletonMesh()` here because that method is
    * `protected static` on a class this one doesn't extend) at texHeight=64
    * instead of 32, so its baked UVs actually match `ash_base.png`/
    * `ash_N.png`'s real dimensions. Used ONLY while `DecayPhase.ASH` is
    * active - HOLD_FULL_SKELETON and earlier keep using `skeletonModel`
    * against the real `SKELETON_TEXTURE`, unaffected by this change.
    */
   private final HumanoidModel<PlayerCorpseEntity> ashSkeletonModel;
   private static final float HALF_BODY_LENGTH = 0.9F;

   public PlayerCorpseRenderer(Context context) {
      super(context, new PlayerCorpseRenderer.StaticPlayerModel(context.bakeLayer(ModelLayers.PLAYER), false), 0.0F);
      this.slimModel = new PlayerCorpseRenderer.StaticPlayerModel(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
      this.skeletonModel = new PlayerCorpseRenderer.StaticHumanoidModel(context.bakeLayer(ModelLayers.SKELETON), false);
      this.ashSkeletonModel = new PlayerCorpseRenderer.StaticHumanoidModel(buildAshSkeletonRoot(), true);
      this.addLayer(new PlayerCorpseRenderer.CorpseShroudLayer(this, this.wideModel, this.slimModel));
      ModelManager modelManager = context.getModelManager();
      this.addLayer(
         new PlayerCorpseRenderer.PhaseGatedArmorLayer(
            this,
            new HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
            new HumanoidModel(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)),
            modelManager,
            false
         )
      );
      this.addLayer(
         new PlayerCorpseRenderer.PhaseGatedArmorLayer(
            this,
            new HumanoidModel(context.bakeLayer(ModelLayers.SKELETON_INNER_ARMOR)),
            new HumanoidModel(context.bakeLayer(ModelLayers.SKELETON_OUTER_ARMOR)),
            modelManager,
            true
         )
      );
      this.addLayer(new ItemInHandLayer(this, context.getItemInHandRenderer()));
      this.addLayer(new PlayerCorpseRenderer.CorpseDeathCauseTintLayer(this));
   }

   /**
    * Duplicates SkeletonModel.createDefaultSkeletonMesh()'s thin-limb
    * overrides (same texOffs, same 2x12x2 dimensions, same mirror flags -
    * confirmed against the real decompiled source) on top of
    * HumanoidModel.createMesh()'s shared head/hat/body definition, then
    * bakes at texHeight=64 instead of vanilla's own 64x32 - see the
    * ashSkeletonModel field comment for why. Geometry is byte-for-byte
    * identical to vanilla's skeleton shape; only the baked UV denominator
    * differs.
    *
    * 2026-08-12, new: also adds "ash_pile", a small flattened box
    * representing what's left once the skeleton has essentially fully
    * eroded/vanished (see DecayCurves.pileAppearT / applyAshDeformation
    * below) - the previously-missing "small ash pile on the ground" state
    * before the grave marker spawns. Positioned at PartPose y=24: real
    * HumanoidModel.createMesh() (confirmed from the decompiled source, not
    * guessed) pivots right_leg/left_leg at y=12 with a 12-unit-tall box
    * extending DOWN from there (y increases downward in this model space,
    * confirmed from head's own box - it sits ABOVE the y=0 shoulder line at
    * y=-8..0) - so 12+12=24 is exactly where the real skeleton's feet
    * already touch the ground. The pile's own box (-3,-3,-3, 6,3,6) sits
    * with its bottom face exactly at that same y=24 ground line, not
    * floating or sunk in.
    *
    * Textured via texOffs(34,3) - a 24x9 UV footprint (2*(6+6) wide,
    * 3+6 tall, matching CubeListBuilder's standard box-UV formula) that
    * sits entirely inside the hat cube's own (32,0)-(64,16) UV rectangle
    * but is never simultaneously in use with it: the hat is always long
    * hidden (ASH_HIDE_HEAD_AT=0.60) before the pile starts appearing
    * (ASH_PILE_APPEAR_START=0.80). generate_ash_base_texture.py paints a
    * real opaque swatch there and generate_ash_texture.py deliberately
    * keeps that same patch at alpha=0 in every ash_N.png stage, so
    * erodeOver's erosion formula never touches it - the pile texture is
    * exempt from the same erosion that removes everything else, since it's
    * meant to be what's left AFTER that erosion finishes, not subject to it
    * itself.
    */
   private static ModelPart buildAshSkeletonRoot() {
      MeshDefinition mesh = HumanoidModel.createMesh(CubeDeformation.NONE, 0.0F);
      PartDefinition root = mesh.getRoot();
      root.addOrReplaceChild(
         "right_arm", CubeListBuilder.create().texOffs(40, 16).addBox(-1.0F, -2.0F, -1.0F, 2.0F, 12.0F, 2.0F), PartPose.offset(-5.0F, 2.0F, 0.0F)
      );
      root.addOrReplaceChild(
         "left_arm",
         CubeListBuilder.create().texOffs(40, 16).mirror().addBox(-1.0F, -2.0F, -1.0F, 2.0F, 12.0F, 2.0F),
         PartPose.offset(5.0F, 2.0F, 0.0F)
      );
      root.addOrReplaceChild(
         "right_leg", CubeListBuilder.create().texOffs(0, 16).addBox(-1.0F, 0.0F, -1.0F, 2.0F, 12.0F, 2.0F), PartPose.offset(-2.0F, 12.0F, 0.0F)
      );
      root.addOrReplaceChild(
         "left_leg", CubeListBuilder.create().texOffs(0, 16).mirror().addBox(-1.0F, 0.0F, -1.0F, 2.0F, 12.0F, 2.0F), PartPose.offset(2.0F, 12.0F, 0.0F)
      );
      root.addOrReplaceChild(
         "ash_pile", CubeListBuilder.create().texOffs(34, 3).addBox(-3.0F, -3.0F, -3.0F, 6.0F, 3.0F, 6.0F), PartPose.offset(0.0F, 24.0F, 0.0F)
      );
      return LayerDefinition.create(mesh, 64, 64).bakeRoot();
   }

   private static boolean isSkeletonPhase(PlayerCorpseEntity entity) {
      return entity.getDecayPhase().ordinal() >= DecayPhase.HOLD_FULL_SHROUD.ordinal();
   }

   private static boolean isShroudActive(PlayerCorpseEntity entity) {
      return entity.getDecayPhase().ordinal() < DecayPhase.HOLD_FULL_SKELETON.ordinal();
   }

   /**
    * Whether CorpseShroudLayer is actually drawing something opaque over the
    * model this tick - the exact condition CorpseShroudLayer.render() itself
    * gates on. Shared with StaticPlayerModel's own hat-hiding so the two
    * can't independently drift out of sync (that drift, before this fix, was
    * the whole bug: the base draw never checked this at all).
    */
   private static boolean isShroudCovering(PlayerCorpseEntity entity) {
      DecayPhase phase = entity.getDecayPhase();
      return phase != DecayPhase.HOLD_FRESH && phase.ordinal() < DecayPhase.HOLD_FULL_SKELETON.ordinal();
   }

   /**
    * Scales a part down toward (near) zero as t approaches 1, THEN flips
    * visible to false only once fully hidden - never the other order.
    * ModelPart.visible has no interpolation of its own (rendering lesson 2);
    * toggling it at full scale is a hard, visible pop. Shared by both static
    * models: StaticHumanoidModel's ash vanish and StaticPlayerModel's
    * shroud-coverage hat hiding are the same problem (hide a part in sync
    * with a continuous 0-1 driver, no boolean-only jump) on two different
    * models.
    */
   private static void setVanishing(ModelPart part, float t, boolean fullyHidden) {
      float scale = Math.max(1.0F - t, 0.001F);
      part.xScale = scale;
      part.yScale = scale;
      part.zScale = scale;
      part.visible = !fullyHidden;
   }

   /**
    * Inverse of setVanishing: scales a part UP from (near) zero as t goes
    * 0->1, becoming visible as soon as it has any real presence. Used only
    * by the ash pile (see applyAshDeformation) - unlike every other part in
    * this file, the pile starts hidden and grows in rather than starting
    * visible and shrinking away.
    */
   private static void setAppearing(ModelPart part, float t) {
      float scale = Math.max(t, 0.001F);
      part.xScale = scale;
      part.yScale = scale;
      part.zScale = scale;
      part.visible = t > 0.0F;
   }

   private static PlayerModel<PlayerCorpseEntity> choosePlayerModel(
      PlayerCorpseEntity entity, PlayerModel<PlayerCorpseEntity> wideModel, PlayerModel<PlayerCorpseEntity> slimModel
   ) {
      return resolveSkin(entity).model() == Model.SLIM ? slimModel : wideModel;
   }

   public void render(PlayerCorpseEntity entity, float entityYaw, float partialTick, PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
      if (entity.getDecayPhase() == DecayPhase.ASH) {
         this.model = this.ashSkeletonModel;
      } else if (isSkeletonPhase(entity)) {
         this.model = this.skeletonModel;
      } else {
         this.model = choosePlayerModel(entity, this.wideModel, this.slimModel);
      }

      if (this.loggedHeldItemIds.add(entity.getId())) {
         LOGGER.info("[PlayerCorpse-DEBUG] corpse {} client-side getItemBySlot(MAINHAND)={}", entity.getId(), entity.getItemBySlot(EquipmentSlot.MAINHAND));
      }

      DecayPhase phase = entity.getDecayPhase();
      if (DIAG_LAST_LOGGED_PHASE.get(entity.getId()) != phase) {
         DIAG_LAST_LOGGED_PHASE.put(entity.getId(), phase);
         LOGGER.info(
            "[PlayerCorpse-DIAG-ASH] corpse {} client-side decay phase changed to {} (progress={})", entity.getId(), phase, entity.getPhaseProgress()
         );
      }

      super.render(entity, entityYaw, partialTick, poseStack, bufferSource, packedLight);
   }

   protected void setupRotations(PlayerCorpseEntity entity, PoseStack poseStack, float ageInTicks, float rotationYaw, float partialTick, float scale) {
      super.setupRotations(entity, poseStack, ageInTicks, rotationYaw, partialTick, scale);
      poseStack.translate(0.0, 0.15, 0.0);
      poseStack.mulPose(Axis.XP.rotationDegrees(90.0F));
      poseStack.translate(0.0, -0.9F, 0.0);
   }

   protected void scale(PlayerCorpseEntity entity, PoseStack poseStack, float partialTickTime) {
      poseStack.scale(0.9375F, 0.9375F, 0.9375F);
   }

   protected boolean shouldShowName(PlayerCorpseEntity entity) {
      return false;
   }

   public ResourceLocation getTextureLocation(PlayerCorpseEntity entity) {
      return resolveBodyTexture(entity);
   }

   /**
    * Shared by the main body draw (getTextureLocation, above) and
    * CorpseDeathCauseTintLayer's own separate translucent draw - both must
    * always agree on which texture goes with the current DecayPhase.ASH ->
    * ashSkeletonModel model swap (see that field's comment); before this
    * extraction the tint layer had its own slightly different inline
    * ternary, which would have kept binding the real (64x32) SKELETON_TEXTURE
    * against the new 64x64-baked ashSkeletonModel for fire/drowning-death
    * corpses in ASH - the exact same UV mismatch this whole fix removes,
    * reintroduced in one specific layer. Same discipline as isShroudCovering
    * above, which exists for the identical reason.
    */
   private static ResourceLocation resolveBodyTexture(PlayerCorpseEntity entity) {
      if (entity.getDecayPhase() == DecayPhase.ASH) {
         return ashTextureFor(entity);
      }

      return isSkeletonPhase(entity) ? SKELETON_TEXTURE : resolveSkin(entity).texture();
   }

   /**
    * Ash is eroded directly into the skeleton texture and returned as THE
    * base texture for this tick, rather than added as a separate
    * RenderLayer - this is what "no separate transparent layer" (RECOVERY.md
    * section 7) means in practice: there is no additional draw call at all,
    * only the single base draw's texture changes. Stage selection is a
    * direct linear map of raw phase progress (2026-08-12 redesign - no
    * ashCollapseCurve remap anymore), the exact same mapping
    * CorpseShroudLayer already uses for EROSION below, so material loss
    * runs continuously across the whole phase instead of being concentrated
    * late.
    */
   private static final Set<Integer> DIAG_LOGGED_ASH_TEXTURE_ENTITY_IDS = new HashSet<>();

   private static ResourceLocation ashTextureFor(PlayerCorpseEntity entity) {
      float effective = entity.getPhaseProgress();
      float scaled = effective * 8.0F;
      int stageLow = Mth.clamp((int)scaled, 0, 7);
      int stageHigh = Mth.clamp(stageLow + 1, 0, 8);
      float frac = Mth.clamp(scaled - stageLow, 0.0F, 1.0F);
      ResourceLocation blended = BlendedTextureCache.getOrCreate(
         entity.getId(), "ash", ASH_STAGE_TEXTURES[stageLow], ASH_STAGE_TEXTURES[stageHigh], frac, ASH_BASE_TEXTURE
      );
      // TEMPORARY DIAGNOSTIC LOGGING (2026-08-11) - NOT permanent. First call
      // per entity proves ashTextureFor() actually executes at all, and logs
      // the exact base-texture ResourceLocation passed to BlendedTextureCache
      // - the only way to confirm from a real log (not from re-reading the
      // source) that this is really ash_base.png and not something else.
      if (DIAG_LOGGED_ASH_TEXTURE_ENTITY_IDS.add(entity.getId())) {
         LOGGER.info(
            "[PlayerCorpse-DIAG-ASH] ashTextureFor() called for corpse {} - phase={} baseTexture={} stageLow={} stageHigh={} frac={} -> blended result={}",
            entity.getId(), entity.getDecayPhase(), ASH_BASE_TEXTURE, ASH_STAGE_TEXTURES[stageLow], ASH_STAGE_TEXTURES[stageHigh], frac, blended
         );
      }

      return blended;
   }

   private static PlayerSkin resolveSkin(PlayerCorpseEntity entity) {
      Optional<UUID> ownerUuid = entity.getOwnerUuid();
      String ownerName = entity.getOwnerName();
      return ownerUuid.isEmpty()
         ? DefaultPlayerSkin.get(new GameProfile(UUID.randomUUID(), ownerName))
         : PlayerCorpseSkinResolver.resolveSkin(ownerUuid.get(), ownerName);
   }

   static {
      for (int i = 0; i < 9; i++) {
         SHROUD_STAGE_TEXTURES[i] = ResourceLocation.fromNamespaceAndPath("playercorpse", "textures/entity/shroud_erosion_" + i + ".png");
         ASH_STAGE_TEXTURES[i] = ResourceLocation.fromNamespaceAndPath("playercorpse", "textures/entity/ash_" + i + ".png");
      }
   }

   private static final class CorpseDeathCauseTintLayer extends RenderLayer<PlayerCorpseEntity, HumanoidModel<PlayerCorpseEntity>> {
      private static final int FIRE_TINT = ARGB32.color(110, 90, 70, 60);
      private static final int DROWNING_TINT = ARGB32.color(110, 190, 205, 220);

      CorpseDeathCauseTintLayer(RenderLayerParent<PlayerCorpseEntity, HumanoidModel<PlayerCorpseEntity>> parent) {
         super(parent);
      }

      public void render(
         PoseStack poseStack,
         MultiBufferSource bufferSource,
         int packedLight,
         PlayerCorpseEntity entity,
         float limbSwing,
         float limbSwingAmount,
         float partialTick,
         float ageInTicks,
         float netHeadYaw,
         float headPitch
      ) {
         if (!PlayerCorpseRenderer.isShroudActive(entity)) {
            int tint = switch (entity.getDeathCauseCategory()) {
               case FIRE -> FIRE_TINT;
               case DROWNING -> DROWNING_TINT;
               case FALL, OTHER -> 0;
            };
            if (tint != 0) {
               ResourceLocation texture = PlayerCorpseRenderer.resolveBodyTexture(entity);
               VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityTranslucent(texture));
               ((HumanoidModel)this.getParentModel()).renderToBuffer(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, tint);
            }
         }
      }
   }

   private static final class CorpseShroudLayer extends RenderLayer<PlayerCorpseEntity, HumanoidModel<PlayerCorpseEntity>> {
      private final PlayerModel<PlayerCorpseEntity> wideModel;
      private final PlayerModel<PlayerCorpseEntity> slimModel;
      private final Set<Integer> loggedSpawnIds = new HashSet<>();
      private final Set<Integer> loggedTextureStatsIds = new HashSet<>();
      private final Set<Integer> loggedBuildEndIds = new HashSet<>();
      private final Set<Integer> loggedModelSwapIds = new HashSet<>();
      private final Map<Integer, Integer> lastLoggedStageByEntityId = new HashMap<>();

      CorpseShroudLayer(
         RenderLayerParent<PlayerCorpseEntity, HumanoidModel<PlayerCorpseEntity>> parent,
         PlayerModel<PlayerCorpseEntity> wideModel,
         PlayerModel<PlayerCorpseEntity> slimModel
      ) {
         super(parent);
         this.wideModel = wideModel;
         this.slimModel = slimModel;
      }

      public void render(
         PoseStack poseStack,
         MultiBufferSource bufferSource,
         int packedLight,
         PlayerCorpseEntity entity,
         float limbSwing,
         float limbSwingAmount,
         float partialTick,
         float ageInTicks,
         float netHeadYaw,
         float headPitch
      ) {
         DecayPhase phase = entity.getDecayPhase();
         if (PlayerCorpseRenderer.isShroudCovering(entity)) {
            int entityId = entity.getId();
            int tickCount = entity.tickCount;
            if (this.loggedSpawnIds.add(entityId)) {
               PlayerCorpseRenderer.LOGGER
                  .info("[PlayerCorpse-DEBUG] corpse {} shroud first visible at client tickCount={} ({}s)", entityId, tickCount, tickCount / 20.0F);
            }

            PlayerModel<PlayerCorpseEntity> playerModel = PlayerCorpseRenderer.choosePlayerModel(entity, this.wideModel, this.slimModel);
            playerModel.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            if (phase.ordinal() >= DecayPhase.HOLD_FULL_SHROUD.ordinal() && this.loggedModelSwapIds.add(entityId)) {
               PlayerCorpseRenderer.LOGGER
                  .info("[PlayerCorpse-DEBUG] corpse {} base model swapped to skeleton at client tickCount={} ({}s)", entityId, tickCount, tickCount / 20.0F);
            }

            boolean hatWasVisible = playerModel.hat.visible;
            playerModel.hat.visible = false;

            try {
               if (phase == DecayPhase.EROSION) {
                  float erosion = entity.getPhaseProgress();
                  float scaled = erosion * 8.0F;
                  int stageLow = Mth.clamp((int)scaled, 0, 7);
                  int stageHigh = Mth.clamp(stageLow + 1, 0, 8);
                  float frac = Mth.clamp(scaled - stageLow, 0.0F, 1.0F);
                  Integer lastStage = this.lastLoggedStageByEntityId.get(entityId);
                  int loggedStage = Math.round(scaled);
                  if (lastStage == null || lastStage != loggedStage) {
                     PlayerCorpseRenderer.LOGGER
                        .info(
                           "[PlayerCorpse-DEBUG] corpse {} erosion blend {}->{} frac={} at client tickCount={} ({}s), erosion={}",
                           new Object[]{entityId, stageLow, stageHigh, frac, tickCount, tickCount / 20.0F, erosion}
                        );
                     this.lastLoggedStageByEntityId.put(entityId, loggedStage);
                  }

                  // Single premixed texture, single draw call - see BlendedTextureCache for why this
                  // replaces the old two-overlapping-transparent-draws crossfade approach.
                  ResourceLocation blended = BlendedTextureCache.getOrCreate(
                     entityId, "shroud", PlayerCorpseRenderer.SHROUD_STAGE_TEXTURES[stageLow], PlayerCorpseRenderer.SHROUD_STAGE_TEXTURES[stageHigh], frac, null
                  );
                  VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityTranslucent(blended));
                  int color = ARGB32.color(255, 255, 255, 255);
                  playerModel.renderToBuffer(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, color);
                  return;
               }

               float alpha = phase == DecayPhase.BUILD ? DecayCurves.buildAlphaCurve(entity.getPhaseProgress()) : 1.0F;
               if (phase.ordinal() > DecayPhase.BUILD.ordinal() && this.loggedBuildEndIds.add(entityId)) {
                  PlayerCorpseRenderer.LOGGER
                     .info(
                        "[PlayerCorpse-DEBUG] corpse {} shroud fully built (build->hold) at client tickCount={} ({}s)", entityId, tickCount, tickCount / 20.0F
                     );
               }

               ResourceLocation texture = PlayerCorpseRenderer.SHROUD_STAGE_TEXTURES[0];
               if (this.loggedTextureStatsIds.add(entityId)) {
                  logTextureStats(entityId, texture, 0);
               }

               VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityTranslucent(texture));
               int color = ARGB32.color(Math.round(alpha * 255.0F), 255, 255, 255);
               playerModel.renderToBuffer(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, color);
            } finally {
               playerModel.hat.visible = hatWasVisible;
            }
         }
      }

      private static void logTextureStats(int entityId, ResourceLocation texture, int stage) {
         Resource resource;
         try {
            resource = Minecraft.getInstance().getResourceManager().getResourceOrThrow(texture);
         } catch (IOException e) {
            PlayerCorpseRenderer.LOGGER
               .warn("[PlayerCorpse-DEBUG] corpse {} stage {} could not locate texture {}: {}", new Object[]{entityId, stage, texture, e.toString()});
            return;
         }

         try (InputStream stream = resource.open()) {
            NativeImage image = NativeImage.read(stream);

            try {
               long sum = 0L;
               long sumSq = 0L;
               int count = 0;
               int min = 255;
               int max = 0;

               for (int y = 0; y < image.getHeight(); y++) {
                  for (int x = 0; x < image.getWidth(); x++) {
                     int argb = image.getPixelRGBA(x, y);
                     int a = argb >> 24 & 0xFF;
                     if (a != 0) {
                        int r = argb & 0xFF;
                        int g = argb >> 8 & 0xFF;
                        int b = argb >> 16 & 0xFF;
                        int lum = Math.round(0.299F * r + 0.587F * g + 0.114F * b);
                        sum += lum;
                        sumSq += (long)lum * lum;
                        count++;
                        min = Math.min(min, lum);
                        max = Math.max(max, lum);
                     }
                  }
               }

               if (count != 0) {
                  double mean = (double)sum / count;
                  double variance = (double)sumSq / count - mean * mean;
                  double stddev = Math.sqrt(Math.max(0.0, variance));
                  PlayerCorpseRenderer.LOGGER
                     .info(
                        "[PlayerCorpse-DEBUG] corpse {} stage {} texture={} visible_px={}/{} lum_min={} lum_max={} lum_mean={} lum_stddev={}",
                        new Object[]{
                           entityId,
                           stage,
                           texture,
                           count,
                           image.getWidth() * image.getHeight(),
                           min,
                           max,
                           String.format("%.1f", mean),
                           String.format("%.1f", stddev)
                        }
                     );
                  return;
               }

               PlayerCorpseRenderer.LOGGER
                  .info("[PlayerCorpse-DEBUG] corpse {} stage {} texture={} has NO visible pixels", new Object[]{entityId, stage, texture});
            } finally {
               image.close();
            }
         } catch (IOException e) {
            PlayerCorpseRenderer.LOGGER
               .warn("[PlayerCorpse-DEBUG] corpse {} stage {} failed to read texture {} for stats: {}", new Object[]{entityId, stage, texture, e.toString()});
         }
      }
   }

   private static final class PhaseGatedArmorLayer
      extends HumanoidArmorLayer<PlayerCorpseEntity, HumanoidModel<PlayerCorpseEntity>, HumanoidModel<PlayerCorpseEntity>> {
      private final boolean skeletonPhase;

      PhaseGatedArmorLayer(
         RenderLayerParent<PlayerCorpseEntity, HumanoidModel<PlayerCorpseEntity>> parent,
         HumanoidModel<PlayerCorpseEntity> innerModel,
         HumanoidModel<PlayerCorpseEntity> outerModel,
         ModelManager modelManager,
         boolean skeletonPhase
      ) {
         super(parent, innerModel, outerModel, modelManager);
         this.skeletonPhase = skeletonPhase;
      }

      public void render(
         PoseStack poseStack,
         MultiBufferSource bufferSource,
         int packedLight,
         PlayerCorpseEntity entity,
         float limbSwing,
         float limbSwingAmount,
         float partialTick,
         float ageInTicks,
         float netHeadYaw,
         float headPitch
      ) {
         if (PlayerCorpseRenderer.isSkeletonPhase(entity) == this.skeletonPhase) {
            super.render(poseStack, bufferSource, packedLight, entity, limbSwing, limbSwingAmount, partialTick, ageInTicks, netHeadYaw, headPitch);
         }
      }
   }

   private static class StaticHumanoidModel extends HumanoidModel<PlayerCorpseEntity> {
      /**
       * Only non-null for ashSkeletonModel (hasAshPile=true) - skeletonModel
       * is baked from vanilla's own ModelLayers.SKELETON, which has no
       * "ash_pile" child at all, and ModelPart.getChild throws (does not
       * return null) for a missing name. resetAshState/applyAshDeformation
       * both run on BOTH StaticHumanoidModel instances (setupAnim is called
       * on whichever model is currently assigned as this.model - see
       * PlayerCorpseRenderer.render()'s swap), so every use of this field
       * must stay null-guarded.
       */
      @Nullable
      private final ModelPart ashPile;

      StaticHumanoidModel(ModelPart root, boolean hasAshPile) {
         super(root);
         this.ashPile = hasAshPile ? root.getChild("ash_pile") : null;
      }

      public void setupAnim(PlayerCorpseEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
         super.setupAnim(entity, 0.0F, 0.0F, 0.0F, netHeadYaw, headPitch);
         // Every field NOT touched by the super call above (visibility, scale, this model's own
         // ash-specific y offset) must still be explicitly reset here every call, unconditionally,
         // BEFORE checking this entity's own phase. this.head/body/etc. are the SAME shared
         // ModelPart objects reused across every corpse rendered through this one renderer instance
         // (one renderer instance per registered entity TYPE, not per entity - RECOVERY.md section 7's
         // priority bug: rendering state held on the model object bleeds between simultaneous
         // corpses). Skipping this reset would let one corpse's ash-hidden/scaled/sunk state
         // visibly leak into a different corpse's render call.
         this.resetAshState();
         if (entity.getDecayPhase() == DecayPhase.ASH) {
            // 2026-08-12: raw phase progress directly, no ashCollapseCurve remap - see
            // DecayCurves' class doc for why (continuous material loss across the whole
            // phase, not a late-concentrated collapse).
            this.applyAshDeformation(entity.getPhaseProgress());
         }
      }

      private void resetAshState() {
         this.head.visible = true;
         this.hat.visible = true;
         this.body.visible = true;
         this.leftArm.visible = true;
         this.rightArm.visible = true;
         this.leftLeg.visible = true;
         this.rightLeg.visible = true;
         this.head.xScale = this.head.yScale = this.head.zScale = 1.0F;
         this.body.xScale = this.body.yScale = this.body.zScale = 1.0F;
         this.leftArm.xScale = this.leftArm.yScale = this.leftArm.zScale = 1.0F;
         this.rightArm.xScale = this.rightArm.yScale = this.rightArm.zScale = 1.0F;
         this.leftLeg.xScale = this.leftLeg.yScale = this.leftLeg.zScale = 1.0F;
         this.rightLeg.xScale = this.rightLeg.yScale = this.rightLeg.zScale = 1.0F;
         this.body.y = 0.0F;
         if (this.ashPile != null) {
            this.ashPile.visible = false;
            this.ashPile.xScale = this.ashPile.yScale = this.ashPile.zScale = 0.001F;
         }
      }

      /**
       * All magnitudes kept within RECOVERY.md section 7's stated bounds
       * (rotations <= 0.30 rad, translation <= 0.6 units) so this reads as
       * structural giving-way, not wobbling. UNVERIFIED visually - this
       * environment cannot render Minecraft; the sign/direction of every
       * offset below (which way is "down", which way is "outward") is my
       * best-effort reasoning about Minecraft's model-space axes, not
       * confirmed against an actual rendered corpse.
       */
      private void applyAshDeformation(float effective) {
         float limbsT = DecayCurves.ashPoseT(effective, DecayCurves.ASH_HIDE_LIMBS_AT);
         float headT = DecayCurves.ashPoseT(effective, DecayCurves.ASH_HIDE_HEAD_AT);
         float bodyT = DecayCurves.ashPoseT(effective, DecayCurves.ASH_HIDE_BODY_AT);

         // Limbs: droop down and splay outward, mirrored left/right. Both rotation axes are fine
         // here - unlike the head, limbs are texture-less bones with no asymmetric jaw-pivoted
         // face to reveal a corkscrew artifact (rendering lesson 3 below only applies to the head).
         float droop = 0.30F * limbsT;
         float splay = 0.22F * limbsT;
         this.rightArm.xRot += droop;
         this.rightArm.zRot += splay;
         this.leftArm.xRot += droop;
         this.leftArm.zRot -= splay;
         this.rightLeg.xRot += droop;
         this.rightLeg.zRot += splay * 0.5F;
         this.leftLeg.xRot += droop;
         this.leftLeg.zRot -= splay * 0.5F;
         boolean limbsHidden = effective >= DecayCurves.ASH_HIDE_LIMBS_AT;
         PlayerCorpseRenderer.setVanishing(this.rightArm, limbsT, limbsHidden);
         PlayerCorpseRenderer.setVanishing(this.leftArm, limbsT, limbsHidden);
         PlayerCorpseRenderer.setVanishing(this.rightLeg, limbsT, limbsHidden);
         PlayerCorpseRenderer.setVanishing(this.leftLeg, limbsT, limbsHidden);

         // Head: skull tilts - ONE axis only (xRot). ModelPart composes rotation Z-then-Y-then-X
         // around its own pivot; xRot+zRot together on the head produces a corkscrew motion instead
         // of a hinge-like sag, because its jaw-pivoted, asymmetric face texture makes that visible
         // in a way it never is on texture-less limb bones (RECOVERY.md rendering lesson 3).
         this.head.xRot += 0.28F * headT;
         boolean headHidden = effective >= DecayCurves.ASH_HIDE_HEAD_AT;
         PlayerCorpseRenderer.setVanishing(this.head, headT, headHidden);
         this.hat.visible = this.head.visible;

         // Body: sinks and caves in slightly, deforms and vanishes last of the three groups.
         this.body.y += 0.5F * bodyT;
         this.body.xRot += 0.15F * bodyT;
         PlayerCorpseRenderer.setVanishing(this.body, bodyT, effective >= DecayCurves.ASH_HIDE_BODY_AT);

         // 2026-08-12, new: the ash pile scales in as the body finishes vanishing, so there's an
         // actual "small ash pile on the ground" end state instead of the model just fully
         // disappearing with no visual bridge to the grave marker that spawns right after.
         if (this.ashPile != null) {
            PlayerCorpseRenderer.setAppearing(this.ashPile, DecayCurves.pileAppearT(effective));
         }
      }
   }

   private static class StaticPlayerModel extends PlayerModel<PlayerCorpseEntity> {
      StaticPlayerModel(ModelPart root, boolean slim) {
         super(root, slim);
      }

      public void setupAnim(PlayerCorpseEntity entity, float limbSwing, float limbSwingAmount, float ageInTicks, float netHeadYaw, float headPitch) {
         super.setupAnim(entity, 0.0F, 0.0F, 0.0F, netHeadYaw, headPitch);
         this.applyHatCoverage(entity);
      }

      /**
       * Hides hair (the "hat" second-layer cube) as the shroud grows over
       * it. Bug: the base LivingEntityRenderer draw (this model, via
       * getTextureLocation returning the real player skin) never touched
       * hat.visible at all - only CorpseShroudLayer's OWN separate overlay
       * draw hid it, and only within that one draw call, restoring it
       * straight after. Net effect: hair rendered fully opaque in the base
       * pass for the entire BUILD phase, completely unaffected by the
       * shroud fading in on top of it, because the shroud's own draw never
       * paints over the hat cube's footprint either (it hides that cube
       * rather than drawing it).
       *
       * Fix is scale-ramped in lockstep with the SAME buildAlphaCurve value
       * that drives the shroud's own vertex alpha, not a hard cutoff at the
       * BUILD phase boundary - a hard toggle would pop the hair away in one
       * frame while the shroud itself is still at ~0 alpha (indistinguishable
       * from invisible), which is its own separate glitch. Reusing
       * PlayerCorpseRenderer.setVanishing (same ramp-then-flip discipline as
       * the ash system) keeps both hidings driven by one curve, so they can
       * never visibly disagree.
       *
       * This model is also reused as CorpseShroudLayer's own playerModel
       * reference for HOLD_FULL_SHROUD/EROSION, where phase != BUILD makes
       * coverage 0 here - harmless, since CorpseShroudLayer forces
       * hat.visible = false itself immediately after calling setupAnim for
       * every phase it draws in, overriding whatever this method computed.
       */
      private void applyHatCoverage(PlayerCorpseEntity entity) {
         float coverage = entity.getDecayPhase() == DecayPhase.BUILD ? DecayCurves.buildAlphaCurve(entity.getPhaseProgress()) : 0.0F;
         PlayerCorpseRenderer.setVanishing(this.hat, coverage, coverage >= 1.0F);
      }
   }
}
