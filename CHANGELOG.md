# Changelog

All notable changes to Corpse: Refined are documented here.

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
