package com.playercorpse.entity;

import com.mojang.logging.LogUtils;
import com.playercorpse.PlayerCorpseConfig;
import com.playercorpse.PlayerCorpseCorpseTracker;
import com.playercorpse.menu.PlayerCorpseMenu;
import com.playercorpse.registry.PlayerCorpseEntities;
import com.playercorpse.registry.PlayerCorpseMenus;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction.Axis;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.network.syncher.SynchedEntityData.Builder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity.RemovalReason;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

public class PlayerCorpseEntity extends LivingEntity {
   private static final Logger LOGGER = LogUtils.getLogger();
   public static final int CONTAINER_SIZE = 36;
   private static final EquipmentSlot[] EQUIPMENT_SLOT_ORDER = new EquipmentSlot[]{
      EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET, EquipmentSlot.OFFHAND
   };
   public static final int EQUIPMENT_SIZE = EQUIPMENT_SLOT_ORDER.length;
   private static final EntityDataAccessor<String> DATA_OWNER_NAME = SynchedEntityData.defineId(PlayerCorpseEntity.class, EntityDataSerializers.STRING);
   private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER_UUID = SynchedEntityData.defineId(
      PlayerCorpseEntity.class, EntityDataSerializers.OPTIONAL_UUID
   );
   private static final EntityDataAccessor<Byte> DATA_DECAY_PHASE = SynchedEntityData.defineId(PlayerCorpseEntity.class, EntityDataSerializers.BYTE);
   private static final EntityDataAccessor<Float> DATA_PHASE_PROGRESS = SynchedEntityData.defineId(PlayerCorpseEntity.class, EntityDataSerializers.FLOAT);
   private static final EntityDataAccessor<Byte> DATA_DEATH_CAUSE = SynchedEntityData.defineId(PlayerCorpseEntity.class, EntityDataSerializers.BYTE);
   private static final EntityDataAccessor<ItemStack> DATA_HELD_ITEM = SynchedEntityData.defineId(PlayerCorpseEntity.class, EntityDataSerializers.ITEM_STACK);
   private static final EntityDataAccessor<ItemStack> DATA_EQUIPMENT_HEAD = SynchedEntityData.defineId(
      PlayerCorpseEntity.class, EntityDataSerializers.ITEM_STACK
   );
   private static final EntityDataAccessor<ItemStack> DATA_EQUIPMENT_CHEST = SynchedEntityData.defineId(
      PlayerCorpseEntity.class, EntityDataSerializers.ITEM_STACK
   );
   private static final EntityDataAccessor<ItemStack> DATA_EQUIPMENT_LEGS = SynchedEntityData.defineId(
      PlayerCorpseEntity.class, EntityDataSerializers.ITEM_STACK
   );
   private static final EntityDataAccessor<ItemStack> DATA_EQUIPMENT_FEET = SynchedEntityData.defineId(
      PlayerCorpseEntity.class, EntityDataSerializers.ITEM_STACK
   );
   private static final EntityDataAccessor<ItemStack> DATA_EQUIPMENT_OFFHAND = SynchedEntityData.defineId(
      PlayerCorpseEntity.class, EntityDataSerializers.ITEM_STACK
   );
   private final SimpleContainer inventory = new SimpleContainer(36);
   private final SimpleContainer equipment = new SimpleContainer(EQUIPMENT_SIZE);
   private DecayPhase phase = DecayPhase.HOLD_FRESH;
   private long phaseTicks;
   private long ageInTicks;
   private long emptyTicks;
   private boolean openedByAnyone;
   private final int[] generalOriginalSlot = new int[36];
   private double anchorX;
   private double anchorY;
   private double anchorZ;
   private boolean anchorSet;
   private static final double HITBOX_HALF_LENGTH = 0.9;
   private static final double HITBOX_HALF_WIDTH = 0.3;
   private static final double HITBOX_HEIGHT = 0.6;

