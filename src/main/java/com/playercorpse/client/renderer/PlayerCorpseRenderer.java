package com.playercorpse.client.renderer;

import com.mojang.authlib.GameProfile;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.playercorpse.client.PlayerCorpseSkinResolver;
import com.playercorpse.entity.DecayPhase;
import com.playercorpse.entity.PlayerCorpseEntity;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;
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
    * eroded (see DecayCurves.pileAppearT / applyAshAppearance
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
    * but is never simultaneously rendered by that cube: skeleton models
    * keep the player-style hat part hidden for their entire lifetime.
    * generate_ash_base_texture.py paints a
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
    * toggling it at full scale is a hard, visible pop.
    *
    * No longer used for StaticHumanoidModel's ASH appearance: a uniform scale-down is
    * exactly the "shrinking" Noah's playtest flagged as wrong for material
    * that's supposed to crumble to ash, not shrink. Still correct and still
    * used for StaticPlayerModel's shroud-coverage hat hiding (BUILD phase) -
    * fabric visibly covering hair is a genuinely different visual claim than
    * bone material eroding away, and a real scale-then-hide transition is
    * the right lever there, unaffected by this fix.
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
    * by the ash pile (see applyAshAppearance) - a genuinely NEW object
    * growing into existence, which is a different visual claim from an
    * existing part shrinking away (2026-08-12b removed the latter for the
    * skeleton's own parts - see setVanishing's doc).
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
   private static ResourceLocation ashTextureFor(PlayerCorpseEntity entity) {
      float effective = entity.getPhaseProgress();
      float scaled = effective * 8.0F;
      int stageLow = Mth.clamp((int)scaled, 0, 7);
      int stageHigh = Mth.clamp(stageLow + 1, 0, 8);
      float frac = Mth.clamp(scaled - stageLow, 0.0F, 1.0F);
      ResourceLocation blended = BlendedTextureCache.getOrCreate(
         entity.getId(), "ash", ASH_STAGE_TEXTURES[stageLow], ASH_STAGE_TEXTURES[stageHigh], frac, ASH_BASE_TEXTURE
      );
      PlayerCorpseRenderer.maybeSpawnAshParticles(entity, stageLow, stageHigh);
      return blended;
   }

   /**
    * 2026-08-12c, new feature (not a bug fix): ash-motes drifting off the corpse during ASH,
    * spawn rate driven by the SAME real per-pixel erosion data erodeOver itself consumes -
    * BlendedTextureCache.meanErosion(stageHigh) - meanErosion(stageLow) is literally the mean of
    * the exact two ash_N.png alpha channels ashTextureFor() is already blending between this tick,
    * read as "how much MORE eroded, on average, the upcoming stage is than the current one" - the
    * same bytes, not an invented separate proxy signal. Since DecayCurves' 2026-08-12b redesign made
    * stage selection a direct linear map of raw phase progress (no remap), this delta is presently
    * near-constant across the whole ASH phase in practice (each of the 9 stages is evenly spaced in
    * time) - the coupling is real and would automatically track any future retune that makes stage
    * spacing uneven again, it just doesn't have much visible variance to show under the CURRENT
    * (deliberately linear) timeline. Flagging this honestly rather than overselling "1:1 gekoppelt"
    * as more dramatic than it currently looks.
    *
    * Tick-gated (not frame-gated): render() runs once per FRAME, which is framerate-dependent and
    * can fire many times per real game tick - entity.tickCount only advances once per real tick
    * (server-authoritative, mirrored client-side), so gating on "have I already handled this
    * entity's current tickCount" keeps the spawn rate framerate-independent, the same reasoning
    * every other tick-based system in this file already follows.
    *
    * Client-only by construction (this whole class only runs client-side) - uses
    * Minecraft.getInstance().level (guaranteed ClientLevel) rather than entity.level(), whose
    * static type is the more general Level and isn't guaranteed to exposes the client particle-
    * spawning overload without a cast this class has no other reason to need.
    */
   private static final Map<Integer, Integer> LAST_ASH_PARTICLE_TICK = new HashMap<>();
   private static final float ASH_PARTICLE_COUNT_SCALE = 60.0F;

   private static void maybeSpawnAshParticles(PlayerCorpseEntity entity, int stageLow, int stageHigh) {
      Integer lastTick = LAST_ASH_PARTICLE_TICK.get(entity.getId());
      if (lastTick != null && lastTick == entity.tickCount) {
         return;
      }

      LAST_ASH_PARTICLE_TICK.put(entity.getId(), entity.tickCount);
      float delta = BlendedTextureCache.meanErosion(ASH_STAGE_TEXTURES[stageHigh]) - BlendedTextureCache.meanErosion(ASH_STAGE_TEXTURES[stageLow]);
      int count = Math.round(Math.max(delta, 0.0F) * ASH_PARTICLE_COUNT_SCALE);
      if (count <= 0) {
         return;
      }

      ClientLevel level = Minecraft.getInstance().level;
      if (level == null) {
         return;
      }

      float yaw = entity.getYRot() * Mth.DEG_TO_RAD;
      double forwardX = -Mth.sin(yaw);
      double forwardZ = Mth.cos(yaw);
      double rightX = forwardZ;
      double rightZ = -forwardX;
      for (int i = 0; i < count; i++) {
         double alongBody = (entity.getRandom().nextDouble() - 0.5) * 1.85;
         double acrossBody = (entity.getRandom().nextDouble() - 0.5) * 0.55;
         double x = entity.getX() + forwardX * alongBody + rightX * acrossBody;
         double y = entity.getY() + 0.08 + entity.getRandom().nextDouble() * 0.32;
         double z = entity.getZ() + forwardZ * alongBody + rightZ * acrossBody;
         level.addParticle(ParticleTypes.ASH, x, y, z, 0.0, 0.008, 0.0);
      }
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
            PlayerModel<PlayerCorpseEntity> playerModel = PlayerCorpseRenderer.choosePlayerModel(entity, this.wideModel, this.slimModel);
            playerModel.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);
            boolean hatWasVisible = playerModel.hat.visible;
            playerModel.hat.visible = false;

            try {
               if (phase == DecayPhase.EROSION) {
                  float erosion = entity.getPhaseProgress();
                  float scaled = erosion * 8.0F;
                  int stageLow = Mth.clamp((int)scaled, 0, 7);
                  int stageHigh = Mth.clamp(stageLow + 1, 0, 8);
                  float frac = Mth.clamp(scaled - stageLow, 0.0F, 1.0F);
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
               ResourceLocation texture = PlayerCorpseRenderer.SHROUD_STAGE_TEXTURES[0];
               VertexConsumer buffer = bufferSource.getBuffer(RenderType.entityTranslucent(texture));
               int color = ARGB32.color(Math.round(alpha * 255.0F), 255, 255, 255);
               playerModel.renderToBuffer(poseStack, buffer, packedLight, OverlayTexture.NO_OVERLAY, color);
            } finally {
               playerModel.hat.visible = hatWasVisible;
            }
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
       * return null) for a missing name. resetAshState/applyAshAppearance
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
         // A corpse is a static object. Passing live head yaw/pitch into the skeleton model made
         // the skull move independently while lying down and amplified every ASH-phase seam.
         super.setupAnim(entity, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
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
            this.applyAshAppearance(entity.getPhaseProgress());
         }
      }

      private void resetAshState() {
         this.head.visible = true;
         // HumanoidModel.createMesh includes the player's outer "hat" cube. Skeletons must never
         // render it. In the 64x64 ASH texture its UV area also stores the ash-pile swatch, so
         // making the hat visible wrapped that swatch around the skull and visibly changed both
         // its shape and texture at the ASH boundary.
         this.hat.visible = false;
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
         if (this.ashPile != null) {
            this.ashPile.visible = false;
            this.ashPile.xScale = this.ashPile.yScale = this.ashPile.zScale = 0.001F;
         }
      }

      /**
       * Existing bones keep exactly the vanilla skeleton pose and dimensions throughout ASH.
       * Material loss is represented only by texture cutouts and particles; rotating or moving
       * whole bones made the decay read as model deformation. The ash pile is a new object, so its
       * own scale-in remains intentional.
       */
      private void applyAshAppearance(float effective) {
         if (this.ashPile != null) {
            PlayerCorpseRenderer.setAppearing(this.ashPile, DecayCurves.pileAppearT(effective));
         }

         boolean showBones = DecayCurves.showAshBones(effective);
         this.head.visible = showBones;
         this.body.visible = showBones;
         this.leftArm.visible = showBones;
         this.rightArm.visible = showBones;
         this.leftLeg.visible = showBones;
         this.rightLeg.visible = showBones;
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
