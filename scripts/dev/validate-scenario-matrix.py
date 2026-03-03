#!/usr/bin/env python3
"""
Scenario matrix validator for Arc Reactor runtime.

Purpose:
- Run scenario-driven API checks with explicit expectations.
- Expand one scenario into many cases using matrix dimensions.
- Validate tool calls (`toolsUsed`) and response expectations.

Example:
  python3 scripts/dev/validate-scenario-matrix.py \
    --base-url http://localhost:8080 \
    --scenario-file scripts/dev/scenarios/user-activity-matrix.json \
    --admin-token "$ADMIN_TOKEN" \
    --strict
"""

from __future__ import annotations

import argparse
import itertools
import json
import random
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

MISSING = object()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run scenario matrix validation against Arc Reactor.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--tenant-id", default="default")
    parser.add_argument("--scenario-file", required=True)
    parser.add_argument("--report-file", default="")
    parser.add_argument("--email", default="")
    parser.add_argument("--password", default="passw0rd!")
    parser.add_argument("--name", default="Scenario Matrix QA")
    parser.add_argument("--admin-token", default="")
    parser.add_argument("--admin-email", default="")
    parser.add_argument("--admin-password", default="")
    parser.add_argument("--max-cases", type=int, default=0, help="0 means unlimited")
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--include-tags", default="")
    parser.add_argument("--exclude-tags", default="")
    parser.add_argument("--case-delay-ms", type=int, default=0)
    parser.add_argument("--rate-limit-backoff-sec", type=int, default=0)
    parser.add_argument("--strict", action="store_true")
    parser.add_argument("--stop-on-fail", action="store_true")
    parser.add_argument("--verbose", action="store_true")
    return parser.parse_args()


def now_ms() -> int:
    return int(time.time() * 1000)


def deep_merge(base: Any, override: Any) -> Any:
    if isinstance(base, dict) and isinstance(override, dict):
        merged = dict(base)
        for key, value in override.items():
            if key in merged:
                merged[key] = deep_merge(merged[key], value)
            else:
                merged[key] = value
        return merged
    return override


def render_templates(value: Any, variables: dict[str, Any]) -> Any:
    if isinstance(value, dict):
        return {k: render_templates(v, variables) for k, v in value.items()}
    if isinstance(value, list):
        return [render_templates(item, variables) for item in value]
    if isinstance(value, str):
        pattern = re.compile(r"{{\s*([^}\s]+)\s*}}")
        return pattern.sub(lambda m: str(variables.get(m.group(1), m.group(0))), value)
    return value


def get_path(data: Any, dotted_path: str) -> Any:
    cursor = data
    if dotted_path == "":
        return cursor
    for raw_part in dotted_path.split("."):
        part = raw_part.strip()
        if part == "":
            continue
        if isinstance(cursor, list):
            if not part.isdigit():
                return MISSING
            idx = int(part)
            if idx < 0 or idx >= len(cursor):
                return MISSING
            cursor = cursor[idx]
        elif isinstance(cursor, dict):
            if part not in cursor:
                return MISSING
            cursor = cursor[part]
        else:
            return MISSING
    return cursor


def http_json_request(
    method: str,
    url: str,
    headers: dict[str, str],
    json_body: Any | None,
    timeout_sec: float,
) -> tuple[int, str, Any]:
    payload = None
    req_headers = dict(headers)
    if json_body is not None:
        payload = json.dumps(json_body).encode("utf-8")
        req_headers.setdefault("Content-Type", "application/json")
    request = urllib.request.Request(url=url, method=method.upper(), data=payload, headers=req_headers)
    try:
        with urllib.request.urlopen(request, timeout=timeout_sec) as response:
            status = int(response.getcode())
            body = response.read().decode("utf-8", errors="replace")
    except urllib.error.HTTPError as err:
        status = int(err.code)
        body = err.read().decode("utf-8", errors="replace")
    parsed: Any = None
    try:
        parsed = json.loads(body) if body.strip() else None
    except json.JSONDecodeError:
        parsed = None
    return status, body, parsed


