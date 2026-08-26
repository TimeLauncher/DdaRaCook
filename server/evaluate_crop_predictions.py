"""Evaluate detector predictions against crop-eval ROI annotations.

    python server/evaluate_crop_predictions.py --oracle
    python server/evaluate_crop_predictions.py --predictions predictions.json
"""
from __future__ import annotations

import argparse
import json
import statistics
import sys
from dataclasses import asdict, dataclass
from pathlib import Path

from roi_crop import (
    AUTO_ROI,
    DEFAULT_ASPECT_RATIOS,
    Detection,
    bottom_60_fallback,
    crop_window_for_bbox,
    intersection_over_union,
    select_active_detection,
)


ROOT = Path(__file__).resolve().parents[1]
DATA_ROOT = ROOT / "test-images" / "crop-eval"


@dataclass
class ClassMetrics:
    images: int = 0
    ground_truth_boxes: int = 0
    predicted_boxes: int = 0
    true_positives: int = 0
    false_positives: int = 0
    false_negatives: int = 0
    active_hits: int = 0
    active_overlaps: int = 0
    correct_class_selections: int = 0
    wrong_class_selections: int = 0
    fallbacks: int = 0
    roi_crops_covered: int = 0
    final_crops_covered: int = 0

    def summary(self) -> dict:
        precision_denominator = self.true_positives + self.false_positives
        recall_denominator = self.true_positives + self.false_negatives
        return {
            **asdict(self),
            "precision": (
                self.true_positives / precision_denominator
                if precision_denominator
                else 0.0
            ),
            "recall": (
                self.true_positives / recall_denominator if recall_denominator else 0.0
            ),
            "activeTargetHitRate": self.active_hits / self.images if self.images else 0.0,
            "activeTargetOverlapRate": (
                self.active_overlaps / self.images if self.images else 0.0
            ),
            "correctClassSelectionRate": (
                self.correct_class_selections / self.images if self.images else 0.0
            ),
            "wrongClassSelectionRate": (
                self.wrong_class_selections / self.images if self.images else 0.0
            ),
            "fallbackRate": self.fallbacks / self.images if self.images else 0.0,
            "roiCropCoverageRate": (
                self.roi_crops_covered / (self.images - self.fallbacks)
                if self.images > self.fallbacks
                else 0.0
            ),
            "finalCropCoverageRate": (
                self.final_crops_covered / self.images if self.images else 0.0
            ),
        }


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def oracle_predictions(annotations: dict) -> dict:
    return {
        "model": "oracle-ground-truth",
        "images": [
            {
                "imageId": image["imageId"],
                "latencyMs": 0.0,
                "detections": [
                    {
                        "class": candidate["class"],
                        "confidence": 1.0,
                        "bbox": candidate["bbox"],
                    }
                    for candidate in image["candidates"]
                ],
            }
            for image in annotations["images"]
        ],
    }


def greedy_matches(
    detections: list[Detection],
    ground_truth: list[tuple[float, float, float, float]],
    iou_threshold: float,
) -> tuple[int, int, int]:
    unmatched = set(range(len(ground_truth)))
    true_positives = 0
    for detection in sorted(detections, key=lambda item: item.confidence, reverse=True):
        choices = [
            (intersection_over_union(detection.bbox, ground_truth[index]), index)
            for index in unmatched
        ]
        if not choices:
            continue
        best_iou, best_index = max(choices)
        if best_iou >= iou_threshold:
            unmatched.remove(best_index)
            true_positives += 1
    false_positives = len(detections) - true_positives
    false_negatives = len(ground_truth) - true_positives
    return true_positives, false_positives, false_negatives


def intersection_over_smaller(
    first: tuple[float, float, float, float],
    second: tuple[float, float, float, float],
) -> float:
    ax, ay, aw, ah = first
    bx, by, bw, bh = second
    intersection = max(0.0, min(ax + aw, bx + bw) - max(ax, bx)) * max(
        0.0, min(ay + ah, by + bh) - max(ay, by)
    )
    smaller = min(aw * ah, bw * bh)
    return intersection / smaller if smaller > 0 else 0.0


def active_coverage_in_window(active_bbox, window, image_width, image_height) -> float:
    window_bbox = (
        window.left / image_width,
        window.top / image_height,
        window.width / image_width,
        window.height / image_height,
    )
    ax, ay, aw, ah = active_bbox
    wx, wy, ww, wh = window_bbox
    intersection = max(0.0, min(ax + aw, wx + ww) - max(ax, wx)) * max(
        0.0, min(ay + ah, wy + wh) - max(ay, wy)
    )
    return intersection / (aw * ah) if aw * ah > 0 else 0.0


