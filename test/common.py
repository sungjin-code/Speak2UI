import sys
import time
import json
import ast

import numpy as np
import pandas as pd
from tqdm import tqdm
from sklearn.metrics import accuracy_score, f1_score
from sklearn.preprocessing import MultiLabelBinarizer


system_prompt = """You are an intelligent assistant designed to understand user input and convert it into a structured command. \
Your task is to determine the most appropriate **intent** the user wants to take on a user interface."""


def join_as_bullet(strings: list[str]) -> str:
    return "\n".join([f"- {item.strip()}" for item in strings])


def get_prompt(user_input: str, clickables: str, apps: str) -> str:
    prompt = f"""Analyse the user input and return a single JSON object with the appropriate Android action.

## Available Actions (select exactly one):

### UI Interaction Actions:
- **PRESS**: Select/tap a clickable UI element (must be from clickable elements list)
- **DOUBLE_PRESS**: Double-tap a clickable UI element (must be from clickable elements list)
- **LONG_PRESS**: Long-press/hold a clickable UI element (must be from clickable elements list)

### Input Action:
- **ENTER**: Type text using keyboard

### Navigation Actions:
- **SWIPE**: Perform swipe gesture (UP/DOWN/LEFT/RIGHT only)
- **HOME**: Press Android Home button (main screen)
- **BACK**: Press Android Back button
- **OVERVIEW_BUTTON**: Press Android current task/overview button

### App Launch:
- **OPEN**: Launch an app, open a URL, or open a specific component/page inside an app
  - `value` can be:
    - A single string → opening the app or URL directly
    - A list of exactly two strings → opening a specific page/component inside an app (first: component identifier, second: app identifier)

### Fallback:
- **NONE**: Input is unclear, not actionable, or lacks required information

## Constraints:
- **PRESS/DOUBLE_PRESS/LONG_PRESS**: `value` must exactly match one item from clickable elements list. Do not create new names; if no exact match exists, return NONE.
- **OPEN**:
  - If launching an app/URL → `value` is a single string from available apps list
  - If opening a specific page/component → `value` is a list of exactly two strings in the correct order:
    1. Component identifier (e.g., "component:mobile_network")
    2. App identifier (e.g., "app:settings")
- **SWIPE**: `value` must be exactly one of: UP, DOWN, LEFT, RIGHT
- **ENTER**: `value` contains the text to type (string only)
- **HOME/BACK/OVERVIEW_BUTTON/NONE**: `value` is empty string
- **Only the OPEN action can return a list as value. All other actions must return a single string or empty string.**

## Available Apps:
{apps}

## Clickable Elements:
{clickables}

## Response Format:
Return ONLY a JSON object with this exact structure:
- If single value: {{"intent": "<ACTION_NAME>", "value": "<REQUIRED_VALUE>"}}
- If list value (OPEN only): {{"intent": "OPEN", "value": ["<COMPONENT_ID>", "<APP_ID>"]}}

## Analysis Guidelines:
- Use your comprehensive language understanding to interpret user intent
- Recognize synonyms, translations, and common alternative names across languages
- Match conceptually similar terms (e.g., "메일" conceptually matches "Gmail")
- Handle mixed language inputs and colloquial expressions
- Consider semantic meaning, not just literal text matching
- For ENTER intent, remove verbs or instruction words meaning "to type" (e.g., "라고 입력해", "를 쳐") so that value contains only the exact text to be typed.
- Only use ENTER if the user explicitly instructs to type or input text; otherwise, return NONE.
- Only return a list when:
  - intent is OPEN
  - both a specific component/page AND the app are clearly identified
- Ensure list order correctness: component first, then app

## User Input: "{user_input}"
"""

    return prompt


def run_inference(file_path, save_path, parse_fn, encoding="utf-8", pause=0):
    """Run inference over a test set using a model-specific `parse_fn`.

    `parse_fn(user_input, clickables, apps) -> dict` performs a single
    prediction and returns a dict with "intent" and "value" keys.
    """
    print(f"Running inference on {file_path}...")

    test_result = {"ID": [], "intent": [], "value": [], "time": []}

    with open("eval/apps.txt", "r", encoding="utf-8") as f:
        installed_apps = f.read()
    installed_apps = join_as_bullet(installed_apps.split("\n"))

    df_test = pd.read_csv(file_path, encoding=encoding)

    for idx, row in tqdm(df_test.iterrows(), total=len(df_test)):
        index = int(row["ID"])
        user_input = str(row["query"]).strip()
        clickables = ast.literal_eval(row["clickables"])
        clickables = join_as_bullet(clickables)

        try:
            start_time = time.perf_counter()
            response = parse_fn(user_input, clickables, installed_apps)
            execution_time = time.perf_counter() - start_time

            intent = str(response.get("intent", "ERROR")).strip().upper()
            value = response.get("value", [])
            if isinstance(value, str):
                if value.strip() != "":
                    if intent == "SWIPE":
                        value = value.upper()
                    value = [value]
                else:
                    value = []

            test_result["ID"].append(index)
            test_result["intent"].append(intent)
            test_result["value"].append(value)
            test_result["time"].append(execution_time)

            if pause:
                time.sleep(pause)

        except Exception as e:
            tqdm.write(f"[{idx}] Error: {e}")
            sys.exit(1)

    df_result = pd.DataFrame(test_result)
    df_result.to_csv(save_path, encoding=encoding, index=False)


def evaluate(file_path, result_path, save_path, encoding="utf-8"):
    df_test = pd.read_csv(file_path, encoding=encoding)
    df_infer = pd.read_csv(result_path, encoding=encoding)

    true_intent = df_test["intent"].to_numpy()
    true_value = df_test["value"].to_numpy()

    predict_intent = df_infer["intent"].to_numpy()
    predict_value = df_infer["value"].to_numpy()
    inference_time = df_infer["time"].to_numpy(dtype=np.float64)

    mean_inference_time = np.mean(inference_time)
    max_inference_time = np.max(inference_time)

    intent_accuracy = accuracy_score(true_intent, predict_intent)
    intent_weighted_f1 = f1_score(true_intent, predict_intent, average="weighted")

    true_value = [
        ast.literal_eval(v) if isinstance(v, str) else [] for v in df_test["value"]
    ]
    predict_value = [
        ast.literal_eval(v) if isinstance(v, str) else [] for v in df_infer["value"]
    ]

    mlb = MultiLabelBinarizer()

    all_labels_nested = true_value + predict_value
    mlb.fit(all_labels_nested)

    true_value_binarized = mlb.transform(true_value)
    predict_value_binarized = mlb.transform(predict_value)

    value_weighted_f1 = f1_score(
        true_value_binarized,
        predict_value_binarized,
        average="weighted",
        zero_division=0,
    )

    with open(save_path, "w") as json_file:
        json.dump(
            {
                "Exact_Match": intent_accuracy,
                "Action_F1": intent_weighted_f1,
                "Target_F1": value_weighted_f1,
                "Average_Time": mean_inference_time,
                "Max_Time": max_inference_time,
            },
            json_file,
        )