def register_or_login(
    base_url: str,
    email: str,
    password: str,
    name: str,
) -> str:
    register_payload = {"email": email, "password": password, "name": name}
    register_status, register_body, register_json = http_json_request(
        method="POST",
        url=f"{base_url}/api/auth/register",
        headers={},
        json_body=register_payload,
        timeout_sec=20,
    )
    if register_status == 201:
        token = str((register_json or {}).get("token", "")).strip()
        if not token:
            raise RuntimeError(f"register succeeded but token missing: {register_body}")
        return token
    if register_status != 409:
        raise RuntimeError(f"register failed status={register_status} body={register_body}")

    login_payload = {"email": email, "password": password}
    login_status, login_body, login_json = http_json_request(
        method="POST",
        url=f"{base_url}/api/auth/login",
        headers={},
        json_body=login_payload,
        timeout_sec=20,
    )
    if login_status != 200:
        raise RuntimeError(f"login failed status={login_status} body={login_body}")
    token = str((login_json or {}).get("token", "")).strip()
    if not token:
        raise RuntimeError(f"login succeeded but token missing: {login_body}")
    return token


def split_tags(value: str) -> set[str]:
    return {tag.strip() for tag in value.split(",") if tag.strip()}


def case_tags(case: dict[str, Any]) -> set[str]:
    raw = case.get("tags", [])
    if isinstance(raw, str):
        return {raw.strip()} if raw.strip() else set()
    if isinstance(raw, list):
        return {str(item).strip() for item in raw if str(item).strip()}
    return set()


def build_cases(
    scenario_doc: dict[str, Any],
    runtime_vars: dict[str, Any],
    include_tags: set[str],
    exclude_tags: set[str],
) -> list[dict[str, Any]]:
    defaults = scenario_doc.get("defaults", {})
    source_cases = scenario_doc.get("scenarios", [])
    built: list[dict[str, Any]] = []

    for raw_case in source_cases:
        if not isinstance(raw_case, dict):
            continue
        if raw_case.get("enabled", True) is False:
            continue
        tags = case_tags(raw_case)
        if include_tags and not (tags & include_tags):
            continue
        if exclude_tags and (tags & exclude_tags):
            continue

        merged_case = deep_merge(defaults, raw_case)
        matrix = merged_case.pop("matrix", {})
        if not matrix:
            rendered = render_templates(merged_case, runtime_vars)
            built.append(rendered)
            continue

        keys = list(matrix.keys())
        values = []
        for key in keys:
            dimension_values = matrix.get(key, [])
            if not isinstance(dimension_values, list) or not dimension_values:
                raise ValueError(f"matrix dimension '{key}' must be a non-empty list")
            values.append(dimension_values)

        for combo in itertools.product(*values):
            combo_vars = dict(runtime_vars)
            combo_vars.update({k: v for k, v in zip(keys, combo)})
            rendered = render_templates(merged_case, combo_vars)
            combo_suffix = ",".join(f"{k}={v}" for k, v in zip(keys, combo))
            rendered["id"] = f"{rendered.get('id', 'case')}[{combo_suffix}]"
            built.append(rendered)

    return built


