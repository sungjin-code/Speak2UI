# Evaluation

## Setup

- OS: Ubuntu 22.04.5 LTS
- Python: 3.13.14

```bash
pip install -r requirements.txt
cp .env.example .env   # then fill in your keys
```

`.env` keys:

- `OPENAI_API_KEY` — required for `test_openai.py`.
- `HF_TOKEN` — required for `test_llm.py` to download gated models (e.g. Llama-3.1/3.2, Gemma-3n).
Accept each model's license on its HuggingFace page first.

Shared logic (prompt, inference loop, evaluation) lives in `common.py`; each script only implements its model-specific `parse_command`.

## Run

### Hosted models (OpenAI)

`test_openai.py` evaluates one model on one dataset per run. Edit the test parameters at the bottom of the file (`lang` = `en`/`ko`, `model`, `is_hard`), then:

```bash
python test_openai.py
```

Supported `model` keys: `gpt-4o-mini`, `gpt-5-mini`.

### Local models (Qwen3-1.7B/4B/8B, Llama-3.1-8B & 3.2-3B/1B-Instruct, SmolLM2-1.7B, Gemma-3n-E2B)

`test_llm.py` runs **all configured models** over **all four datasets** (`eval` and `eval_hard`, `en` and `ko`) in a single execution.

```bash
python test_llm.py
```

Weights are downloaded on first run (reusing the local HuggingFace cache if already present). A model whose result files already exist is skipped, so re-running only evaluates the missing models.

### Output

Each run writes, per model, into a folder named after the model key (e.g. `gpt-4o-mini/`, `qwen3-8b/`, `llama-3.1-8b/`):

- `result{_hard}_{en|ko}.csv` — per-query predictions (`ID, intent, value, time`).
- `result{_hard}_{en|ko}.json` — metrics (`Exact_Match`, `Action_F1`,
  `Target_F1`, `Average_Time`, `Max_Time`).