def evaluate(
    predictions: dict,
    minimum_confidence: float,
    iou_threshold: float,
    maximum_gaze_distance: float | None = None,
    context_padding: float = 0.18,
    auto_roi: bool = False,
) -> tuple[dict, list[str]]:
    manifest = load_json(DATA_ROOT / "crop-manifest.json")
    annotations = load_json(DATA_ROOT / "roi-annotations.json")
    annotation_by_id = {row["imageId"]: row for row in annotations["images"]}
    prediction_by_id = {row["imageId"]: row for row in predictions.get("images", [])}
    gaze_anchor = tuple(manifest["selectionPolicy"]["gazeAnchorNormalized"])
    metrics: dict[str, ClassMetrics] = {}
    failures: list[str] = []
    latencies: list[float] = []

    for case in manifest["images"]:
        image_id = case["imageId"]
        expected_class = case["targetClass"]
        class_metrics = metrics.setdefault(expected_class, ClassMetrics())
        class_metrics.images += 1
        annotation = annotation_by_id[image_id]
        ground_truth = [
            tuple(candidate["bbox"])
            for candidate in annotation["candidates"]
            if candidate["class"] == expected_class
        ]
        active_ground_truth = next(
            tuple(candidate["bbox"])
            for candidate in annotation["candidates"]
            if candidate["activeTarget"]
        )
        image_width = annotation["imageWidth"]
        image_height = annotation["imageHeight"]
        class_metrics.ground_truth_boxes += len(ground_truth)

        prediction_row = prediction_by_id.get(image_id, {"detections": []})
        if isinstance(prediction_row.get("latencyMs"), (int, float)):
            latencies.append(float(prediction_row["latencyMs"]))
        all_detections = [
            Detection(
                class_name=row["class"],
                confidence=float(row["confidence"]),
                bbox=tuple(row["bbox"]),
            )
            for row in prediction_row.get("detections", [])
            if float(row.get("confidence", 0)) >= minimum_confidence
        ]
        detections = [
            detection
            for detection in all_detections
            if detection.class_name == expected_class
        ]
        class_metrics.predicted_boxes += len(detections)
        true_positives, false_positives, false_negatives = greedy_matches(
            detections, ground_truth, iou_threshold
        )
        class_metrics.true_positives += true_positives
        class_metrics.false_positives += false_positives
        class_metrics.false_negatives += false_negatives

        selected = select_active_detection(
            all_detections if auto_roi else detections,
            AUTO_ROI if auto_roi else expected_class,
            gaze_anchor=gaze_anchor,
            minimum_confidence=minimum_confidence,
            maximum_gaze_distance=maximum_gaze_distance,
        )
        if selected is None:
            class_metrics.fallbacks += 1
            final_window = bottom_60_fallback(image_width, image_height)
            failures.append(f"{image_id}: no selected {expected_class}")
        else:
            if selected.class_name != expected_class:
                class_metrics.wrong_class_selections += 1
                failures.append(
                    f"{image_id}: selected wrong class "
                    f"({selected.class_name}, expected {expected_class})"
                )
            else:
                class_metrics.correct_class_selections += 1
            active_iou = intersection_over_union(selected.bbox, active_ground_truth)
            if (
                selected.class_name == expected_class
                and intersection_over_smaller(selected.bbox, active_ground_truth) >= 0.5
            ):
                class_metrics.active_overlaps += 1
            if selected.class_name == expected_class and active_iou >= iou_threshold:
                class_metrics.active_hits += 1
            elif selected.class_name == expected_class:
                failures.append(
                    f"{image_id}: selected wrong target (active IoU={active_iou:.3f})"
                )
            final_window = crop_window_for_bbox(
                image_width,
                image_height,
                selected.bbox,
                DEFAULT_ASPECT_RATIOS[selected.class_name],
                context_padding=context_padding,
            )
            if active_coverage_in_window(
                active_ground_truth, final_window, image_width, image_height
            ) >= 0.95:
                class_metrics.roi_crops_covered += 1
        if active_coverage_in_window(
            active_ground_truth, final_window, image_width, image_height
        ) >= 0.95:
            class_metrics.final_crops_covered += 1

    missing = sorted(set(annotation_by_id) - set(prediction_by_id))
    if missing and predictions.get("model") != "oracle-ground-truth":
        failures.extend(f"{image_id}: prediction row missing" for image_id in missing)

    total = ClassMetrics()
    for value in metrics.values():
        for field in asdict(total):
            setattr(total, field, getattr(total, field) + getattr(value, field))
    summary = {
        "model": predictions.get("model", "unknown"),
        "selectionMode": AUTO_ROI if auto_roi else "RECIPE_TARGET",
        "minimumConfidence": minimum_confidence,
        "iouThreshold": iou_threshold,
        "maximumGazeDistance": maximum_gaze_distance,
        "contextPadding": context_padding,
        "overall": total.summary(),
        "byClass": {name: value.summary() for name, value in metrics.items()},
        "latencyMs": {
            "mean": statistics.fmean(latencies) if latencies else None,
            "median": statistics.median(latencies) if latencies else None,
            "max": max(latencies) if latencies else None,
        },
    }
    return summary, failures