def evaluate_expectations(
    expect: dict[str, Any],
    status: int,
    body_text: str,
    body_json: Any,
) -> list[str]:
    failures: list[str] = []
    content = ""
    tools_used: list[str] = []

    if isinstance(body_json, dict):
        raw_content = body_json.get("content")
        if raw_content is None:
            content = body_text
        else:
            rendered_content = str(raw_content)
            # Some responses include content="" while body contains useful JSON.
            content = body_text if rendered_content.strip() == "" else rendered_content
        raw_tools = body_json.get("toolsUsed", [])
        if isinstance(raw_tools, list):
            tools_used = [str(tool) for tool in raw_tools]
    else:
        content = body_text

    expected_status = expect.get("status")
    if expected_status is not None and status != int(expected_status):
        failures.append(f"status expected={expected_status} actual={status}")

    status_in = expect.get("statusIn")
    if isinstance(status_in, list) and status not in [int(v) for v in status_in]:
        failures.append(f"status expected in {status_in}, actual={status}")

    expected_success = expect.get("success")
    if expected_success is not None:
        actual_success = get_path(body_json, "success")
        if actual_success is MISSING:
            failures.append("json path 'success' missing")
        elif bool(actual_success) != bool(expected_success):
            failures.append(f"success expected={expected_success} actual={actual_success}")

    tools_all = [str(t) for t in expect.get("toolsUsedAll", [])]
    for tool in tools_all:
        if tool not in tools_used:
            failures.append(f"toolsUsed missing required '{tool}'")

    tools_any = [str(t) for t in expect.get("toolsUsedAny", [])]
    if tools_any and not any(tool in tools_used for tool in tools_any):
        failures.append(f"toolsUsed must contain one of {tools_any}, actual={tools_used}")

    tools_none = [str(t) for t in expect.get("toolsUsedNone", [])]
    for tool in tools_none:
        if tool in tools_used:
            failures.append(f"toolsUsed must not contain '{tool}'")

    min_count = expect.get("toolsUsedMinCount")
    if min_count is not None and len(tools_used) < int(min_count):
        failures.append(f"toolsUsed count expected>={min_count}, actual={len(tools_used)}")

    max_count = expect.get("toolsUsedMaxCount")
    if max_count is not None and len(tools_used) > int(max_count):
        failures.append(f"toolsUsed count expected<={max_count}, actual={len(tools_used)}")

    for needle in [str(s) for s in expect.get("contentContainsAll", [])]:
        if needle not in content:
            failures.append(f"content missing substring '{needle}'")

    contains_any = [str(s) for s in expect.get("contentContainsAny", [])]
    if contains_any and not any(needle in content for needle in contains_any):
        failures.append(f"content must contain one of {contains_any}")

    regex_all = [str(s) for s in expect.get("contentRegexAll", [])]
    for pattern in regex_all:
        if re.search(pattern, content, flags=re.MULTILINE) is None:
            failures.append(f"content missing regex /{pattern}/")

    regex_any = [str(s) for s in expect.get("contentRegexAny", [])]
    if regex_any and not any(re.search(pattern, content, flags=re.MULTILINE) for pattern in regex_any):
        failures.append(f"content must match one of {regex_any}")

    for pattern in [str(s) for s in expect.get("contentNotRegex", [])]:
        if re.search(pattern, content, flags=re.MULTILINE):
            failures.append(f"content must not match regex /{pattern}/")

    error_contains = expect.get("errorContains")
    if error_contains is not None:
        actual_error = get_path(body_json, "errorMessage")
        if actual_error is MISSING:
            failures.append("json path 'errorMessage' missing")
        elif str(error_contains) not in str(actual_error):
            failures.append(f"errorMessage missing '{error_contains}'")

    json_equals = expect.get("jsonEquals", {})
    if isinstance(json_equals, dict):
        for path, expected_value in json_equals.items():
            actual_value = get_path(body_json, str(path))
            if actual_value is MISSING:
                failures.append(f"json path missing for equals check: {path}")
            elif actual_value != expected_value:
                failures.append(
                    f"json equals mismatch path={path} expected={expected_value!r} actual={actual_value!r}"
                )

    json_exists = expect.get("jsonExists", [])
    if isinstance(json_exists, list):
        for path in json_exists:
            actual_value = get_path(body_json, str(path))
            if actual_value is MISSING:
                failures.append(f"json path missing: {path}")

    json_regex = expect.get("jsonRegex", {})
    if isinstance(json_regex, dict):
        for path, pattern in json_regex.items():
            actual_value = get_path(body_json, str(path))
            if actual_value is MISSING:
                failures.append(f"json path missing for regex check: {path}")
                continue
            if re.search(str(pattern), str(actual_value), flags=re.MULTILINE) is None:
                failures.append(f"json regex mismatch path={path} pattern=/{pattern}/ actual={actual_value!r}")

    return failures


