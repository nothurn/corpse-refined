# Changelog

All notable changes to Corpse: Refined are documented here.

## 2.0.3 - Unreleased

### Fixed

- Grave recovery menus now close when the grave block is removed, its BlockEntity changes, or the player moves more than eight blocks away. This prevents items from being inserted into a detached inventory after another player mines the grave.

### Compatibility and verification

- Implemented and compiled for Minecraft 1.19.2 Forge, 1.20.1 Forge, and 1.21.1 NeoForge.
- A two-player in-game test is still required: one player keeps the grave open while another mines it, then both inventories and the dropped items are checked for loss or duplication.

## 2.0.2 - 2026-09-07

### Added

- Death History now opens a dedicated Corpse Settings submenu for grave options. Server operators can toggle future grave creation there; disabling it requires a prominently highlighted red confirmation, while regular players can see the current server rule without changing it.
- `grave_markers_enabled` can disable future grave creation entirely. When disabled, a finishing corpse drops its remaining contents safely instead of creating a grave. Existing grave blocks and legacy graves remain untouched until a player breaks them.

### Fixed

- Corpse placement now checks the visible lying body footprint and the fluid layer directly below it, preventing a corpse from being accepted with only part of its body supported while the remainder lies over lava or water.
- Empty corpses no longer jump directly from the fresh player model to a complete skeleton after `empty_decay_ticks`. They now enter the normal shroud build, full-cover, and erosion sequence so the model swap remains hidden and gradual.
- A corpse no longer keeps rendering the item held at death after the corresponding loot has been removed.
- The ash pile now finishes forming at 86% of the final phase; all skeleton model parts are then hidden for a 14%-long grounded pile-only ending, so sparse texture remnants cannot float in the air near the transition without cutting off the earlier decay.
- Ash particles are sampled along the corpse's rotated head-to-feet and shoulder axes instead of its axis-aligned hitbox, keeping the effect on top of the lying model.
- Grave markers are now real blocks with wooden hardness and axe mining behavior, not attackable entities. Breaking the block drops both stored inventory containers exactly once, while right-clicking still opens the recovery screen.
- Legacy entity graves in existing worlds migrate into the persistent block form when loaded.
- Grave blocks never remove themselves, including after their inventory is emptied or grave creation is disabled. They remain as persistent memorials until a player breaks them.

### Compatibility and verification

- The fixes are implemented for Minecraft 1.19.2 Forge, 1.20.1 Forge, and 1.21.1 NeoForge.
- Clean builds, placement/erosion regression checks, packaged-resource checks, and bytecode checks pass for all three versions.
- Confirmed in Forge 1.20.1 before this follow-up: safe lava-death placement, captured corpse inventory, the gradual body-to-skeleton sequence, and grave creation. The revised particle axis, structural pile-only ending, block mining, persistence, and drop behavior still require an in-game retest.
- Forge 1.19.2 and NeoForge 1.21.1 still require their own complete gameplay pass. This section must receive a release date only after those checks pass.

## 2.0.1 - 2026-08-30

Minecraft 1.21.1 / NeoForge 21.1

### Added

- Ash particles and a small ash pile now accompany the final skeleton decay.
- Configurable environmental decay: water speeds decay up and freezing biomes slow it down.
- Server owners can optionally leave an empty memorial grave marker after decay; this remains disabled by default.

### Changed

- The default full decay cycle now lasts 15 minutes under normal conditions.
- Exact former 10.5- and 30-minute default presets are migrated to the new timing, while custom phase timings remain untouched.
- Skeleton decay now uses progressive pixel erosion instead of shrinking, hiding, or deforming whole body parts.
- Empty corpses still complete their shortened decay without creating a grave marker by default. Corpses containing loot continue to transfer it safely into a grave marker at the end.

### Fixed

- Corpses no longer spawn submerged in lava or water solely because the fluid block has no solid collision shape.
- The complete lying-corpse hitbox is checked during placement, preventing corpses from intersecting nearby walls.
- Unsafe saved corpses and grave markers are relocated to a nearby dry position when their chunk loads.
- Death History and overflow drops now use the corpse's verified final position.
- The skeleton skull no longer gains a second outer shell, samples the ash-pile texture, changes shape, or moves independently during decay.
- The first ash stage now matches the intact skeleton, removing the visual pop at the start of the ash phase.
- Skeleton pixels retain their bone colour until they erode instead of gaining a pale overlay.
- The corpse direction arrow disappears immediately when the tracked corpse is opened, including after reconnecting.

### Recovery and verification

- Existing inaccessible corpses and grave markers are recovered automatically when possible, provided the entity still exists in the world.
- Confirmed in game: a corpse created by dying in lava spawned safely beside the lava lake and remained visible and accessible.
- Verified with a clean Gradle build, texture regression checks, and placement checks for lava, wall intersections, unsupported fluid pools, and saved grave markers.