   public static int equipmentContainerIndex(EquipmentSlot slot) {
      for (int i = 0; i < EQUIPMENT_SLOT_ORDER.length; i++) {
         if (EQUIPMENT_SLOT_ORDER[i] == slot) {
            return i;
         }
      }

      return -1;
   }

   public static EquipmentSlot equipmentSlotAt(int index) {
      return EQUIPMENT_SLOT_ORDER[index];
   }

   public static int generalSlotForPlayerInventorySlot(int playerInventoryIndex) {
      if (playerInventoryIndex >= 0 && playerInventoryIndex < 9) {
         return 27 + playerInventoryIndex;
      } else {
         return playerInventoryIndex >= 9 && playerInventoryIndex < 36 ? playerInventoryIndex - 9 : -1;
      }
   }

   public PlayerCorpseEntity(EntityType<? extends PlayerCorpseEntity> type, Level level) {
      super(type, level);
      Arrays.fill(this.generalOriginalSlot, -1);
      this.setNoGravity(true);
      this.noPhysics = true;
      this.blocksBuilding = false;
   }

   public void anchorHere() {
      this.anchorX = this.getX();
      this.anchorY = this.getY();
      this.anchorZ = this.getZ();
      this.anchorSet = true;
   }

   protected AABB makeBoundingBox() {
      boolean longAlongX = this.getDirection().getAxis() == Axis.X;
      double halfX = longAlongX ? 0.9 : 0.3;
      double halfZ = longAlongX ? 0.3 : 0.9;
      double x = this.getX();
      double y = this.getY();
      double z = this.getZ();
      return new AABB(x - halfX, y, z - halfZ, x + halfX, y + 0.6, z + halfZ);
   }

   public void setYRot(float yRot) {
      super.setYRot(yRot);
      this.setBoundingBox(this.makeBoundingBox());
   }

   public void knockback(double strength, double x, double z) {
   }

   public boolean isPushedByFluid() {
      return false;
   }

   public boolean isInvulnerableTo(DamageSource damageSource) {
      return true;
   }

   public void setOwner(UUID ownerUuid, String ownerName) {
      this.entityData.set(DATA_OWNER_UUID, Optional.of(ownerUuid));
      this.entityData.set(DATA_OWNER_NAME, ownerName);
      this.setCustomName(Component.literal(ownerName.isEmpty() ? "Corpse" : "Corpse of " + ownerName));
      this.setCustomNameVisible(false);
   }

   public Optional<UUID> getOwnerUuid() {
      return (Optional<UUID>)this.entityData.get(DATA_OWNER_UUID);
   }

   public String getOwnerName() {
      return (String)this.entityData.get(DATA_OWNER_NAME);
   }

   public DecayPhase getDecayPhase() {
      byte ordinal = (Byte)this.entityData.get(DATA_DECAY_PHASE);
      return DecayPhase.values()[ordinal];
   }

   /**
    * Progress (0-1) within the current phase only - resets to 0 on every
    * phase transition, does not represent overall corpse lifetime.
    */
   public float getPhaseProgress() {
      return (Float)this.entityData.get(DATA_PHASE_PROGRESS);
   }

   private void setPhase(DecayPhase newPhase) {
      this.entityData.set(DATA_DECAY_PHASE, (byte)newPhase.ordinal());
      this.phase = newPhase;
      this.phaseTicks = 0L;
      this.entityData.set(DATA_PHASE_PROGRESS, 0.0F);
   }

   /**
    * Roughly equivalent to the old single-scalar system's "decay progress
    * reached 1.0" moment (right as the shroud finished building) - kept as a
    * named check since PlayerCorpseCorpseTracker's HUD-arrow sync still gates
    * on it. Deliberately preserves the known, accepted gap from RECOVERY.md
    * section 7: the arrow stops tracking once the shroud is fully built,
    * long before the corpse could ever reach the grave-marker spawn moment -
    * this was never fixed historically and isn't being fixed here either.
    */
   public boolean isDecayed() {
      return this.getDecayPhase().ordinal() >= DecayPhase.HOLD_FULL_SHROUD.ordinal();
   }

   public boolean hasBeenOpened() {
      return this.openedByAnyone;
   }

