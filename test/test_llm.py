import os
import json
import re

import torch
from dotenv import load_dotenv
from transformers import AutoModelForCausalLM, AutoTokenizer

from common import system_prompt, get_prompt, run_inference, evaluate

# NOTE: `meta-llama/Llama-3.1-8B-Instruct` is a gated model on HuggingFace.
# Accept the license on the model page, then put your token in a `.env` file as:
#   HF_TOKEN=hf_xxx
# `transformers` reads HF_TOKEN automatically once load_dotenv() loads it.
load_dotenv()


def load_model(hf_id: str):
    tokenizer = AutoTokenizer.from_pretrained(hf_id)
    model = AutoModelForCausalLM.from_pretrained(
        hf_id,
        torch_dtype=torch.bfloat16,
        device_map="cuda",
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
        "qwen3-8b": "Qwen/Qwen3-8B",
        "llama-3.1-8b": "meta-llama/Llama-3.1-8B-Instruct",
    }

    # (dirname, lang, encoding, suffix)
    datasets = [
        ("eval", "en", "utf-8", ""),
        ("eval", "ko", "utf-8-sig", ""),
        ("eval_hard", "en", "utf-8", "_hard"),
        ("eval_hard", "ko", "utf-8-sig", "_hard"),
    ]

    for key, hf_id in models.items():
        print(f"\n===== Loading {key} ({hf_id}) =====")
        os.makedirs(key, exist_ok=True)
        model, tokenizer = load_model(hf_id)
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
