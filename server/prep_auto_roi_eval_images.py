"""Prepare pair-named AUTO_ROI JPEGs for the labeled VLM evaluation set."""
from __future__ import annotations

import argparse
import base64
import json
import sys
from collections import Counter
from pathlib import Path

import roi_cropper
from roi_crop import AUTO_ROI
from roi_cropper import OnnxRoiDetector, prepare_judge_image


ROOT = Path(__file__).resolve().parents[1]
TEST_ROOT = ROOT / "test-images"
DATA_ROOT = TEST_ROOT / "crop-eval"


def main() -> int:
    parser = argparse.ArgumentParser(
        description="Prepare production-format AUTO_ROI images for eval.py"
    )
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--context-padding", type=float, required=True)
    args = parser.parse_args()

    pairs = json.loads(
        (DATA_ROOT / "judgment-manifest.json").read_text(encoding="utf-8")
    )
    args.output.mkdir(parents=True, exist_ok=True)
    roi_cropper.CONTEXT_PADDING = args.context_padding
    detector = OnnxRoiDetector()
    prepared_by_source: dict[str, tuple[bytes, str]] = {}
    decisions: Counter[str] = Counter()

    for pair in pairs:
        for role in ("start", "current"):
            source_name = pair.get(role)
            if not source_name:
                continue
            if source_name not in prepared_by_source:
                source_bytes = (TEST_ROOT / source_name).read_bytes()
                encoded, decision = prepare_judge_image(
                    base64.b64encode(source_bytes).decode("ascii"),
                    AUTO_ROI,
                    detector=detector,
                )
                prepared_by_source[source_name] = (
                    base64.b64decode(encoded),
                    decision.mode,
                )
                decisions[decision.mode] += 1
            output_bytes, _ = prepared_by_source[source_name]
            (args.output / f"{pair['pairId']}_{role}.jpg").write_bytes(output_bytes)

    print(
        f"AUTO_ROI 평가 입력 {len(pairs)}쌍 · 원본 {len(prepared_by_source)}장 · "
        f"padding={args.context_padding:.2f} · {dict(decisions)} → {args.output}"
    )
    return 0


if __name__ == "__main__":
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    raise SystemExit(main())