   public void setHeldItemForRender(ItemStack stack) {
      this.entityData.set(DATA_HELD_ITEM, stack);
   }

   public ItemStack getHeldItemForRender() {
      return (ItemStack)this.entityData.get(DATA_HELD_ITEM);
   }

   public void setDeathCauseCategory(DeathCauseCategory category) {
      this.entityData.set(DATA_DEATH_CAUSE, (byte)category.ordinal());
   }

   public DeathCauseCategory getDeathCauseCategory() {
      return DeathCauseCategory.values()[this.entityData.get(DATA_DEATH_CAUSE)];
   }

   public SimpleContainer getInventory() {
      return this.inventory;
   }

   public SimpleContainer getEquipment() {
      return this.equipment;
   }

   public void setGeneralOriginalSlot(int corpseSlot, int originalPlayerSlot) {
      this.generalOriginalSlot[corpseSlot] = originalPlayerSlot;
   }

   public int[] getGeneralOriginalSlots() {
      return this.generalOriginalSlot;
   }

   private boolean isInventoryEmpty() {
      return this.inventory.isEmpty() && this.equipment.isEmpty();
   }

   public void tick() {
      super.tick();
      if (!this.getDeltaMovement().equals(Vec3.ZERO)) {
         this.setDeltaMovement(Vec3.ZERO);
      }

      if (this.anchorSet) {
         int minY = this.level().getMinBuildHeight();
         if (this.anchorY < minY) {
            this.anchorY = minY;
         }

         if (this.getX() != this.anchorX || this.getY() != this.anchorY || this.getZ() != this.anchorZ) {
            super.setPos(this.anchorX, this.anchorY, this.anchorZ);
         }
      }

      if (!this.level().isClientSide()) {
         this.syncEquipmentDisplay();
         this.ageInTicks++;
         if (this.isInventoryEmpty()) {
            this.emptyTicks++;
            if (this.emptyTicks >= (Long)PlayerCorpseConfig.EMPTY_DECAY_TICKS.get() && this.phase.ordinal() < DecayPhase.HOLD_FULL_SKELETON.ordinal()) {
               this.setPhase(DecayPhase.HOLD_FULL_SKELETON);
            }
         } else {
            this.emptyTicks = 0L;
         }

         this.tickDecayPhase();
         if ((Boolean)PlayerCorpseConfig.FORCE_DESPAWN_ENABLED.get() && this.ageInTicks >= (Long)PlayerCorpseConfig.FORCE_DESPAWN_TICKS.get()) {
            this.discard();
         }
      }
   }

   private static long durationTicksFor(DecayPhase decayPhase) {
      return switch (decayPhase) {
         case HOLD_FRESH -> (Long)PlayerCorpseConfig.HOLD_FRESH_TICKS.get();
         case BUILD -> (Long)PlayerCorpseConfig.DECAY_TICKS.get();
         case HOLD_FULL_SHROUD -> (Long)PlayerCorpseConfig.HOLD_FULL_SHROUD_TICKS.get();
         case EROSION -> (Long)PlayerCorpseConfig.SHROUD_EROSION_TICKS.get();
         case HOLD_FULL_SKELETON -> (Long)PlayerCorpseConfig.HOLD_FULL_SKELETON_TICKS.get();
         case ASH -> (Long)PlayerCorpseConfig.ASH_TICKS.get();
      };
   }

   private void tickDecayPhase() {
      this.phaseTicks++;
      long duration = durationTicksFor(this.phase);
      float progress = Mth.clamp((float)this.phaseTicks / (float)duration, 0.0F, 1.0F);
      if (progress != this.getPhaseProgress()) {
         this.entityData.set(DATA_PHASE_PROGRESS, progress);
      }

      if (this.phaseTicks >= duration) {
         if (this.phase.isFinal()) {
            this.completeAshAndDiscard();
         } else {
            this.setPhase(this.phase.next());
         }
      }
   }

