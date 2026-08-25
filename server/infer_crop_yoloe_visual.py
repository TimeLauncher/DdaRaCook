"""Run YOLOE with a two-class visual reference assembled from eval examples.

This is an experiment, not training. Two labeled active-object crops are placed
on a reference canvas and supplied through YOLOE's visual prompt predictor.
The chosen reference image IDs are recorded in the output for honest reporting.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / "test-images"
DATA_ROOT = TEST_ROOT / "crop-eval"
DEFAULT_OUTPUT = DATA_ROOT / "predictions" / "yoloe-26n-visual.json"
CLASS_NAMES = ["CUTTING_BOARD_ROI", "PAN_COOKING_ROI"]


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def build_reference(
    manifest: dict,
    annotations: dict,
    reference_ids: list[str],
    output_path: Path,
) -> list[list[float]]:
    case_by_id = {case["imageId"]: case for case in manifest["images"]}
    annotation_by_id = {row["imageId"]: row for row in annotations["images"]}
    panel_size = 512
    canvas = Image.new("RGB", (panel_size * len(reference_ids), panel_size), (96, 96, 96))
    prompt_boxes: list[list[float]] = []

    for index, image_id in enumerate(reference_ids):
        case = case_by_id[image_id]
        annotation = annotation_by_id[image_id]
        active = next(candidate for candidate in annotation["candidates"] if candidate["activeTarget"])
        with Image.open(TEST_ROOT / case["source"]) as opened:
            source = opened.convert("RGB")
        x, y, width, height = active["bbox"]
        crop = source.crop(
            (
                round(x * source.width),
                round(y * source.height),
                round((x + width) * source.width),
                round((y + height) * source.height),
            )
        )
        crop.thumbnail((panel_size - 24, panel_size - 24), Image.Resampling.LANCZOS)
        left = index * panel_size + (panel_size - crop.width) // 2
        top = (panel_size - crop.height) // 2
        canvas.paste(crop, (left, top))
        prompt_boxes.append([left, top, left + crop.width, top + crop.height])

    output_path.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output_path, quality=92)
    return prompt_boxes


def main() -> int:
    parser = argparse.ArgumentParser(description="Run visual-prompt YOLOE crop evaluation")
    parser.add_argument("--model", default="yoloe-26n-seg.pt")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--device", default="cpu")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--confidence", type=float, default=0.05)
    parser.add_argument("--board-reference", default="soya_chop_progress_02")
    parser.add_argument("--pan-reference", default="soya_pan_sausage_added")
    args = parser.parse_args()

    try:
        import numpy as np
        from ultralytics import YOLOE
        from ultralytics.models.yolo.yoloe import YOLOEVPSegPredictor
    except ImportError as error:
        raise SystemExit("ultralytics and numpy are required for visual prompting") from error

    manifest = load_json(DATA_ROOT / "crop-manifest.json")
    annotations = load_json(DATA_ROOT / "roi-annotations.json")
    reference_ids = [args.board_reference, args.pan_reference]
    reference_path = DATA_ROOT / "visual-prompt" / "board-pan-reference.jpg"
    prompt_boxes = build_reference(
        manifest, annotations, reference_ids, reference_path
    )
    visual_prompts = {
        "bboxes": np.asarray(prompt_boxes, dtype=np.float32),
        "cls": np.asarray([0, 1], dtype=np.int32),
    }

    model = YOLOE(args.model)
    sources = [str(TEST_ROOT / case["source"]) for case in manifest["images"]]
    results = model.predict(
        sources,
        refer_image=str(reference_path),
        visual_prompts=visual_prompts,
        predictor=YOLOEVPSegPredictor,
        conf=args.confidence,
        imgsz=args.imgsz,
        device=args.device,
        verbose=False,
    )

    output_rows = []
    for case, result in zip(manifest["images"], results, strict=True):
        detections = []
        if result.boxes is not None:
            xywhn = result.boxes.xywhn.detach().cpu().tolist()
            confidences = result.boxes.conf.detach().cpu().tolist()
            classes = result.boxes.cls.detach().cpu().tolist()
            for (center_x, center_y, width, height), confidence, class_index in zip(
                xywhn, confidences, classes, strict=True
            ):
                class_id = int(class_index)
                if class_id >= len(CLASS_NAMES):
                    continue
                detections.append(
                    {
                        "class": CLASS_NAMES[class_id],
                        "confidence": round(float(confidence), 6),
                        "bbox": [
                            round(center_x - width / 2, 6),
                            round(center_y - height / 2, 6),
                            round(width, 6),
                            round(height, 6),
                        ],
                    }
                )
        speed = getattr(result, "speed", {}) or {}
        output_rows.append(
            {
                "imageId": case["imageId"],
                "latencyMs": round(float(sum(speed.values())), 3),
                "detections": detections,
            }
        )

    payload = {
        "schemaVersion": 1,
        "model": f"{Path(args.model).name}:visual-prompt",
        "referenceImageIds": reference_ids,
        "referencePath": str(reference_path),
        "imgsz": args.imgsz,
        "inferenceConfidence": args.confidence,
        "device": args.device,
        "images": output_rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    total = sum(len(row["detections"]) for row in output_rows)
    print(f"✅ visual-prompt YOLOE {len(output_rows)}장 · 검출 {total}개 → {args.output}")
    return 0


if __name__ == "__main__":
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    raise SystemExit(main())