def execute_case(
    base_url: str,
    tenant_id: str,
    case: dict[str, Any],
    user_token: str,
    admin_token: str,
    verbose: bool,
) -> dict[str, Any]:
    case_id = str(case.get("id", "case"))
    method = str(case.get("method", "POST")).upper()
    path = str(case.get("path", "/api/chat"))
    timeout_sec = float(case.get("timeoutSec", 30))
    max_attempts = int(case.get("maxAttempts", 1))
    auth_mode = str(case.get("auth", "user")).lower()

    headers = {}
    raw_headers = case.get("headers", {})
    if isinstance(raw_headers, dict):
        headers = {str(k): str(v) for k, v in raw_headers.items()}

    token = ""
    if auth_mode == "user":
        token = user_token
    elif auth_mode == "admin":
        token = admin_token
        if not token:
            return {
                "id": case_id,
                "status": "skipped",
                "reason": "admin token is required but unavailable",
            }
    elif auth_mode == "none":
        token = ""
    else:
        return {
            "id": case_id,
            "status": "failed",
            "reason": f"unsupported auth mode: {auth_mode}",
        }

    if token:
        headers["Authorization"] = f"Bearer {token}"
    headers.setdefault("X-Tenant-Id", tenant_id)

    json_body = case.get("json")
    expect = case.get("expect", {})
    url = f"{base_url}{path}"
    attempt_results = []

    for attempt in range(1, max_attempts + 1):
        started = now_ms()
        status_code, body_text, body_json = http_json_request(
            method=method,
            url=url,
            headers=headers,
            json_body=json_body,
            timeout_sec=timeout_sec,
        )
        duration_ms = now_ms() - started
        failures = evaluate_expectations(expect, status_code, body_text, body_json)
        attempt_result = {
            "attempt": attempt,
            "httpStatus": status_code,
            "durationMs": duration_ms,
            "failures": failures,
            "bodySnippet": body_text[:1200],
        }
        attempt_results.append(attempt_result)
        if not failures:
            return {
                "id": case_id,
                "status": "passed",
                "attempts": attempt,
                "httpStatus": status_code,
                "durationMs": duration_ms,
                "bodySnippet": body_text[:1200],
            }
        if verbose:
            print(f"      attempt={attempt} failed: {failures}")

    final_failures = attempt_results[-1]["failures"] if attempt_results else ["unknown failure"]
    return {
        "id": case_id,
        "status": "failed",
        "attempts": max_attempts,
        "httpStatus": attempt_results[-1]["httpStatus"] if attempt_results else 0,
        "durationMs": attempt_results[-1]["durationMs"] if attempt_results else 0,
        "failures": final_failures,
        "attemptDetails": attempt_results,
    }


def is_rate_limited_result(result: dict[str, Any]) -> bool:
    for attempt in result.get("attemptDetails", []):
        snippet = str(attempt.get("bodySnippet", ""))
        if "Rate limit exceeded" in snippet:
            return True
    return False


