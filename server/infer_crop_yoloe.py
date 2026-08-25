"""Run prompted YOLOE inference on the local crop evaluation set.

Ultralytics and its model dependencies are intentionally optional so the
production server does not acquire a large PyTorch dependency before the
experiment proves useful.

    python server/infer_crop_yoloe.py --model yoloe-26n-seg.pt
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / "test-images"
DATA_ROOT = TEST_ROOT / "crop-eval"
DEFAULT_OUTPUT = DATA_ROOT / "predictions" / "yoloe-26n-seg.json"


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def main() -> int:
    parser = argparse.ArgumentParser(description="Run YOLOE crop detector evaluation")
    parser.add_argument("--model", default="yoloe-26n-seg.pt")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--device", default="0")
    parser.add_argument("--imgsz", type=int, default=640)
    parser.add_argument("--confidence", type=float, default=0.05)
    parser.add_argument("--board-prompts", default="cutting board")
    parser.add_argument("--pan-prompts", default="frying pan")
    args = parser.parse_args()

    try:
        from ultralytics import YOLOE
    except ImportError as error:
        raise SystemExit(
            "ultralytics is required only for this experiment. Install it in an "
            "isolated Python 3.12 environment before running this script."
        ) from error

    manifest = load_json(DATA_ROOT / "crop-manifest.json")
    board_prompts = [value.strip() for value in args.board_prompts.split(",") if value.strip()]
    pan_prompts = [value.strip() for value in args.pan_prompts.split(",") if value.strip()]
    prompt_to_class = {
        **{prompt: "CUTTING_BOARD_ROI" for prompt in board_prompts},
        **{prompt: "PAN_COOKING_ROI" for prompt in pan_prompts},
    }
    prompts = list(prompt_to_class)
    model = YOLOE(args.model)
    model.set_classes(prompts)
    sources = [str(TEST_ROOT / case["source"]) for case in manifest["images"]]
    results = model.predict(
        sources,
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
                prompt = prompts[int(class_index)]
                detections.append(
                    {
                        "class": prompt_to_class[prompt],
                        "prompt": prompt,
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
        "model": Path(args.model).name,
        "prompts": prompts,
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
    print(f"✅ YOLOE 추론 {len(output_rows)}장 · 검출 {total}개 → {args.output}")
    return 0


if __name__ == "__main__":
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    raise SystemExit(main())
