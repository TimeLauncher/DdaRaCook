"""
T2-1 / T2-2 · 이미지 전처리 (평가·정규화 공용)

`testdata/raw/` 원본은 **절대 건드리지 않습니다.** 여기 함수들은 항상 새 bytes 를
만들어 돌려주며, 파일을 덮어쓰지 않습니다. 규격이 바뀌어도 원본에서 재생성하면
되는 구조를 지키기 위해서입니다 (요청서 §개정 이력).

⚠️ 가장 중요한 규칙 하나:

    아무 변환도 요청하지 않으면 **파일 바이트를 그대로** 돌려준다.

Pillow 로 열었다 저장하기만 해도 JPEG 가 재인코딩되어 픽셀이 미세하게 달라집니다.
실서비스는 1번이 축소한 파일을 그대로 보내는데 평가만 재인코딩된 파일을 쓰면,
그 차이가 정확도 숫자에 섞여 들어가고 원인을 추적할 수 없게 됩니다.
"""
from __future__ import annotations

import base64
import io
import os
from typing import Optional

from PIL import Image, ImageCms

# 안경 원본은 Display P3 입니다 (2026-08-10 실측).
# ICC 가 없어도 카메라가 한 종류뿐이라 P3 로 간주하면 결정론적입니다.
ASSUME_P3_WHEN_NO_ICC = True

_srgb_profile = None


def _srgb():
    global _srgb_profile
    if _srgb_profile is None:
        _srgb_profile = ImageCms.createProfile("sRGB")
    return _srgb_profile


def _p3_like(im: Image.Image) -> bool:
    """이 이미지의 픽셀이 sRGB 가 아닌 색역 기준인가."""
    raw = im.info.get("icc_profile")
    if not raw:
        return ASSUME_P3_WHEN_NO_ICC
    try:
        desc = ImageCms.getProfileDescription(
            ImageCms.ImageCmsProfile(io.BytesIO(raw)))
    except Exception:
        return ASSUME_P3_WHEN_NO_ICC
    return "sRGB" not in desc or "P3" in desc


def to_srgb(im: Image.Image) -> Image.Image:
    """Display P3 → sRGB 변환.

    변환하지 않으면 모델이 채도를 약 13.5% 낮게 봅니다(실측, 편향 방향 일정).
    다만 그게 정확도에 실제로 영향을 주는지는 T2-3 에서 A/B 로 확인합니다.
    그래서 이 변환은 **항상 켜는 게 아니라 플래그로 켜고 끕니다.**
    """
    if im.mode != "RGB":
        im = im.convert("RGB")
    if not _p3_like(im):
        return im
    raw = im.info.get("icc_profile")
    src = (ImageCms.ImageCmsProfile(io.BytesIO(raw)) if raw
           else ImageCms.createProfile("sRGB"))  # ICC 없으면 변환 불가 → 그대로
    if raw is None:
        return im
    try:
        return ImageCms.profileToProfile(im, src, _srgb(), outputMode="RGB")
    except Exception:
        return im


def resize_long_edge(im: Image.Image, long_edge: int) -> Image.Image:
    """긴 변 기준 축소. **확대는 하지 않습니다** (요청서 §정규화 규격).

    비율을 유지하므로 짧은 변은 959~966 처럼 갈립니다. 정상입니다.
    """
    if max(im.size) <= long_edge:
        return im
    im = im.copy()
    im.thumbnail((long_edge, long_edge), Image.LANCZOS)
    return im


def drop_top(im: Image.Image, percent: int) -> Image.Image:
    """위쪽 일정 비율을 버린다 (제품 정규화 1단계, 2026-08-13 3인 협의).

    1인칭 안경 화각에서 상단은 벽·후드·걸어둔 조리도구뿐입니다.
    `testdata/raw` 16장 실측에서 판정 대상은 **전부** 세로 60% 아래에 있었고,
    크롭 후에도 잘린 장이 없었습니다.

    페이로드보다 중요한 효과는 **판정 잡음 제거**입니다. 도마 사진 상단의
    칼·주전자·밀폐용기가 IDENTIFY 판정을 방해하지 않게 됩니다.
    """
    if not 0 < percent < 100:
        return im
    w, h = im.size
    return im.crop((0, h * percent // 100, w, h))


def prepare(
    path: str,
    long_edge: Optional[int] = None,
    srgb: bool = False,
    quality: int = 80,
    crop_top: Optional[int] = None,
) -> bytes:
    """평가·전송용 JPEG bytes 를 만든다.

    아무 변환도 주지 않으면 **파일을 그대로 읽어 돌려준다**
    (위 모듈 docstring 의 규칙).

    제품과 동일한 전처리는 `crop_top=40, long_edge=1024` 입니다.
    """
    if long_edge is None and crop_top is None and not srgb:
        with open(path, "rb") as f:
            return f.read()

    im = Image.open(path)
    im.load()
    icc = im.info.get("icc_profile")

    if srgb:
        im = to_srgb(im)
        icc = None          # 변환했으므로 원본 프로파일은 더 이상 유효하지 않다
    elif im.mode != "RGB":
        im = im.convert("RGB")

    # 앱 파이프라인과 같은 순서. 크롭이 먼저여야 긴 변 기준이 달라진다
    # (3024x4032 → 크롭 → 3024x2419 → 긴 변이 가로로 바뀜 → 1024x819).
    if crop_top is not None:
        im = drop_top(im, crop_top)

    if long_edge is not None:
        im = resize_long_edge(im, long_edge)

    buf = io.BytesIO()
    # EXIF 는 싣지 않는다. 회전은 이미 픽셀에 반영돼 있고(Orientation=1),
    # GPS 같은 개인정보가 평가 산출물로 새어 나가지 않게 하기 위해서다.
    im.save(buf, format="JPEG", quality=quality,
            **({"icc_profile": icc} if icc else {}))
    return buf.getvalue()


def prepare_b64(path: str, **kw) -> str:
    return base64.standard_b64encode(prepare(path, **kw)).decode()


def exists(path: str) -> bool:
    return bool(path) and os.path.exists(path)
