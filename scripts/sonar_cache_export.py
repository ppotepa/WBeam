#!/usr/bin/env python3
"""
Pull every Sonar issue for the wbeam project and cache it locally.

Outputs:
  .sonarcache/issues_raw.json      # full issue payload
  .sonarcache/issues_summary.jsonl # flattened JSON per issue
  .sonarcache/issues_summary.txt   # readable text-form list
"""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Dict, Iterable, List, Tuple
from urllib import parse, request


DEFAULT_HOST = "http://127.0.0.1:9000"
DEFAULT_PROJECT = "wbeam"
CACHE_DIR = Path(".sonarcache")
RAW_FILENAME = "issues_raw.json"
SUMMARY_JSONL_FILENAME = "issues_summary.jsonl"
SUMMARY_TEXT_FILENAME = "issues_summary.txt"


def load_env_file(path: Path) -> Dict[str, str]:
    if not path.exists():
        return {}
    data: Dict[str, str] = {}
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        data[key.strip()] = value.strip().strip('"').strip("'")
    return data


def build_arg_parser(env_defaults: Dict[str, str]) -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Cache Sonar issues locally for faster iteration."
    )
    parser.add_argument(
        "--env-file",
        default=".env.sonar",
        help="Path to the env file containing SONAR_HOST/SONAR_TOKEN (default: %(default)s)",
    )
    parser.add_argument(
        "--host",
        default=os.getenv("SONAR_HOST") or env_defaults.get("SONAR_HOST") or DEFAULT_HOST,
        help="Sonar base URL (default: %(default)s)",
    )
    parser.add_argument(
        "--token",
        default=os.getenv("SONAR_TOKEN") or env_defaults.get("SONAR_TOKEN"),
        help="Sonar API token (reads from env/env-file if omitted).",
    )
    parser.add_argument(
        "--project",
        default=os.getenv("SONAR_PROJECT_KEY")
        or env_defaults.get("SONAR_PROJECT_KEY")
        or DEFAULT_PROJECT,
        help="Sonar project key/component key (default: %(default)s)",
    )
    parser.add_argument(
        "--page-size",
        type=int,
        default=500,
        help="Number of records per request (max 500).",
    )
    parser.add_argument(
        "--component-key",
        default=None,
        help="Optional component key override (defaults to the project key).",
    )
    parser.add_argument(
        "--output-dir",
        type=Path,
        default=CACHE_DIR,
        help="Directory for cached files (default: .sonarcache/).",
    )
    return parser


def build_auth_header(token: str) -> str:
    encoded = base64.b64encode(f"{token}:".encode("utf-8")).decode("ascii")
    return f"Basic {encoded}"


def sonar_request(host: str, token: str, path: str, params: Dict[str, str]) -> Dict:
    query = parse.urlencode(params)
    url = f"{host.rstrip('/')}{path}?{query}"
    req = request.Request(url)
    req.add_header("Authorization", build_auth_header(token))
    req.add_header("Accept", "application/json")
    with request.urlopen(req) as response:
        if response.status != 200:
            raise RuntimeError(f"Sonar API returned HTTP {response.status} for {url}")
        return json.load(response)


def fetch_all_issues(
    host: str, token: str, project_key: str, page_size: int, component_key: str | None
) -> Tuple[List[Dict], Dict]:
    issues: List[Dict] = []
    page = 1
    total = None
    while True:
        payload = sonar_request(
            host,
            token,
            "/api/issues/search",
            {
                "componentKeys": component_key or project_key,
                "projects": project_key,
                "p": page,
                "ps": min(page_size, 500),
            },
        )
        batch = payload.get("issues", [])
        issues.extend(batch)
        paging = payload.get("paging") or {}
        total = paging.get("total", len(issues))
        if len(issues) >= total or not batch:
            return issues, payload
        page += 1


def format_issue(issue: Dict) -> Dict:
    text_range = issue.get("textRange") or {}
    component = issue.get("component", "")
    rel_path = component.split(":", 1)[-1] if ":" in component else component
    return {
        "key": issue.get("key"),
        "rule": issue.get("rule"),
        "severity": issue.get("severity"),
        "type": issue.get("type"),
        "status": issue.get("status"),
        "assignee": issue.get("assignee"),
        "project": issue.get("project"),
        "component": rel_path,
        "line": issue.get("line") or text_range.get("startLine"),
        "message": issue.get("message"),
        "debt": issue.get("debt"),
        "creationDate": issue.get("creationDate"),
        "updateDate": issue.get("updateDate"),
        "tags": issue.get("tags"),
    }


def write_summary_files(issues: Iterable[Dict], output_dir: Path) -> None:
    output_dir.mkdir(parents=True, exist_ok=True)
    flattened = [format_issue(issue) for issue in issues]
    timestamp = datetime.now(timezone.utc).isoformat()

    raw_payload = {
        "fetched_at": timestamp,
        "issue_count": len(flattened),
        "issues": issues,
    }
    raw_path = output_dir / RAW_FILENAME
    summary_jsonl_path = output_dir / SUMMARY_JSONL_FILENAME
    summary_text_path = output_dir / SUMMARY_TEXT_FILENAME

    raw_path.write_text(json.dumps(raw_payload, indent=2))

    with summary_jsonl_path.open("w", encoding="utf-8") as summary_file:
        for row in flattened:
            summary_file.write(json.dumps(row, ensure_ascii=False) + "\n")

    with summary_text_path.open("w", encoding="utf-8") as human_file:
        for row in flattened:
            location = f"{row['component']}:{row['line']}" if row.get("line") else row[
                "component"
            ]
            human_file.write(
                f"[{row.get('severity')}/{row.get('type')}] {location} "
                f"({row.get('rule')}) {row.get('message')}\n"
            )


def main(argv: List[str]) -> int:
    pre_parser = argparse.ArgumentParser(add_help=False)
    pre_parser.add_argument("--env-file", default=".env.sonar")
    pre_args, remaining = pre_parser.parse_known_args(argv)

    env_defaults = load_env_file(Path(pre_args.env_file))
    parser = build_arg_parser(env_defaults)
    args = parser.parse_args(argv)

    token = args.token
    if not token:
        parser.error("Missing Sonar token. Provide --token or set SONAR_TOKEN.")

    issues, _ = fetch_all_issues(
        args.host, token, args.project, args.page_size, args.component_key
    )

    write_summary_files(issues, args.output_dir)

    print(
        f"Fetched {len(issues)} issues for {args.project} "
        f"into {args.output_dir.resolve()}",
        file=sys.stderr,
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
