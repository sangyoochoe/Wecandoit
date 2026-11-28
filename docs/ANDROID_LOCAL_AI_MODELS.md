# Android 로컬 AI 모델 준비 안내

현재 Android 앱의 로컬 모델 어댑터는 MediaPipe LLM Inference용 .task 번들만 읽습니다. 아래 파일은 Google AI Edge Gallery의 공식 모델 허용 목록에 등록된 Android용 모델입니다. GGUF 또는 .litertlm 파일은 이 어댑터에 넣어도 실행되지 않습니다.

## 우선 추천 다운로드

| 우선순위 | 모델 | 파일 크기(공식 목록 기준) | 예상 피크 메모리 | 용도 |
|---|---|---:|---:|---|
| 1 | Gemma 3 1B IT, 4-bit | 약 529 MiB | 약 2 GiB | 첫 로컬 모델 테스트, 가벼운 한국어 질의 |
| 2 | Qwen 2.5 1.5B Instruct, 8-bit | 약 1.51 GiB | 약 2.5 GiB | 다국어 지시 수행, 더 큰 모델 실험 |

### 1. Gemma 3 1B IT (먼저 다운로드 권장)

- 모델 저장소: https://huggingface.co/litert-community/Gemma3-1B-IT
- 공식 파일: Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task
- 파일 페이지: https://huggingface.co/litert-community/Gemma3-1B-IT/blob/main/Gemma3-1B-IT_multi-prefill-seq_q4_ekv2048.task
- Hugging Face 로그인 및 이용 약관 동의가 필요할 수 있습니다.

### 2. Qwen 2.5 1.5B Instruct (선택 다운로드)

- 모델 저장소: https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct
- 공식 파일: Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task
- 파일 페이지: https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/blob/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task

## 설치 시 주의

1. 휴대폰 여유 저장 공간을 모델 파일 크기의 최소 2배 정도 확보하세요. 앱 내부 저장소로 복사할 공간도 필요합니다.
2. 메모리 사용량은 파일 크기보다 큽니다. Gemma 3 1B는 피크 약 2 GiB, Qwen 2.5 1.5B는 약 2.5 GiB를 기준으로 기기 여건을 확인하세요.
3. 모델 파일은 APK에 포함하지 말고 앱의 로컬 모델 가져오기 메뉴에서 선택하세요.
4. 이 문서의 다운로드는 사전 준비용입니다. 현재 앱의 MediaPipe 어댑터와 일치하는 .task 파일만 가져오세요.
5. 모델을 기기에 내려받았다고 해서 모든 기기에서 동일한 속도나 안정성이 보장되지는 않습니다.

## 다른 모델 형식

Google의 LiteRT-LM은 별도 런타임이며 .litertlm 파일을 사용합니다. Gemma, Qwen, Llama, Phi 계열 지원 여부와 최적화된 파일은 모델/기기별로 다릅니다. 현재 앱의 MediaPipe .task 어댑터와 혼용하지 마세요. LiteRT-LM으로 전환하려면 Gradle 의존성, Kotlin API, 백그라운드 엔진 수명주기, 다운로드 진행률/검증, 기기별 테스트를 별도로 구현해야 합니다.

## 공식 근거

- Google AI Edge Gallery 모델 허용 목록: https://github.com/google-ai-edge/gallery/blob/main/model_allowlist.json
- Gemma 3 1B IT Android 모델 카드: https://huggingface.co/litert-community/Gemma3-1B-IT
- Google Android LiteRT-LM 안내: https://developer.android.com/ai/custom
