"""Render legacy bottom-60 and oracle ROI crop previews for the eval set.

    python server/render_crop_comparison.py
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

from roi_crop import (
    AUTO_ROI,
    DEFAULT_ASPECT_RATIOS,
    Detection,
    bottom_60_fallback,
    crop_window_for_bbox,
    scaled_dimensions,
    select_active_detection,
)


ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / "test-images"
DATA_ROOT = TEST_ROOT / "crop-eval"
OUTPUT_ROOT = DATA_ROOT / "crop-comparison"


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def font(size: int):
    for name in ("malgun.ttf", "arial.ttf"):
        try:
            return ImageFont.truetype(name, size=size)
        except OSError:
            pass
    return ImageFont.load_default()


def normalized_crop(source: Image.Image, bounds, long_edge: int = 768) -> Image.Image:
    cropped = source.crop((bounds.left, bounds.top, bounds.right, bounds.bottom))
    output_size = scaled_dimensions(cropped.width, cropped.height, long_edge)
    if output_size != cropped.size:
        cropped = cropped.resize(output_size, Image.Resampling.LANCZOS)
    return cropped


def fit_tile(image: Image.Image, width: int, height: int) -> Image.Image:
    canvas = Image.new("RGB", (width, height), (20, 20, 20))
    copy = image.copy()
    copy.thumbnail((width, height), Image.Resampling.LANCZOS)
    canvas.paste(copy, ((width - copy.width) // 2, (height - copy.height) // 2))
    return canvas


def comparison_tile(
    image_id: str,
    legacy: Image.Image,
    candidate: Image.Image,
    target_class: str,
    method_label: str,
    method_color: tuple[int, int, int],
) -> Image.Image:
    panel_width, panel_height, header = 300, 300, 56
    tile = Image.new("RGB", (panel_width * 2, panel_height + header), (15, 15, 15))
    tile.paste(fit_tile(legacy, panel_width, panel_height), (0, header))
    tile.paste(fit_tile(candidate, panel_width, panel_height), (panel_width, header))
    draw = ImageDraw.Draw(tile)
    draw.text((8, 4), image_id, fill="white", font=font(17))
    draw.text((8, 30), "LEGACY bottom60", fill=(255, 180, 60), font=font(15))
    draw.text(
        (panel_width + 8, 30),
        method_label,
        fill=method_color,
        font=font(15),
    )
    return tile


def make_sheet(tiles: list[Image.Image], columns: int = 2) -> Image.Image:
    cell_width = max(tile.width for tile in tiles)
    cell_height = max(tile.height for tile in tiles)
    rows = math.ceil(len(tiles) / columns)
    sheet = Image.new("RGB", (cell_width * columns, cell_height * rows), (5, 5, 5))
    for index, tile in enumerate(tiles):
        sheet.paste(tile, ((index % columns) * cell_width, (index // columns) * cell_height))
    return sheet


def main() -> int:
    parser = argparse.ArgumentParser(description="Render legacy/ROI crop comparisons")
    parser.add_argument("--predictions", type=Path)
    parser.add_argument("--confidence", type=float, default=0.05)
    parser.add_argument("--max-gaze-distance", type=float)
    parser.add_argument("--context-padding", type=float, default=0.22)
    parser.add_argument(
        "--auto-roi",
        action="store_true",
        help="select the closest board or pan exactly like AUTO_ROI production mode",
    )
    parser.add_argument("--name", default="crop-comparison")
    args = parser.parse_args()

    manifest = load_json(DATA_ROOT / "crop-manifest.json")
    annotation_rows = load_json(DATA_ROOT / "roi-annotations.json")["images"]
    annotations = {row["imageId"]: row for row in annotation_rows}
    output_root = DATA_ROOT / args.name
    predictions = load_json(args.predictions.resolve()) if args.predictions else None
    prediction_by_id = (
        {row["imageId"]: row for row in predictions["images"]}
        if predictions
        else {}
    )
    legacy_root = output_root / "legacy"
    candidate_root = output_root / ("detector" if predictions else "oracle")
    sheets_root = output_root / "sheets"
    for directory in (legacy_root, candidate_root, sheets_root):
        directory.mkdir(parents=True, exist_ok=True)

    sheets: dict[str, list[Image.Image]] = defaultdict(list)
    retained: dict[str, list[float]] = defaultdict(list)
    for case in manifest["images"]:
        image_id = case["imageId"]
        with Image.open(TEST_ROOT / case["source"]) as opened:
            source = opened.convert("RGB")
        annotation = annotations[image_id]
        active = next(
            candidate for candidate in annotation["candidates"] if candidate["activeTarget"]
        )
        legacy_bounds = bottom_60_fallback(source.width, source.height)
        ratio_name = "4:3" if case["targetClass"] == "CUTTING_BOARD_ROI" else "1:1"
        if predictions:
            prediction_row = prediction_by_id.get(image_id, {"detections": []})
            detections = [
                Detection(
                    class_name=row["class"],
                    confidence=float(row["confidence"]),
                    bbox=tuple(row["bbox"]),
                )
                for row in prediction_row.get("detections", [])
            ]
            selected = select_active_detection(
                detections,
                AUTO_ROI if args.auto_roi else case["targetClass"],
                gaze_anchor=tuple(manifest["selectionPolicy"]["gazeAnchorNormalized"]),
                minimum_confidence=args.confidence,
                maximum_gaze_distance=args.max_gaze_distance,
            )
            if selected is None:
                candidate_bounds = legacy_bounds
                method_label = "FALLBACK bottom60"
                method_color = (255, 180, 60)
            else:
                selected_class = selected.class_name
                candidate_bounds = crop_window_for_bbox(
                    source.width,
                    source.height,
                    selected.bbox,
                    DEFAULT_ASPECT_RATIOS[selected_class],
                    context_padding=args.context_padding,
                )
                active_x, active_y, active_width, active_height = active["bbox"]
                active_left = active_x * source.width
                active_top = active_y * source.height
                active_right = (active_x + active_width) * source.width
                active_bottom = (active_y + active_height) * source.height
                intersection = max(
                    0, min(active_right, candidate_bounds.right) - max(active_left, candidate_bounds.left)
                ) * max(
                    0, min(active_bottom, candidate_bounds.bottom) - max(active_top, candidate_bounds.top)
                )
                coverage = intersection / (
                    active_width * source.width * active_height * source.height
                )
                safe = coverage >= 0.95
                selected_ratio_name = (
                    "4:3" if selected_class == "CUTTING_BOARD_ROI" else "1:1"
                )
                method_label = (
                    f"YOLO {'SAFE' if safe else 'MISS'} {selected_ratio_name}"
                )
                method_color = (70, 255, 90) if safe else (255, 80, 80)
        else:
            candidate_bounds = crop_window_for_bbox(
                source.width,
                source.height,
                tuple(active["bbox"]),
                DEFAULT_ASPECT_RATIOS[case["targetClass"]],
                context_padding=args.context_padding,
            )
            method_label = f"ORACLE ROI {ratio_name} +18%"
            method_color = (70, 255, 90)

        legacy = normalized_crop(source, legacy_bounds)
        candidate = normalized_crop(source, candidate_bounds)
        legacy.save(legacy_root / f"{image_id}.jpg", quality=80)
        candidate.save(candidate_root / f"{image_id}.jpg", quality=80)
        retained[case["targetClass"]].append(
            candidate_bounds.width * candidate_bounds.height / (source.width * source.height)
        )
        group = case["source"].split("/", 1)[0]
        sheets[group].append(
            comparison_tile(
                image_id,
                legacy,
                candidate,
                case["targetClass"],
                method_label,
                method_color,
            )
        )

    for group, tiles in sheets.items():
        make_sheet(tiles).save(sheets_root / f"{group}.jpg", quality=90)

    for target_class, values in retained.items():
        print(
            f"{target_class}: 평균 원본 면적 {sum(values) / len(values):.1%} 유지 "
            f"(n={len(values)})"
        )
    print(f"✅ legacy/ROI crop 비교 {len(manifest['images'])}장 → {output_root}")
    return 0


if __name__ == "__main__":
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    raise SystemExit(main())
