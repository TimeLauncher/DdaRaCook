"""Run the production ONNX crop detector over the local evaluation set."""
from __future__ import annotations

import argparse
import json
import sys
import time
from pathlib import Path

from PIL import Image

from roi_cropper import OnnxRoiDetector


ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / "test-images"
DATA_ROOT = TEST_ROOT / "crop-eval"


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate production ONNX ROI detector")
    parser.add_argument(
        "--model",
        type=Path,
        default=ROOT / "server" / "models" / "yoloe-26n-cook-roi.onnx",
    )
    parser.add_argument(
        "--output",
        type=Path,
        default=DATA_ROOT / "predictions" / "yoloe-26n-onnx.json",
    )
    args = parser.parse_args()

    manifest = json.loads(
        (DATA_ROOT / "crop-manifest.json").read_text(encoding="utf-8")
    )
    detector = OnnxRoiDetector(args.model)
    rows = []
    for case in manifest["images"]:
        with Image.open(TEST_ROOT / case["source"]) as opened:
            image = opened.convert("RGB")
        started = time.perf_counter()
        detections = detector.detect(image)
        latency_ms = (time.perf_counter() - started) * 1000
        rows.append(
            {
                "imageId": case["imageId"],
                "latencyMs": round(latency_ms, 3),
                "detections": [
                    {
                        "class": detection.class_name,
                        "confidence": round(detection.confidence, 6),
                        "bbox": [round(value, 6) for value in detection.bbox],
                    }
                    for detection in detections
                ],
            }
        )
    payload = {
        "schemaVersion": 1,
        "model": args.model.name,
        "images": rows,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(
        f"✅ ONNX 추론 {len(rows)}장 · "
        f"검출 {sum(len(row['detections']) for row in rows)}개 → {args.output}"
    )
    return 0


if __name__ == "__main__":
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    raise SystemExit(main())
