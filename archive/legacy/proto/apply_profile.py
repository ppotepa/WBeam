#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path
from typing import Any


def normalize_key(key: str) -> str:
    k = re.sub(r"[^A-Za-z0-9_]+", "_", str(key)).strip("_").upper()
    if not k:
        raise ValueError(f"invalid key: {key!r}")
    if k[0].isdigit():
        k = "_" + k
    return k


def scalar_to_str(value: Any) -> str:
    if value is None:
        return ""
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return str(value)
    if isinstance(value, str):
        return value
    if isinstance(value, list):
        return ",".join(scalar_to_str(v) for v in value)
    return json.dumps(value, separators=(",", ":"))


def normalize_map(raw: Any, section: str) -> dict[str, Any]:
    if raw is None:
        return {}
    if not isinstance(raw, dict):
        raise ValueError(f"profile section '{section}' must be object")
    out: dict[str, Any] = {}
    for k, v in raw.items():
        out[normalize_key(k)] = v
    return out


def read_json(path: Path) -> dict[str, Any]:
    raw = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(raw, dict):
        raise ValueError(f"JSON root must be object: {path}")
    return raw


def render_conf(cfg: dict[str, Any], source_json: Path) -> str:
    lines = [
        f"# Generated from {source_json}",
        "# Do not edit manually; edit proto.json or re-run apply_profile.py.",
    ]
    for key in sorted(cfg):
        val = scalar_to_str(cfg[key])
        if re.search(r"\s", val) or val == "":
            lines.append(f'{key}="{val}"')
        else:
            lines.append(f"{key}={val}")
    return "\n".join(lines) + "\n"


def main() -> int:
    p = argparse.ArgumentParser(description="Apply versioned runtime profile to proto/config/proto.json")
    p.add_argument("profile", help="Profile name from config/profiles.json")
    p.add_argument("--config", default="config/proto.json")
    p.add_argument("--profiles", default="config/profiles.json")
    args = p.parse_args()

    root = Path(__file__).resolve().parent
    config_path = (root / args.config).resolve()
    profiles_path = (root / args.profiles).resolve()
    conf_path = config_path.with_suffix(".conf")

    cfg = read_json(config_path)
    profiles_doc = read_json(profiles_path)
    profiles = profiles_doc.get("profiles")
    if not isinstance(profiles, dict):
        raise ValueError(f"missing 'profiles' object in {profiles_path}")
    profile = profiles.get(args.profile)
    if not isinstance(profile, dict):
        raise ValueError(f"profile '{args.profile}' not found in {profiles_path}")

    values = normalize_map(profile.get("values"), "values")
    quality = normalize_map(profile.get("quality"), "quality")
    latency = normalize_map(profile.get("latency"), "latency")

    merged = dict(cfg)
    merged.update(values)
    merged.update(quality)
    merged.update(latency)
    merged["PROTO_PROFILE"] = args.profile
    merged["PROTO_PROFILE_FILE"] = args.profiles

    config_path.write_text(json.dumps(merged, indent=2) + "\n", encoding="utf-8")
    conf_path.write_text(render_conf(merged, config_path), encoding="utf-8")

    print(f"[proto] applied profile: {args.profile}")
    print(f"[proto] wrote: {config_path}")
    print(f"[proto] wrote: {conf_path}")
    print(f"[proto] quality keys: {', '.join(sorted(quality.keys())) or '(none)'}")
    print(f"[proto] latency keys: {', '.join(sorted(latency.keys())) or '(none)'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
