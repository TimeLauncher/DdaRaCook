import base64
import io

from PIL import Image

from roi_crop import Detection
from roi_cropper import ROI_OUTPUT_LONG_EDGE, _agnostic_nms, prepare_judge_image

# 출력 해상도는 환경변수로 조절한다(roi_cropper.ROI_OUTPUT_LONG_EDGE).
# 테스트는 특정 픽셀값이 아니라 **긴 변과 비율**을 검증한다. 값을 하드코딩하면
# 해상도를 조정할 때마다 무관한 테스트가 깨져 진짜 회귀를 가린다.
EDGE = ROI_OUTPUT_LONG_EDGE


def jpeg_b64(width=1366, height=1821):
    output = io.BytesIO()
    Image.new("RGB", (width, height), (120, 80, 40)).save(output, "JPEG", quality=80)
    return base64.b64encode(output.getvalue()).decode("ascii")


def dimensions(image_b64):
    with Image.open(io.BytesIO(base64.b64decode(image_b64))) as image:
        return image.size


def assert_shape(image_b64, ratio, label):
    """긴 변이 설정값과 같고, 가로:세로 비율이 기대와 맞는지 본다."""
    width, height = dimensions(image_b64)
    assert width == EDGE, f"{label}: 긴 변이 {EDGE} 여야 하는데 {width}"
    expected = EDGE / ratio
    assert abs(height - expected) <= 1, (
        f"{label}: 세로가 {expected:.0f}±1 이어야 하는데 {height}")


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


def test_legacy_target_keeps_bottom_60_and_configured_long_edge():
    result, decision = prepare_judge_image(jpeg_b64(), "LEGACY_BOTTOM_60")
    assert_shape(result, 1366 / 1092.6, "LEGACY_BOTTOM_60")
    assert decision.mode == "LEGACY_BOTTOM_60"


def test_board_detection_produces_standard_4_by_3_crop():
    detector = FakeDetector([
        Detection("CUTTING_BOARD_ROI", 0.8, (0.30, 0.65, 0.48, 0.25))
    ])
    result, decision = prepare_judge_image(
        jpeg_b64(), "CUTTING_BOARD_ROI", detector=detector
    )
    assert_shape(result, 4 / 3, "CUTTING_BOARD_ROI")
    assert decision.mode == "YOLO_ROI"


def test_pan_detection_produces_square_crop():
    detector = FakeDetector([
        Detection("PAN_COOKING_ROI", 0.8, (0.38, 0.54, 0.41, 0.31))
    ])
    result, decision = prepare_judge_image(
        jpeg_b64(), "PAN_COOKING_ROI", detector=detector
    )
    assert_shape(result, 1.0, "PAN_COOKING_ROI")
    assert decision.mode == "YOLO_ROI"


def test_auto_roi_uses_selected_detection_class_for_output_ratio():
    detector = FakeDetector([
        Detection("CUTTING_BOARD_ROI", 0.9, (0.05, 0.20, 0.20, 0.20)),
        Detection("PAN_COOKING_ROI", 0.7, (0.38, 0.54, 0.41, 0.31)),
    ])
    result, decision = prepare_judge_image(
        jpeg_b64(), "AUTO_ROI", detector=detector
    )
    assert_shape(result, 1.0, "AUTO_ROI")
    assert decision.mode == "YOLO_ROI"
    assert decision.target == "AUTO_ROI"


def test_far_detection_uses_safe_fallback():
    detector = FakeDetector([
        Detection("PAN_COOKING_ROI", 0.8, (0.78, 0.55, 0.20, 0.22))
    ])
    result, decision = prepare_judge_image(
        jpeg_b64(), "PAN_COOKING_ROI", detector=detector
    )
    assert_shape(result, 1366 / 1092.6, "FALLBACK_BOTTOM_60")
    assert decision.mode == "FALLBACK_BOTTOM_60"


def test_detector_exception_uses_safe_fallback():
    class BrokenDetector:
        def detect(self, _image):
            raise RuntimeError("broken")

    result, decision = prepare_judge_image(
        jpeg_b64(), "PAN_COOKING_ROI", detector=BrokenDetector()
    )
    assert_shape(result, 1366 / 1092.6, "FALLBACK_BOTTOM_60")
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
        test_legacy_target_keeps_bottom_60_and_configured_long_edge,
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