def main() -> int:
    args = parse_args()
    random.seed(args.seed)

    scenario_path = Path(args.scenario_file)
    if not scenario_path.exists():
        print(f"Error: scenario file not found: {scenario_path}", file=sys.stderr)
        return 1

    try:
        scenario_doc = json.loads(scenario_path.read_text(encoding="utf-8"))
    except Exception as exc:
        print(f"Error: failed to parse scenario file: {exc}", file=sys.stderr)
        return 1

    if not args.email:
        args.email = f"qa-scenario-{int(time.time())}-{random.randint(1000, 9999)}@example.com"
    run_id = f"smx-{int(time.time())}-{random.randint(1000, 9999)}"

    runtime_vars = {
        "run_id": run_id,
        "tenant_id": args.tenant_id,
        "base_url": args.base_url,
        "user_email": args.email,
    }

    try:
        user_token = register_or_login(
            base_url=args.base_url,
            email=args.email,
            password=args.password,
            name=args.name,
        )
    except Exception as exc:
        print(f"Error: failed to resolve user token: {exc}", file=sys.stderr)
        return 1

    admin_token = args.admin_token
    if not admin_token and args.admin_email and args.admin_password:
        try:
            admin_token = register_or_login(
                base_url=args.base_url,
                email=args.admin_email,
                password=args.admin_password,
                name="Scenario Matrix Admin",
            )
        except Exception as exc:
            print(f"Warning: admin login failed ({exc})", file=sys.stderr)
            admin_token = ""

    include_tags = split_tags(args.include_tags)
    exclude_tags = split_tags(args.exclude_tags)
    all_cases = build_cases(
        scenario_doc=scenario_doc,
        runtime_vars=runtime_vars,
        include_tags=include_tags,
        exclude_tags=exclude_tags,
    )

    if not all_cases:
        print("No scenarios selected after filters.")
        return 1

    if args.max_cases > 0 and len(all_cases) > args.max_cases:
        all_cases = random.sample(all_cases, args.max_cases)

    print(f"Running {len(all_cases)} scenario cases")
    print(f"Base URL: {args.base_url}")
    print(f"Tenant ID: {args.tenant_id}")
    print(f"Run ID: {run_id}")
    print(f"User email: {args.email}")
    print(f"Admin token available: {'yes' if admin_token else 'no'}")

    results: list[dict[str, Any]] = []
    failed = 0
    skipped = 0
    rate_limited = 0

    for idx, case in enumerate(all_cases, start=1):
        case_id = str(case.get("id", f"case-{idx}"))
        print(f"[{idx}/{len(all_cases)}] {case_id}")
        result = execute_case(
            base_url=args.base_url,
            tenant_id=args.tenant_id,
            case=case,
            user_token=user_token,
            admin_token=admin_token,
            verbose=args.verbose,
        )
        results.append(result)

        status = result.get("status")
        if status == "passed":
            print("      PASS")
        elif status == "skipped":
            skipped += 1
            print(f"      SKIP: {result.get('reason', 'no reason')}")
        else:
            failed += 1
            reason = result.get("reason") or "; ".join(result.get("failures", []))
            print(f"      FAIL: {reason}")
            if is_rate_limited_result(result):
                rate_limited += 1
            if args.stop_on_fail:
                break

        is_last = idx == len(all_cases)
        if not is_last and args.case_delay_ms > 0:
            time.sleep(args.case_delay_ms / 1000.0)
        if (
            not is_last
            and args.rate_limit_backoff_sec > 0
            and status == "failed"
            and is_rate_limited_result(result)
        ):
            print(f"      INFO: rate limit detected, sleeping {args.rate_limit_backoff_sec}s")
            time.sleep(args.rate_limit_backoff_sec)

    passed = sum(1 for item in results if item.get("status") == "passed")
    failed = sum(1 for item in results if item.get("status") == "failed")
    skipped = sum(1 for item in results if item.get("status") == "skipped")

    report = {
        "generatedAtMs": now_ms(),
        "scenarioFile": str(scenario_path),
        "baseUrl": args.base_url,
        "tenantId": args.tenant_id,
        "runId": run_id,
        "summary": {
            "total": len(results),
            "passed": passed,
            "failed": failed,
            "skipped": skipped,
            "rateLimited": rate_limited,
            "strict": args.strict,
        },
        "results": results,
    }

    report_file = args.report_file.strip()
    if not report_file:
        report_file = f"/tmp/arc-scenario-report-{int(time.time())}.json"
    Path(report_file).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print("Summary:")
    print(f"  total={len(results)} passed={passed} failed={failed} skipped={skipped}")
    print(f"  report={report_file}")

    if failed > 0:
        return 1
    if args.strict and skipped > 0:
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
