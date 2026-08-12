"""
T2-1 · `raw/` → `images/` 정규화본 생성

    python prep_images.py                 # testdata/manifest.json 대로 생성
    python prep_images.py --long-edge 1024
    python prep_images.py --check         # 만들지 않고 계획만 출력

**`raw/` 는 절대 건드리지 않습니다.** 여기서 하는 일은 `imageprep.prepare()` 로
새 bytes 를 만들어 `images/` 에 쓰는 것뿐입니다. 규격이 바뀌면 이 스크립트를
다시 돌리면 끝입니다 — 재촬영이 아닙니다 (`raw/README.md`).

## 왜 manifest 가 따로 있나

`eval.py` 는 이미지를 `images/{pairId}_current.jpg` 로만 찾습니다. 그래서 원본을
그 이름으로 복사하고 나면 **"이 파일이 원래 어느 촬영본이었는지"가 사라집니다.**
같은 원본을 여러 pairId 가 재사용할 때(같은 사진에 다른 질문) 특히 문제가 됩니다.

`labels.json` 에 넣지 않고 별도 파일로 둔 이유는 `eval.py` 가 라벨 스키마를
엄격 검증하기 때문입니다 — 모르는 필드는 오타로 간주되어 즉시 거부됩니다.

## manifest.json 형식

    [{"pairId": "onion_chop_p01", "current": "20260811_082027_a20040ca.jpg",
      "start": "20260811_082019_8e06ac33.jpg"}]

`start` 는 생략 가능합니다. 생략하면 `_start.jpg` 를 만들지 않습니다 —
라벨의 `hasStartImage=false` 와 짝이 맞아야 합니다 (`eval.py:167` 가 검사).
"""
from __future__ import annotations

import argparse
import json
import os
import sys

import imageprep

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_MANIFEST = os.path.join(HERE, "testdata", "manifest.json")


def main() -> int:
    p = argparse.ArgumentParser(description="raw/ → images/ 정규화본 생성")
    p.add_argument("--manifest", default=DEFAULT_MANIFEST)
    p.add_argument("--raw", default=None, help="기본값: manifest 옆의 raw/")
    p.add_argument("--images", default=None, help="기본값: manifest 옆의 images/")
    p.add_argument("--long-edge", type=int, default=1280,
                   help="긴 변 축소 기준 (기본 1280 · 요청서 규격)")
    p.add_argument("--srgb", action="store_true",
                   help="P3→sRGB 를 산출물에 굽는다. 기본은 끔 — "
                        "eval.py --srgb 로 A/B 하려면 켜지 마세요")
    p.add_argument("--quality", type=int, default=80)
    p.add_argument("--check", action="store_true", help="쓰지 않고 계획만 출력")
    args = p.parse_args()

    base = os.path.dirname(os.path.abspath(args.manifest))
    raw_dir = args.raw or os.path.join(base, "raw")
    img_dir = args.images or os.path.join(base, "images")

    with open(args.manifest, encoding="utf-8") as f:
        rows = json.load(f)

    # 먼저 전부 검증한다. 절반만 만들어두고 실패하면 images/ 가 어정쩡해진다.
    errors, plan = [], []
    seen = set()
    for i, r in enumerate(rows):
        if "_" in r and "pairId" not in r:
            continue                      # 구분선 주석 (JSON 에 주석이 없어서)
        pid = r.get("pairId")
        if not pid:
            errors.append(f"[{i}] pairId 없음")
            continue
        if pid in seen:
            errors.append(f"[{i}] {pid}: pairId 중복")
        seen.add(pid)
        for role in ("current", "start"):
            src = r.get(role)
            if not src:
                if role == "current":
                    errors.append(f"[{i}] {pid}: current 없음")
                continue
            src_path = os.path.join(raw_dir, src)
            if not os.path.exists(src_path):
                errors.append(f"[{i}] {pid}: raw 파일 없음 — {src}")
                continue
            plan.append((pid, role, src_path,
                         os.path.join(img_dir, f"{pid}_{role}.jpg")))

    if errors:
        print(f"❌ manifest 검증 실패 — {len(errors)}건\n")
        for e in errors:
            print("   " + e)
        return 1

    print(f"manifest : {args.manifest}  ({len(rows)}쌍 · 파일 {len(plan)}개)")
    print(f"raw      : {raw_dir}   (읽기 전용)")
    print(f"images   : {img_dir}")
    print(f"규격     : 긴 변 {args.long_edge}px · quality {args.quality}"
          f" · sRGB 변환 {'ON (굽는다)' if args.srgb else 'OFF (P3 보존)'}")
    print("-" * 72)

    if args.check:
        for pid, role, src, dst in plan:
            print(f"  {pid}_{role}.jpg  ←  {os.path.basename(src)}")
        return 0

    os.makedirs(img_dir, exist_ok=True)
    total = 0
    for pid, role, src, dst in plan:
        data = imageprep.prepare(src, long_edge=args.long_edge,
                                 srgb=args.srgb, quality=args.quality)
        with open(dst, "wb") as f:
            f.write(data)
        total += len(data)
        print(f"  ✅ {pid}_{role}.jpg  {len(data)//1024:>4} KB"
              f"  ←  {os.path.basename(src)}")

    print("-" * 72)
    print(f"  {len(plan)}개 생성 · 합계 {total//1024} KB"
          f" · 전송 시 base64 약 {int(total*1.34)//1024} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
