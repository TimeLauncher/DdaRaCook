"""Render recipe-crop annotation overlays and contact sheets.

    python server/render_crop_annotations.py --grid-only
    python server/render_crop_annotations.py

Outputs are written below ignored ``test-images/crop-eval/overlays``.
"""
from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / "test-images"
DATA_ROOT = TEST_ROOT / "crop-eval"
MANIFEST_PATH = DATA_ROOT / "crop-manifest.json"
ANNOTATIONS_PATH = DATA_ROOT / "roi-annotations.json"
OUTPUT_ROOT = DATA_ROOT / "overlays"
COLORS = {
    "CUTTING_BOARD_ROI": (0, 210, 255),
    "PAN_COOKING_ROI": (255, 190, 0),
}
ACTIVE_COLOR = (70, 255, 90)


def font(size: int) -> ImageFont.FreeTypeFont | ImageFont.ImageFont:
    for name in ("malgun.ttf", "arial.ttf"):
        try:
            return ImageFont.truetype(name, size=size)
        except OSError:
            pass
    return ImageFont.load_default()


def read_json(path: Path):
    with path.open(encoding="utf-8") as stream:
        return json.load(stream)


def render_grid(image: Image.Image, draw: ImageDraw.ImageDraw) -> None:
    width, height = image.size
    grid_color = (255, 255, 255, 150)
    grid_font = font(max(16, width // 80))
    for index in range(1, 10):
        x = round(width * index / 10)
        y = round(height * index / 10)
        draw.line((x, 0, x, height), fill=grid_color, width=max(1, width // 700))
        draw.line((0, y, width, y), fill=grid_color, width=max(1, width // 700))
        draw.text((x + 3, 3), f".{index}", fill=(255, 255, 255), font=grid_font,
                  stroke_width=2, stroke_fill=(0, 0, 0))
        draw.text((3, y + 3), f".{index}", fill=(255, 255, 255), font=grid_font,
                  stroke_width=2, stroke_fill=(0, 0, 0))


def render_box(
    draw: ImageDraw.ImageDraw,
    image_size: tuple[int, int],
    candidate: dict,
) -> None:
    width, height = image_size
    x, y, box_width, box_height = candidate["bbox"]
    left = round(x * width)
    top = round(y * height)
    right = round((x + box_width) * width)
    bottom = round((y + box_height) * height)
    active = bool(candidate.get("activeTarget"))
    color = ACTIVE_COLOR if active else COLORS[candidate["class"]]
    line_width = max(6 if active else 3, width // (300 if active else 500))
    draw.rectangle((left, top, right, bottom), outline=color, width=line_width)
    label = f"{'ACTIVE ' if active else ''}{candidate['class']} #{candidate['candidateId']}"
    label_font = font(max(18, width // 60))
    text_box = draw.textbbox((left, top), label, font=label_font, stroke_width=2)
    label_height = text_box[3] - text_box[1] + 8
    label_top = max(0, top - label_height)
    draw.rectangle((left, label_top, min(width, left + text_box[2] - text_box[0] + 8), top), fill=color)
    draw.text((left + 4, label_top + 2), label, fill=(0, 0, 0), font=label_font)


def thumbnail(image: Image.Image, title: str, width: int = 360) -> Image.Image:
    scale = width / image.width
    resized = image.resize((width, round(image.height * scale)), Image.Resampling.LANCZOS)
    header_height = 54
    tile = Image.new("RGB", (width, resized.height + header_height), (25, 25, 25))
    tile.paste(resized, (0, header_height))
    draw = ImageDraw.Draw(tile)
    draw.text((8, 6), title, fill=(255, 255, 255), font=font(18))
    return tile


def make_sheet(tiles: list[Image.Image], columns: int = 4) -> Image.Image:
    cell_width = max(tile.width for tile in tiles)
    cell_height = max(tile.height for tile in tiles)
    rows = math.ceil(len(tiles) / columns)
    sheet = Image.new("RGB", (cell_width * columns, cell_height * rows), (10, 10, 10))
    for index, tile in enumerate(tiles):
        x = (index % columns) * cell_width
        y = (index // columns) * cell_height
        sheet.paste(tile, (x, y))
    return sheet


def main() -> int:
    parser = argparse.ArgumentParser(description="crop ROI annotation overlay renderer")
    parser.add_argument("--grid-only", action="store_true", help="bbox 없이 10%% 좌표 격자만 표시")
    args = parser.parse_args()

    manifest = read_json(MANIFEST_PATH)
    annotations = {} if args.grid_only else {
        row["imageId"]: row for row in read_json(ANNOTATIONS_PATH)["images"]
    }
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    groups: dict[str, list[Image.Image]] = {}

    for case in manifest["images"]:
        source_path = TEST_ROOT / case["source"]
        with Image.open(source_path) as source:
            image = source.convert("RGB")
        draw = ImageDraw.Draw(image, "RGBA")
        render_grid(image, draw)
        for candidate in annotations.get(case["imageId"], {}).get("candidates", []):
            render_box(draw, image.size, candidate)
        output_path = OUTPUT_ROOT / f"{case['imageId']}.jpg"
        image.save(output_path, quality=88)
        group = case["source"].split("/", 1)[0]
        groups.setdefault(group, []).append(thumbnail(image, case["imageId"]))

    suffix = "grid" if args.grid_only else "annotations"
    for group, tiles in groups.items():
        make_sheet(tiles).save(OUTPUT_ROOT / f"{group}-{suffix}.jpg", quality=90)
    print(f"✅ overlay {len(manifest['images'])}장 → {OUTPUT_ROOT}")
    return 0


if __name__ == "__main__":
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    raise SystemExit(main())