   /**
    * Ash phase complete: spawn a grave marker transferring both containers
    * if anything remains (RECOVERY.md section 7 - "nur wenn noch Loot drin
    * ist"), then discard. If the corpse is already empty, nothing is
    * spawned and discard() below simply removes it - remove()'s own
    * ground-drop fallback is a no-op too, since there's nothing left in
    * either container.
    */
   private void completeAshAndDiscard() {
      // TEMPORARY DIAGNOSTIC LOGGING (2026-08-11) - NOT permanent, remove once
      // BUG 4 (floating grave marker) is actually root-caused from Noah's next
      // log. Proves this method runs at all, independent of whether a marker
      // ends up spawned.
      LOGGER.info(
         "[PlayerCorpse-DIAG-GRAVE] completeAshAndDiscard() reached for corpse {} - inventoryEmpty={} corpse.getY()={} blockPosition()={}",
         this.getId(), this.isInventoryEmpty(), this.getY(), this.blockPosition()
      );
      if (!this.isInventoryEmpty() && this.level() instanceof ServerLevel serverLevel) {
         double groundY = findGroundY(serverLevel, this.blockPosition());
         PlayerGraveMarkerEntity marker = new PlayerGraveMarkerEntity(PlayerCorpseEntities.GRAVE_MARKER.get(), serverLevel);
         marker.setPos(this.getX(), groundY, this.getZ());
         marker.setYRot(this.getYRot());
         marker.transferFrom(this.inventory, this.equipment, this.generalOriginalSlot);
         serverLevel.addFreshEntity(marker);
         // TEMPORARY DIAGNOSTIC LOGGING (2026-08-11) - NOT permanent. Logs all
         // three Y values from BUG 4's fix chain in one line: the corpse's own
         // Y (the old, discredited source), what findGroundY() actually
         // computed, and the marker's real Y after setPos() + addFreshEntity()
         // - so a mismatch between "findGroundY() returned" and "marker ended
         // up at" (e.g. something repositioning it afterward) is visible
         // directly in the log instead of only inferred from re-reading code.
         LOGGER.info(
            "[PlayerCorpse-DIAG-GRAVE] marker {} spawned for corpse {} - corpse.getY()={} findGroundY()={} marker.getY() after spawn={}",
            marker.getId(), this.getId(), this.getY(), groundY, marker.getY()
         );
      }

      this.discard();
   }

   /**
    * BUG 4 fix: the marker used to spawn at this.getY() directly. Read
    * through this.getY()'s actual provenance end to end (PlayerCorpseEvents
    * sets it once at death via findSafeCorpsePos - the same non-solid block
    * the dying player's own feet occupied - then tick()'s anchor logic pins
    * it there unchanged for the corpse's entire lifetime) and through
    * makeBoundingBox()'s own [y, y+0.6] hitbox, which is bottom-anchored at
    * y, not centered around it. Both say this.getY() should already BE
    * ground level, not a torso-centered offset - I could not reproduce a
    * concrete Y-miscalculation by reading the code alone.
    *
    * Searching down for solid ground at spawn time anyway, rather than
    * trusting the inherited value: this runs up to ~12,600 ticks (10.5
    * real-world minutes, RECOVERY.md section 7's default total) after that
    * Y was captured, and unlike the corpse's own tick() there is no anchor
    * re-asserting it - terrain within that window (dug out, blown up,
    * flooded/drained) can change the actual solid surface out from under a
    * value that was only ever correct at the moment of death. This removes
    * that dependency entirely instead of patching one specific cause -
    * same reasoning findSafeCorpsePos already applies for the corpse's own
    * spawn, just searching down for the first solid block instead of up
    * for the first clear one.
    */
   private static double findGroundY(ServerLevel level, BlockPos start) {
      BlockPos.MutableBlockPos pos = start.mutable();
      for (int i = 0; i < 16; i++) {
         if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
            return pos.getY() + 1;
         }

         pos.move(0, -1, 0);
      }

