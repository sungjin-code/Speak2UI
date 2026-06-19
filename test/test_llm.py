import os
import json
import re

import torch
from dotenv import load_dotenv
from huggingface_hub import snapshot_download
from transformers import (
    AutoModelForCausalLM,
    AutoModelForImageTextToText,
    AutoTokenizer,
)

from common import system_prompt, get_prompt, run_inference, evaluate

# NOTE: Some models are gated on HuggingFace (e.g. Llama-3.1/3.2, Gemma-3n).
# Accept the license on each model page, then put your token in a `.env` file:
#   HF_TOKEN=hf_xxx
# `transformers` reads HF_TOKEN automatically once load_dotenv() loads it.
load_dotenv()


def download_model(hf_id: str) -> str:
    """Ensure model weights are available locally.

    `snapshot_download` reuses already-downloaded files from the HuggingFace
    cache and fetches only what is missing, then returns the local path.
    """
    print(f"Downloading / locating weights for {hf_id}...")
    return snapshot_download(repo_id=hf_id)


def load_model(local_path: str, key: str):
    tokenizer = AutoTokenizer.from_pretrained(local_path, local_files_only=True)
    # Gemma 3n is multimodal and needs the image-text-to-text class even for
    # text-only generation; the others are plain causal LMs.
    model_cls = (
        AutoModelForImageTextToText if "gemma-3n" in key else AutoModelForCausalLM
    )
    model = model_cls.from_pretrained(
        local_path,
        dtype=torch.bfloat16,
        device_map="cuda",
        local_files_only=True,
    )
    model.eval()
    return model, tokenizer


def extract_json(text: str) -> dict:
    """Parse a JSON object from raw model output.

    Local models do not honor a `response_format` constraint, so the generated
    text may contain markdown fences, leftover reasoning, or trailing prose.
    """
    # Drop any leftover reasoning blocks (e.g. Qwen3 <think>...</think>).
    text = re.sub(r"<think>.*?</think>", "", text, flags=re.DOTALL)
    # Strip markdown code fences.
    text = re.sub(r"```(?:json)?", "", text)

    # Extract the first balanced {...} substring.
    start = text.find("{")
    if start != -1:
        depth = 0
        for i in range(start, len(text)):
            if text[i] == "{":
                depth += 1
            elif text[i] == "}":
                depth -= 1
                if depth == 0:
                    return json.loads(text[start : i + 1])

    raise ValueError(f"No JSON object found in output: {text!r}")


def parse_command(
    user_input: str,
    clickables: str,
    apps: str,
    model,
    tokenizer,
    is_qwen: bool,
) -> dict:
    prompt_text = get_prompt(user_input, clickables, apps)

    messages = [
        {"role": "system", "content": system_prompt},
        {"role": "user", "content": prompt_text},
    ]

    template_kwargs = {"tokenize": False, "add_generation_prompt": True}
    if is_qwen:
        # Disable Qwen3 thinking mode for clean JSON output.
        template_kwargs["enable_thinking"] = False

    prompt = tokenizer.apply_chat_template(messages, **template_kwargs)
    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)

    try:
        with torch.no_grad():
            output = model.generate(
                **inputs,
                do_sample=False,  # greedy ~ temperature 0
                max_new_tokens=512,
                pad_token_id=tokenizer.eos_token_id,
            )
        generated = output[0][inputs["input_ids"].shape[1] :]
        decoded = tokenizer.decode(generated, skip_special_tokens=True)
        return extract_json(decoded)
    except Exception as e:
        return {"intent": "ERROR", "value": [], "error": str(e)}


if __name__ == "__main__":
    models = {
        "qwen3-1.7b": "Qwen/Qwen3-1.7B",
        "qwen3-4b": "Qwen/Qwen3-4B",
        "qwen3-8b": "Qwen/Qwen3-8B",
        "llama-3.1-8b": "meta-llama/Llama-3.1-8B-Instruct",
        "llama-3.2-3b": "meta-llama/Llama-3.2-3B-Instruct",
        "llama-3.2-1b": "meta-llama/Llama-3.2-1B-Instruct",
        "smollm2-1.7b": "HuggingFaceTB/SmolLM2-1.7B-Instruct",
        "gemma-3n-e2b": "google/gemma-3n-E2B-it",
    }

    # (dirname, lang, encoding, suffix)
    datasets = [
        ("eval", "en", "utf-8", ""),
        ("eval", "ko", "utf-8-sig", ""),
        ("eval_hard", "en", "utf-8", "_hard"),
        ("eval_hard", "ko", "utf-8-sig", "_hard"),
    ]

    def is_done(key):
        # A model is complete when every dataset's result JSON already exists.
        return all(
            os.path.exists(f"{key}/result{suffix}_{lang}.json")
            for _, lang, _, suffix in datasets
        )

    # Skip models that already have complete local results.
    pending = {}
    for key, hf_id in models.items():
        if is_done(key):
            print(f"Skipping {key}: results already exist.")
        else:
            pending[key] = hf_id

    # Download all weights first (reusing local cache), then run experiments.
    # Models that fail to download are logged and skipped.
    local_paths = {}
    for key, hf_id in pending.items():
        try:
            local_paths[key] = download_model(hf_id)
        except Exception as e:
            print(f"[WARN] Skipping {key}: download failed ({hf_id}): {e}")

    for key, local_path in local_paths.items():
        print(f"\n===== Loading {key} =====")
        os.makedirs(key, exist_ok=True)
        model, tokenizer = load_model(local_path, key)
        is_qwen = "qwen" in key

        for d, lang, enc, suffix in datasets:
            run_inference(
                file_path=f"{d}/test_{lang}.csv",
                save_path=f"{key}/result{suffix}_{lang}.csv",
                parse_fn=lambda u, c, a: parse_command(
                    u, c, a, model, tokenizer, is_qwen
                ),
                encoding=enc,
            )
            evaluate(
                file_path=f"{d}/test_{lang}.csv",
                result_path=f"{key}/result{suffix}_{lang}.csv",
                save_path=f"{key}/result{suffix}_{lang}.json",
                encoding=enc,
            )

        # Free GPU memory before loading the next model.
        del model
        torch.cuda.empty_cache()

    print("✅ Done!")
