# 조리 ROI 모델

`yoloe-26n-cook-roi.onnx`는 YOLOE-26n에 아래 text prompt를 고정한 서버 CPU 추론용 모델입니다.

- 도마: `cutting board`, `chopping board`
- 팬: `frying pan`, `skillet`, `cooking pan`, `wok`
- 입력: RGB letterbox `640×640`
- SHA-256: `b2cd6f4c482b78d21f10d36b94fc959af6bdb09eaa7221bade56a9e3948924e2`

개발 환경에서 다시 export할 때만 Ultralytics/PyTorch가 필요합니다. 운영 서버는
`onnxruntime`만 사용합니다.

```bash
python export_crop_yoloe_onnx.py --model yoloe-26n-seg.pt \
  --output models/yoloe-26n-cook-roi.onnx
```

원본 가중치와 생성 모델의 사용·배포에는 Ultralytics 라이선스 조건을 확인해야 합니다.
