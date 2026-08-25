"""Export crop-eval ROI annotations to standard YOLO detection labels.

The reviewable annotation JSON uses normalized top-left ``x, y, w, h``.
YOLO text labels use ``class_id, center_x, center_y, w, h``.  Active-target
metadata is kept in a sidecar because the YOLO detection format has no field
for it.

    python server/export_crop_yolo_labels.py
"""
from __future__ import annotations

import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DATA_ROOT = ROOT / "test-images" / "crop-eval"
ANNOTATIONS_PATH = DATA_ROOT / "roi-annotations.json"
OUTPUT_ROOT = DATA_ROOT / "yolo-labels"
CLASS_IDS = {
    "CUTTING_BOARD_ROI": 0,
    "PAN_COOKING_ROI": 1,
}


def main() -> int:
    annotations = json.loads(ANNOTATIONS_PATH.read_text(encoding="utf-8"))
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    active_targets: dict[str, str] = {}

    for image in annotations["images"]:
        image_id = image["imageId"]
        lines: list[str] = []
        for candidate in image["candidates"]:
            x, y, width, height = candidate["bbox"]
            center_x = x + width / 2
            center_y = y + height / 2
            class_id = CLASS_IDS[candidate["class"]]
            lines.append(
                f"{class_id} {center_x:.6f} {center_y:.6f} "
                f"{width:.6f} {height:.6f}"
            )
            if candidate["activeTarget"]:
                active_targets[image_id] = candidate["candidateId"]
        (OUTPUT_ROOT / f"{image_id}.txt").write_text(
            "\n".join(lines) + "\n", encoding="utf-8"
        )

    (OUTPUT_ROOT / "classes.txt").write_text(
        "\n".join(CLASS_IDS) + "\n", encoding="utf-8"
    )
    (OUTPUT_ROOT / "active-targets.json").write_text(
        json.dumps(active_targets, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"✅ YOLO 라벨 {len(annotations['images'])}개 · "
        f"활성 대상 {len(active_targets)}개 → {OUTPUT_ROOT}"
    )
    return 0


if __name__ == "__main__":
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    raise SystemExit(main())
