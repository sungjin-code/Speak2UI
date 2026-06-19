# Evaluation

## Dataset

- `eval/*`: Test set from [Voicify](https://github.com/vuminhduc796/Voicify/blob/main/parser/datasets/android_eval/test.txt).
- `eval_hard/*`: An additional test set created for further evaluation.

## Setup

```bash
pip install -r requirements.txt
cp .env.example .env   # then fill in your keys
```

`.env` keys:

- `OPENAI_API_KEY` — required for `test_openai.py`.
- `HF_TOKEN` — required for `test_llm.py` to download gated models (e.g. Llama-3.1).
  Accept each model's license on its HuggingFace page first.

Shared logic (prompt, inference loop, evaluation) lives in `common.py`; each
script only implements its model-specific `parse_command`.

## Run

### Hosted models (OpenAI)

`test_openai.py` evaluates one model on one dataset per run. Edit the test
parameters at the bottom of the file (`lang` = `en`/`ko`, `model`, `is_hard`),
then:

```bash
python test_openai.py
```

Supported `model` keys: `gpt-4o-mini`, `gpt-5-mini`.

### Local models (Qwen3-8B, Llama-3.1-8B-Instruct)

`test_llm.py` loads each model once and runs **all four datasets**
(`eval` and `eval_hard`, `en` and `ko`) in a single execution. Requires one
CUDA GPU; inference runs in BF16.

```bash
python test_llm.py
```

### Output

Each run writes, per model, into a folder named after the model key
(e.g. `gpt-4o-mini/`, `qwen3-8b/`, `llama-3.1-8b/`):

- `result{_hard}_{en|ko}.csv` — per-query predictions (`ID, intent, value, time`).
- `result{_hard}_{en|ko}.json` — metrics (`Exact_Match`, `Action_F1`,
  `Target_F1`, `Average_Time`, `Max_Time`).

## Result

### 1. Voicify vs Speak2UI

Result on `eval/test_en` dataset.

| Model | EM Accuracy (%) | Target F1 (%) | Action F1 (%) | Inference Time (s) |
|:---:|:---:|:---:|:---:|:---:|
| Voicify | 91.09 | 93.05 | 97.03 | - |
| Ours (GPT-4o-mini) | **100** | **96.73** | **100** | **1.049** |
| Ours (GPT-5-mini) | 97.03 | 95.13 | 97.88 | 2.757 |

### 2. Additional Evaluation

Base Model: `GPT-4o-mini`

| Dataset | EM Accuracy (%) | Target F1 (%) | Action F1 (%) | Inference Time (s) |
|:---:|:---:|:---:|:---:|:---:|
| eval/test_en | 100 | 96.73 | 100 | 1.049 |
| eval/test_ko | 96.04 | 96.76 | 97.48 | 0.771 |
| eval_hard/test_en | 95.40 | 100 | 95.69 | 1.124 |
| eval_hard/test_ko | 97.70 | 100 | 97.76 | 0.779 |

## Translation Rules

- Tap: 탭해
- Press: 눌러
- Click: 클릭해
- Type: 타이핑해
- Enter: 입력해
- Go (to): 이동해
- Open: 열어
- Swipe: 밀어
- Scroll: 스크롤해
- Start: 시작해
- Check off: 선택 해제해
- Launch: 실행해
- Select: 선택해
- Double: 두 번
- Long: 길게
