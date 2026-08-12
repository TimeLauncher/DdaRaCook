"""
이미지 규격 검사 도구 (T2-1 Part 3)

    python inspect_image.py a.jpg b.jpg ...
    python inspect_image.py 폴더경로/

1번의 축소본과 내 축소본이 같은지 대조하는 용도입니다.
메신저(디스코드/카톡)를 거친 파일은 EXIF·ICC가 이미 지워져 있어
검사가 무의미하니 Gmail(파일 첨부)·USB·드라이브로 받은 파일로 확인하세요.

2026-08-10 개정 — 규격 변경 반영:
  · EXIF 제거는 더 이상 요구하지 않습니다 (서버가 정리)
  · 대신 Orientation이 1인지, ICC가 보존됐는지를 봅니다
  · 안경 원본은 Display P3이며, sRGB 변환은 서버가 담당합니다
"""
import io
import os
import sys
from collections import Counter

from PIL import Image, ImageCms

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
    except Exception:
        pass

TARGET_LONG_EDGE = 1280   # 규격 확정 후 조정
EXPECTED_MODEL = "Ray-Ban Meta Smart Glasses 2"

TAG_ORIENTATION, TAG_MAKE, TAG_MODEL = 274, 271, 272


def icc_label(im):
    """ICC 프로파일을 짧은 이름으로. 없으면 None."""
    raw = im.info.get("icc_profile")
    if not raw:
        return None
    try:
        desc = ImageCms.getProfileDescription(
            ImageCms.ImageCmsProfile(io.BytesIO(raw))).strip()
    except Exception:
        return "?"
    if "P3" in desc:
        return "P3"
    if "sRGB" in desc:
        return "sRGB"
    return desc[:8]


def inspect(path):
    im = Image.open(path)
    exif = im.getexif()
    kb = os.path.getsize(path) / 1024
    make, model = exif.get(TAG_MAKE), exif.get(TAG_MODEL)
    return {
        "name": os.path.basename(path),
        "size": f"{im.size[0]}x{im.size[1]}",
        "long_edge": max(im.size),
        "format": im.format,
        "mode": im.mode,
        "orientation": exif.get(TAG_ORIENTATION),
        "icc": icc_label(im),
        "device": f"{make} {model}".strip() if (make or model) else None,
        "kb": round(kb),
        "b64_kb": round(kb * 1.34),
        "ratio": min(im.size) / max(im.size),
    }


EXTS = (".jpg", ".jpeg", ".png", ".webp")


def expand(paths):
    """파일과 폴더를 모두 받는다. 폴더면 안의 이미지를 모두 훑는다."""
    out = []
    for p in paths:
        if os.path.isdir(p):
            for root, _, files in os.walk(p):
                for f in sorted(files):
                    if f.lower().endswith(EXTS):
                        out.append(os.path.join(root, f))
        elif os.path.exists(p):
            out.append(p)
        else:
            print(f"❌ 파일 없음: {p}")
    return out


def check(r):
    """규격 위반 목록. 축소 전 원본은 위반이 아니라 정보로 처리한다."""
    issues, notes = [], []

    if r["format"] != "JPEG":
        issues.append(f"포맷이 {r['format']}")
    if r["mode"] != "RGB":
        issues.append(f"색공간이 {r['mode']}")

    # 회전 — 축소 과정에서 새로 붙었는지가 유일한 관심사
    if r["orientation"] not in (None, 1):
        issues.append(f"⚠️ Orientation={r['orientation']} — 축소 중 회전 태그가 붙었습니다")

    # 긴 변 — 원본이면 위반이 아님
    if r["long_edge"] > TARGET_LONG_EDGE * 1.5:
        notes.append(f"긴 변 {r['long_edge']} — 축소 전 원본으로 보입니다")
    elif r["long_edge"] != TARGET_LONG_EDGE:
        issues.append(f"긴 변 {r['long_edge']} (기대 {TARGET_LONG_EDGE})")

    # ICC — 안경 원본은 P3. sRGB면 누군가 변환한 것
    if r["icc"] is None:
        notes.append("ICC 없음 — 전달 과정에서 지워졌을 수 있습니다")
    elif r["icc"] == "sRGB":
        issues.append("ICC가 sRGB — 앱에서 변환했습니다. 변환은 서버 담당입니다")

    return issues, notes


