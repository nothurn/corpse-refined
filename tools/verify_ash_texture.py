import numpy as np
from PIL import Image

BASE = "../src/main/resources/assets/playercorpse/textures/entity/ash_{}.png"
TORSO_CORE = (24, 24, 26, 26)
HEAD_TOP = (8, 0, 16, 8)

previous_overall = -1.0
for i in range(9):
    img = np.array(Image.open(BASE.format(i)).convert("RGBA"))
    alpha = img[:, :, 3].astype(np.float64) / 255.0
    x0, y0, x1, y1 = HEAD_TOP
    head_mean = alpha[y0:y1, x0:x1].mean()
    x0, y0, x1, y1 = TORSO_CORE
    torso_mean = alpha[y0:y1, x0:x1].mean()
    overall = alpha.mean()
    if i == 0:
        assert np.max(alpha) == 0.0, "stage 0 must not alter the skeleton at the ASH boundary"
    elif i <= 4:
        assert head_mean > torso_mean, f"stage {i}: skull crown should begin eroding ahead of the torso core"
    assert overall >= previous_overall, f"stage {i}: erosion must be monotonic"
    previous_overall = overall
    print(f"stage {i}: overall_mean={overall:.3f} head_top_mean={head_mean:.3f} torso_core_mean={torso_mean:.3f}")

print("OK: stage 0 is neutral; stages 1-8 progress monotonically and early skull-crown erosion leads the torso core")