      return start.getY();
   }

   public void remove(RemovalReason reason) {
      if (!this.level().isClientSide() && !this.isInventoryEmpty()) {
         this.dropContainer(this.inventory);
         this.dropContainer(this.equipment);
      }

      super.remove(reason);
   }

   private void dropContainer(SimpleContainer container) {
      for (int i = 0; i < container.getContainerSize(); i++) {
         ItemStack stack = container.getItem(i);
         if (!stack.isEmpty()) {
            this.level().addFreshEntity(new ItemEntity(this.level(), this.getX(), this.getY(), this.getZ(), stack));
         }
      }

      container.clearContent();
   }

   public InteractionResult interact(Player player, InteractionHand hand) {
      if (hand != InteractionHand.MAIN_HAND) {
         return InteractionResult.PASS;
      }

      if (!this.level().isClientSide() && player instanceof ServerPlayer serverPlayer) {
         if (!this.openedByAnyone) {
            this.openedByAnyone = true;
            if (this.level() instanceof ServerLevel serverLevel) {
               PlayerCorpseCorpseTracker.onCorpseOpened(serverLevel, this);
            }
         }

         Component title = (Component)(this.getCustomName() != null ? this.getCustomName() : Component.literal("Corpse"));
         serverPlayer.openMenu(
            new SimpleMenuProvider(
               (containerId, playerInventory, p) -> new PlayerCorpseMenu(
                  (MenuType<?>)PlayerCorpseMenus.CORPSE_MENU.get(), containerId, playerInventory, this.equipment, this.inventory, this.generalOriginalSlot
               ),
               title
            )
         );
      }

      return InteractionResult.sidedSuccess(this.level().isClientSide());
   }

   public boolean isPickable() {
      return true;
   }

   public boolean isPushable() {
      return false;
   }

   protected void doPush(Entity entity) {
   }

   public Iterable<ItemStack> getArmorSlots() {
      return List.of(
         this.getItemBySlot(EquipmentSlot.FEET),
         this.getItemBySlot(EquipmentSlot.LEGS),
         this.getItemBySlot(EquipmentSlot.CHEST),
         this.getItemBySlot(EquipmentSlot.HEAD)
      );
   }

   public ItemStack getItemBySlot(EquipmentSlot slot) {
      if (slot == EquipmentSlot.MAINHAND) {
         return this.getHeldItemForRender();
      }

      EntityDataAccessor<ItemStack> accessor = syncedEquipmentAccessor(slot);
      return accessor != null ? (ItemStack)this.entityData.get(accessor) : ItemStack.EMPTY;
   }

   @Nullable
   private static EntityDataAccessor<ItemStack> syncedEquipmentAccessor(EquipmentSlot slot) {
      return switch (slot) {
         case HEAD -> DATA_EQUIPMENT_HEAD;
         case CHEST -> DATA_EQUIPMENT_CHEST;
         case LEGS -> DATA_EQUIPMENT_LEGS;
         case FEET -> DATA_EQUIPMENT_FEET;
         case OFFHAND -> DATA_EQUIPMENT_OFFHAND;
         default -> null;
      };
   }

   private void syncEquipmentDisplay() {
      for (int i = 0; i < EQUIPMENT_SIZE; i++) {
         EntityDataAccessor<ItemStack> accessor = syncedEquipmentAccessor(EQUIPMENT_SLOT_ORDER[i]);
         ItemStack current = this.equipment.getItem(i);
         if (!ItemStack.matches(current, (ItemStack)this.entityData.get(accessor))) {
            this.entityData.set(accessor, current.copy());
         }
      }
   }

   public void setItemSlot(EquipmentSlot slot, ItemStack stack) {
   }

   public HumanoidArm getMainArm() {
      return HumanoidArm.RIGHT;
   }

   protected void defineSynchedData(Builder builder) {
      super.defineSynchedData(builder);
      builder.define(DATA_OWNER_NAME, "");
      builder.define(DATA_OWNER_UUID, Optional.empty());
      builder.define(DATA_DECAY_PHASE, (byte)DecayPhase.HOLD_FRESH.ordinal());
      builder.define(DATA_PHASE_PROGRESS, 0.0F);
      builder.define(DATA_DEATH_CAUSE, (byte)DeathCauseCategory.OTHER.ordinal());
      builder.define(DATA_HELD_ITEM, ItemStack.EMPTY);
      builder.define(DATA_EQUIPMENT_HEAD, ItemStack.EMPTY);
      builder.define(DATA_EQUIPMENT_CHEST, ItemStack.EMPTY);
      builder.define(DATA_EQUIPMENT_LEGS, ItemStack.EMPTY);
      builder.define(DATA_EQUIPMENT_FEET, ItemStack.EMPTY);
      builder.define(DATA_EQUIPMENT_OFFHAND, ItemStack.EMPTY);
   }

   public void addAdditionalSaveData(CompoundTag compound) {
      compound.put("Items", this.inventory.createTag(this.registryAccess()));
      compound.put("Equipment", this.equipment.createTag(this.registryAccess()));
      compound.putString("OwnerName", this.getOwnerName());
      this.getOwnerUuid().ifPresent(uuid -> compound.putUUID("OwnerUUID", uuid));
      compound.putString("DecayPhase", this.phase.name());
      compound.putLong("PhaseTicks", this.phaseTicks);
      compound.putBoolean("OpenedByAnyone", this.openedByAnyone);
      compound.putLong("AgeInTicks", this.ageInTicks);
      compound.putLong("EmptyTicks", this.emptyTicks);
      compound.putIntArray("GeneralOriginalSlots", this.generalOriginalSlot);
      compound.putByte("DeathCause", (byte)this.getDeathCauseCategory().ordinal());
      if (!this.getHeldItemForRender().isEmpty()) {
         compound.put("HeldItemForRender", this.getHeldItemForRender().save(this.registryAccess()));
      }
   }

   public void readAdditionalSaveData(CompoundTag compound) {
      this.inventory.fromTag(compound.getList("Items", 10), this.registryAccess());
      if (compound.contains("Equipment")) {
         this.equipment.fromTag(compound.getList("Equipment", 10), this.registryAccess());
      }

      this.entityData.set(DATA_OWNER_NAME, compound.getString("OwnerName"));
      if (compound.hasUUID("OwnerUUID")) {
         this.entityData.set(DATA_OWNER_UUID, Optional.of(compound.getUUID("OwnerUUID")));
      }

      DecayPhase savedPhase = DecayPhase.HOLD_FRESH;
      if (compound.contains("DecayPhase")) {
         try {
            savedPhase = DecayPhase.valueOf(compound.getString("DecayPhase"));
         } catch (IllegalArgumentException e) {
            savedPhase = DecayPhase.HOLD_FRESH;
         }
      }

      this.phase = savedPhase;
      this.entityData.set(DATA_DECAY_PHASE, (byte)savedPhase.ordinal());
      this.phaseTicks = compound.getLong("PhaseTicks");
      long durationForSavedPhase = durationTicksFor(savedPhase);
      this.entityData.set(DATA_PHASE_PROGRESS, Mth.clamp((float)this.phaseTicks / (float)durationForSavedPhase, 0.0F, 1.0F));
      this.openedByAnyone = compound.getBoolean("OpenedByAnyone");
      this.ageInTicks = compound.getLong("AgeInTicks");
      this.emptyTicks = compound.getLong("EmptyTicks");
      int[] savedOriginalSlots = compound.getIntArray("GeneralOriginalSlots");
      if (savedOriginalSlots.length == 36) {
         System.arraycopy(savedOriginalSlots, 0, this.generalOriginalSlot, 0, 36);
      }

      int deathCauseOrdinal = compound.getByte("DeathCause") & 255;
      if (deathCauseOrdinal < DeathCauseCategory.values().length) {
         this.entityData.set(DATA_DEATH_CAUSE, (byte)deathCauseOrdinal);
      }

      if (compound.contains("HeldItemForRender")) {
         this.entityData.set(DATA_HELD_ITEM, ItemStack.parseOptional(this.registryAccess(), compound.getCompound("HeldItemForRender")));
      }
   }
}
