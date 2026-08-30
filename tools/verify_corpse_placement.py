"""Numerical regression harness for CorpsePlacement's search policy."""

import math

VERTICAL_OFFSETS = (0, 1, -1, 2, -2, 3, -3, 4, -4, *range(5, 17))


def cells(bounds):
    min_x, min_y, min_z, max_x, max_y, max_z = bounds
    eps = 1.0e-7
    for x in range(math.floor(min_x + eps), math.floor(max_x - eps) + 1):
        for y in range(math.floor(min_y + eps), math.floor(max_y - eps) + 1):
            for z in range(math.floor(min_z + eps), math.floor(max_z - eps) + 1):
                yield x, y, z


def move(bounds, x, y, z):
    min_x, min_y, min_z, max_x, max_y, max_z = bounds
    return min_x + x, min_y + y, min_z + z, max_x + x, max_y + y, max_z + z


def safe(bounds, solid, fluid):
    occupied = set(cells(bounds))
    return occupied.isdisjoint(solid) and occupied.isdisjoint(fluid)


def supported(bounds, solid):
    return not set(cells(move(bounds, 0, -0.125, 0))).isdisjoint(solid)


def search(base_bounds, solid, fluid, max_radius, require_support):
    for radius in range(max_radius + 1):
        for y in VERTICAL_OFFSETS:
            for x in range(-radius, radius + 1):
                for z in range(-radius, radius + 1):
                    if radius and max(abs(x), abs(z)) != radius:
                        continue
                    candidate = move(base_bounds, x, y, z)
                    if safe(candidate, solid, fluid) and (not require_support or supported(candidate, solid)):
                        return x, y, z, candidate
    return None


def find(base_bounds, solid, fluid):
    return search(base_bounds, solid, fluid, 8, True) or search(base_bounds, solid, fluid, 2, False)


def main():
    # Lying corpse: the preferred 1.8-block body volume overlaps lava, while a dry bank exists nearby.
    corpse = (-0.3, 0.0, -1.3, 0.3, 0.6, 0.5)
    lava = {(x, 0, z) for x in range(-1, 2) for z in range(-2, 2)}
    floor = {(x, -1, z) for x in range(-10, 11) for z in range(-10, 11)}
    bank_result = find(corpse, floor, lava)
    assert bank_result is not None and safe(bank_result[3], floor, lava)
    assert supported(bank_result[3], floor)

    # A collision in the far end of the lying body's bounds invalidates the whole placement.
    wall = {(0, 0, -1)}
    wall_result = find(corpse, floor, wall)
    assert wall_result is not None and wall_result[:3] != (0, 0, 0)
    assert safe(wall_result[3], floor, wall)

    # In a large unsupported fluid pool, the second pass chooses dry air rather than leaving loot submerged.
    pool = {(x, 0, z) for x in range(-10, 11) for z in range(-10, 11)}
    fallback_result = find(corpse, set(), pool)
    assert fallback_result is not None and fallback_result[1] >= 1
    assert safe(fallback_result[3], set(), pool)

    # A 0.75x1.3 grave marker gets the same dry, supported relocation behavior.
    marker = (-0.375, 0.0, -0.375, 0.375, 1.3, 0.375)
    marker_result = find(marker, floor, {(0, 0, 0), (0, 1, 0)})
    assert marker_result is not None and supported(marker_result[3], floor)
    assert safe(marker_result[3], floor, {(0, 0, 0), (0, 1, 0)})

    print("PASS: full bounds, fluids, support preference, fallback, and marker relocation")


if __name__ == "__main__":
    main()