def summarize(rows):
    """기기·해상도·비율을 훑어 이상치를 드러낸다."""
    print()
    print("=== 촬영 기기 ===")
    for dev, n in Counter(r["device"] or "(EXIF 없음)" for r in rows).most_common():
        ok = "✅" if EXPECTED_MODEL in dev else ("❔" if "EXIF" in dev else "⚠️")
        print(f"  {ok} {n:>3}장   {dev}")
    if any(r["device"] and EXPECTED_MODEL not in r["device"] for r in rows):
        print(f"  ⚠️ 안경({EXPECTED_MODEL}) 외의 기기가 섞여 있습니다 — 평가셋에서 빼세요")

    print()
    print("=== 해상도 분포 ===")
    for size, n in Counter(r["size"] for r in rows).most_common():
        print(f"  {n:>3}장   {size}")

    ratios = [(r["name"], r["ratio"]) for r in rows]
    med = sorted(x[1] for x in ratios)[len(ratios) // 2]
    print()
    print(f"=== 가로/세로 비율 (중앙값 {med:.4f}) ===")
    outliers = [(n, x) for n, x in ratios if abs(x - med) > 0.01]
    if outliers:
        print("  ⚠️ 비율이 다른 파일 — 다른 기기/모드일 수 있음:")
        for n, x in outliers:
            print(f"     {x:.4f}  {n[:50]}")
    else:
        print("  ✅ 모두 동일 계열 (편차 0.01 이내)")

    print()
    print(f"=== 긴 변 {TARGET_LONG_EDGE}px 축소 시 예상 크기 ===")
    seen = set()
    for r in rows:
        w, h = map(int, r["size"].split("x"))
        sc = TARGET_LONG_EDGE / max(w, h)
        out = f"{round(w*sc)}x{round(h*sc)}"
        if out not in seen:
            seen.add(out)
            print(f"  {r['size']:>12}  ->  {out}")
    if len(seen) > 1:
        print("  ℹ️ 축소 후에도 크기가 갈립니다(비율 차이). VLM 판정에는 지장 없습니다.")


def main(paths):
    if not paths:
        sys.exit(__doc__)

    rows = [inspect(p) for p in expand(paths)]
    if not rows:
        return

    print(f"{'파일':<28}{'해상도':<14}{'포맷':<6}{'모드':<5}"
          f"{'회전':>5}{'ICC':>6}{'KB':>7}{'전송KB':>8}")
    print("-" * 80)
    for r in rows:
        print(f"{r['name'][:27]:<28}{r['size']:<14}{r['format']:<6}{r['mode']:<5}"
              f"{str(r['orientation'] or '-'):>5}{str(r['icc'] or '-'):>6}"
              f"{r['kb']:>7}{r['b64_kb']:>8}")

    print("\n=== 규격 점검 ===")
    for r in rows:
        issues, notes = check(r)
        print(f"  {'✅' if not issues else '❌'} {r['name'][:40]}")
        for msg in issues:
            print(f"       ❌ {msg}")
        for msg in notes:
            print(f"       ℹ️ {msg}")

    if len(rows) > 1:
        print("\n=== 파일 간 일치 여부 (축소 단계 동일성) ===")
        for key, label in [("long_edge", "긴 변"), ("format", "포맷"),
                           ("mode", "색공간"), ("icc", "ICC")]:
            vals = {str(r[key]) for r in rows}
            print(f"  {'✅' if len(vals) == 1 else '❌'} {label:<8} {sorted(vals)}")
        print("\n  ⚠️ 메신저를 거친 파일이면 이 대조가 무의미합니다 (EXIF·ICC가 지워짐).")

        summarize(rows)


if __name__ == "__main__":
    main(sys.argv[1:])
