import os
import json

import openai
from dotenv import load_dotenv

from common import system_prompt, get_prompt, run_inference, evaluate


# OpenAI API Key (set OPENAI_API_KEY in a `.env` file)
load_dotenv()
API_KEY = os.getenv("OPENAI_API_KEY")

client = openai.OpenAI(api_key=API_KEY)


def parse_command(
    user_input: str,
    clickables: str,
    apps: str,
    model_name: str,
) -> dict:
    prompt_text = get_prompt(user_input, clickables, apps)

    temperature = 0
    if model_name.startswith("gpt-5"):
        temperature = 1

    try:
        response = client.chat.completions.create(
            model=model_name,
            response_format={"type": "json_object"},
            messages=[
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": prompt_text},
            ],
            temperature=temperature,
        )
        return json.loads(response.choices[0].message.content)
    except Exception as e:
        return json.dumps({"error": str(e)}, indent=2)


if __name__ == "__main__":
    #############################
    ###### Test Parameters ######
    lang = "en"
    model = "gpt-5-mini"
    is_hard = True
    ##############################

    encoding = "utf-8"
    if lang.lower() == "ko":
        encoding = "utf-8-sig"

    suffix = ""
    if is_hard:
        suffix = "_hard"

    model_names = {
        "gpt-4o-mini": "gpt-4o-mini-2024-07-18",
        "gpt-5-mini": "gpt-5-mini-2025-08-07",
    }
    if model not in model_names.keys():
        raise NameError(f"Only allow models: {model_names.keys()}")

    model_name = model_names[model]

    run_inference(
        file_path=f"eval{suffix}/test_{lang}.csv",
        save_path=f"{model}/result{suffix}_{lang}.csv",
        parse_fn=lambda u, c, a: parse_command(u, c, a, model_name),
        encoding=encoding,
        pause=3,
    )
    evaluate(
        file_path=f"eval{suffix}/test_{lang}.csv",
        result_path=f"{model}/result{suffix}_{lang}.csv",
        save_path=f"{model}/result{suffix}_{lang}.json",
        encoding=encoding,
    )
    print("✅ Done!")
