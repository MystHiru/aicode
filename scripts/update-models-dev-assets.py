#!/usr/bin/env python3
"""发版前手动执行：更新内置 models.dev 模型元数据快照（app/src/main/assets/api.official.json）。

约束：
- 仅保留内置快照已有的 provider，不引入新 provider；这些 provider 下的新模型可直接加入。
- 每个模型仅保留裁剪字段：id / name / tool_call / temperature / reasoning / reasoning_options / limit / modalities(input) / cost。
- 旧快照有而网络版已下架的模型保留（用户可能仍在使用）。
- 网络拉取或解析失败时打印错误并以非零退出，绝不改动现有快照。

用法：python3 scripts/update-models-dev-assets.py
"""

import json
import os
import sys
import urllib.request

MODELS_DEV_URL = "https://models.dev/api.json"
ASSET_PATH = os.path.abspath(
    os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "api.official.json")
)
UA = "aicode-assets-updater"


def trim_model(mv: dict) -> dict:
    out = {}
    for key in ("id", "name", "tool_call", "temperature", "reasoning", "reasoning_options", "limit"):
        if key in mv:
            out[key] = mv[key]
    modalities = mv.get("modalities", {})
    if isinstance(modalities, dict) and "input" in modalities:
        out["modalities"] = {"input": modalities["input"]}
    if "cost" in mv:
        out["cost"] = mv["cost"]
    return out


def main() -> int:
    if not os.path.isfile(ASSET_PATH):
        print(f"错误：未找到内置快照 {ASSET_PATH}")
        return 1

    with open(ASSET_PATH, encoding="utf-8") as f:
        current = json.load(f)
    providers = list(current.keys())

    print(f"拉取 {MODELS_DEV_URL} ...")
    try:
        req = urllib.request.Request(MODELS_DEV_URL, headers={"User-Agent": UA})
        with urllib.request.urlopen(req, timeout=30) as resp:
            remote = json.loads(resp.read().decode("utf-8"))
    except Exception as e:
        print(f"拉取/解析失败（{e}），跳过更新，保持现有快照")
        return 1

    if not isinstance(remote, dict):
        print("远端数据格式异常，跳过更新，保持现有快照")
        return 1

    merged = {}
    for pid in providers:
        net_models = remote.get(pid, {}).get("models", {})
        models = {
            mid: trim_model(mv)
            for mid, mv in net_models.items()
            if isinstance(mv, dict)
        }
        for mid, mv in current.get(pid, {}).get("models", {}).items():
            if mid not in models:
                models[mid] = mv
        merged[pid] = {"models": models}

    old_total = sum(len(p.get("models", {})) for p in current.values())
    new_total = sum(len(p.get("models", {})) for p in merged.values())
    if new_total == 0:
        print("合并结果为空，放弃更新")
        return 1

    with open(ASSET_PATH, "w", encoding="utf-8") as f:
        json.dump(merged, f, ensure_ascii=False, separators=(",", ":"))
    print(f"已更新 {ASSET_PATH}")
    for pid in providers:
        old = len(current.get(pid, {}).get("models", {}))
        new = len(merged.get(pid, {}).get("models", {}))
        flag = "（新增模型）" if new > old else ""
        print(f"  {pid}: {old} -> {new} {flag}")
    print(f"合计：{old_total} -> {new_total} 个模型")
    return 0


if __name__ == "__main__":
    sys.exit(main())
