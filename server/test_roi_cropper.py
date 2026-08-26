import base64
import io

from PIL import Image

from roi_crop import Detection
from roi_cropper import _agnostic_nms, prepare_judge_image


def jpeg_b64(width=1366, height=1821):
    output = io.BytesIO()
    Image.new("RGB", (width, height), (120, 80, 40)).save(output, "JPEG", quality=80)
    return base64.b64encode(output.getvalue()).decode("ascii")


def dimensions(image_b64):
    with Image.open(io.BytesIO(base64.b64decode(image_b64))) as image:
        return image.size


class FakeDetector:
    def __init__(self, detections):
        self.detections = detections

    def detect(self, _image):
        return self.detections


def test_old_client_is_byte_for_byte_passthrough():
    source = jpeg_b64()
    result, decision = prepare_judge_image(source, None)
    assert result == source
    assert decision.mode == "CLIENT_PREPARED"


def test_manual_mode_is_byte_for_byte_passthrough():
    source = jpeg_b64()
    result, decision = prepare_judge_image(source, "NO_CROP")
    assert result == source
    assert decision.mode == "NO_CROP"


def test_legacy_target_keeps_bottom_60_and_long_edge_768():
    result, decision = prepare_judge_image(jpeg_b64(), "LEGACY_BOTTOM_60")
    assert dimensions(result) == (768, 614)
    assert decision.mode == "LEGACY_BOTTOM_60"


def test_board_detection_produces_standard_4_by_3_crop():
    detector = FakeDetector([
        Detection("CUTTING_BOARD_ROI", 0.8, (0.30, 0.65, 0.48, 0.25))
    ])
    result, decision = prepare_judge_image(
        jpeg_b64(), "CUTTING_BOARD_ROI", detector=detector
    )
    assert dimensions(result) == (768, 576)
    assert decision.mode == "YOLO_ROI"


def test_pan_detection_produces_square_crop():
    detector = FakeDetector([
        Detection("PAN_COOKING_ROI", 0.8, (0.38, 0.54, 0.41, 0.31))
    ])
    result, decision = prepare_judge_image(
        jpeg_b64(), "PAN_COOKING_ROI", detector=detector
    )
    assert dimensions(result) == (768, 768)
    assert decision.mode == "YOLO_ROI"


def test_auto_roi_uses_selected_detection_class_for_output_ratio():
    detector = FakeDetector([
        Detection("CUTTING_BOARD_ROI", 0.9, (0.05, 0.20, 0.20, 0.20)),
        Detection("PAN_COOKING_ROI", 0.7, (0.38, 0.54, 0.41, 0.31)),
    ])
    result, decision = prepare_judge_image(
        jpeg_b64(), "AUTO_ROI", detector=detector
    )
    assert dimensions(result) == (768, 768)
    assert decision.mode == "YOLO_ROI"
    assert decision.target == "AUTO_ROI"


def test_far_detection_uses_safe_fallback():
    detector = FakeDetector([
        Detection("PAN_COOKING_ROI", 0.8, (0.78, 0.55, 0.20, 0.22))
    ])
    result, decision = prepare_judge_image(
        jpeg_b64(), "PAN_COOKING_ROI", detector=detector
    )
    assert dimensions(result) == (768, 614)
    assert decision.mode == "FALLBACK_BOTTOM_60"


def test_detector_exception_uses_safe_fallback():
    class BrokenDetector:
        def detect(self, _image):
            raise RuntimeError("broken")

    result, decision = prepare_judge_image(
        jpeg_b64(), "PAN_COOKING_ROI", detector=BrokenDetector()
    )
    assert dimensions(result) == (768, 614)
    assert decision.mode == "FALLBACK_BOTTOM_60"


def test_agnostic_nms_merges_overlapping_synonym_boxes():
    detections = [
        Detection("PAN_COOKING_ROI", 0.8, (0.3, 0.5, 0.4, 0.3)),
        Detection("PAN_COOKING_ROI", 0.7, (0.31, 0.51, 0.4, 0.3)),
        Detection("CUTTING_BOARD_ROI", 0.6, (0.30, 0.50, 0.4, 0.3)),
    ]
    kept = _agnostic_nms(detections, iou_threshold=0.70)
    assert kept == [detections[0]]


def main() -> int:
    tests = [
        test_old_client_is_byte_for_byte_passthrough,
        test_manual_mode_is_byte_for_byte_passthrough,
        test_legacy_target_keeps_bottom_60_and_long_edge_768,
        test_board_detection_produces_standard_4_by_3_crop,
        test_pan_detection_produces_square_crop,
        test_auto_roi_uses_selected_detection_class_for_output_ratio,
        test_far_detection_uses_safe_fallback,
        test_detector_exception_uses_safe_fallback,
        test_agnostic_nms_merges_overlapping_synonym_boxes,
    ]
    for test in tests:
        test()
    print(f"roi_cropper_tests_passed={len(tests)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