def main() -> int:
    parser = argparse.ArgumentParser(description="Evaluate crop detector predictions")
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--oracle", action="store_true")
    source.add_argument("--predictions", type=Path)
    parser.add_argument("--confidence", type=float, default=0.25)
    parser.add_argument(
        "--confidence-sweep",
        help="comma-separated thresholds; print a compact comparison table",
    )
    parser.add_argument("--iou", type=float, default=0.50)
    parser.add_argument("--max-gaze-distance", type=float)
    parser.add_argument("--context-padding", type=float, default=0.22)
    parser.add_argument(
        "--auto-roi",
        action="store_true",
        help="select the closest board or pan exactly like AUTO_ROI production mode",
    )
    parser.add_argument("--padding-sweep", help="comma-separated context padding values")
    parser.add_argument(
        "--distance-sweep",
        help="comma-separated maximum gaze distances; use 'none' for no gate",
    )
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()

    annotations = load_json(DATA_ROOT / "roi-annotations.json")
    predictions = (
        oracle_predictions(annotations)
        if args.oracle
        else load_json(args.predictions.resolve())
    )
    if args.padding_sweep:
        print("padding  roi-coverage  final-coverage  fallback")
        for value in args.padding_sweep.split(","):
            padding = float(value)
            sweep_summary, _ = evaluate(
                predictions,
                args.confidence,
                args.iou,
                args.max_gaze_distance,
                padding,
                args.auto_roi,
            )
            overall = sweep_summary["overall"]
            print(
                f"{padding:>7.2f}  {overall['roiCropCoverageRate']:>12.3f}  "
                f"{overall['finalCropCoverageRate']:>14.3f}  "
                f"{overall['fallbackRate']:>8.3f}"
            )
        return 0
    if args.distance_sweep:
        print("distance  active-overlap  roi-coverage  final-coverage  fallback")
        for value in args.distance_sweep.split(","):
            distance = None if value.strip().lower() == "none" else float(value)
            sweep_summary, _ = evaluate(
                predictions,
                args.confidence,
                args.iou,
                distance,
                args.context_padding,
                args.auto_roi,
            )
            overall = sweep_summary["overall"]
            label = "none" if distance is None else f"{distance:.2f}"
            print(
                f"{label:>8}  {overall['activeTargetOverlapRate']:>14.3f}  "
                f"{overall['roiCropCoverageRate']:>12.3f}  "
                f"{overall['finalCropCoverageRate']:>14.3f}  "
                f"{overall['fallbackRate']:>8.3f}"
            )
        return 0
    if args.confidence_sweep:
        print("conf  precision  recall  active-hit  fallback")
        for value in args.confidence_sweep.split(","):
            confidence = float(value)
            sweep_summary, _ = evaluate(
                predictions,
                confidence,
                args.iou,
                args.max_gaze_distance,
                args.context_padding,
                args.auto_roi,
            )
            overall = sweep_summary["overall"]
            print(
                f"{confidence:>4.2f}  {overall['precision']:>9.3f}  "
                f"{overall['recall']:>6.3f}  "
                f"{overall['activeTargetHitRate']:>10.3f}  "
                f"{overall['fallbackRate']:>8.3f}"
            )
        return 0
    summary, failures = evaluate(
        predictions,
        args.confidence,
        args.iou,
        args.max_gaze_distance,
        args.context_padding,
        args.auto_roi,
    )
    rendered = json.dumps(summary, ensure_ascii=False, indent=2)
    print(rendered)
    if failures:
        print("\n실패 사례:")
        for failure in failures:
            print(f"  - {failure}")
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(
            json.dumps(
                {"summary": summary, "failures": failures},
                ensure_ascii=False,
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
    return 0


if __name__ == "__main__":
    if sys.platform == "win32":
        try:
            sys.stdout.reconfigure(encoding="utf-8")
        except Exception:
            pass
    raise SystemExit(main())
