#!/usr/bin/env python3
"""
Ensure documentation defaults stay aligned with Kotlin configuration defaults.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]


def read_text(path: str) -> str:
    return (ROOT / path).read_text(encoding="utf-8")


def extract(pattern: str, content: str, label: str) -> str:
    match = re.search(pattern, content)
    if match is None:
        raise ValueError(f"Could not extract '{label}' using pattern: {pattern}")
    return match.group(1)


def main() -> int:
    agent_properties = read_text(
        "arc-core/src/main/kotlin/com/arc/reactor/agent/config/AgentProperties.kt"
    )
    policy_properties = read_text(
        "arc-core/src/main/kotlin/com/arc/reactor/agent/config/AgentPolicyAndFeatureProperties.kt"
    )

    defaults = {
        "max_tool_calls": extract(
            r"val maxToolCalls: Int = (\d+)", agent_properties, "maxToolCalls"
        ),
        "max_tools_per_request": extract(
            r"val maxToolsPerRequest: Int = (\d+)", agent_properties, "maxToolsPerRequest"
        ),
        "temperature": extract(
            r"val temperature: Double = ([0-9.]+)", agent_properties, "temperature"
        ),
        "request_timeout_ms": extract(
            r"val requestTimeoutMs: Long = (\d+)", agent_properties, "requestTimeoutMs"
        ),
        "tool_call_timeout_ms": extract(
            r"val toolCallTimeoutMs: Long = (\d+)", agent_properties, "toolCallTimeoutMs"
        ),
        "rate_limit_per_minute": extract(
            r"val rateLimitPerMinute: Int = (\d+)", agent_properties, "rateLimitPerMinute"
        ),
        "rate_limit_per_hour": extract(
            r"val rateLimitPerHour: Int = (\d+)", agent_properties, "rateLimitPerHour"
        ),
        "max_context_window_tokens": extract(
            r"val maxContextWindowTokens: Int = (\d+)",
            agent_properties,
            "maxContextWindowTokens",
        ),
        "input_max_chars": extract(
            r"val inputMaxChars: Int = (\d+)", policy_properties, "inputMaxChars"
        ),
    }

    required_strings = {
        "AGENTS.md": [
            f"`max-tool-calls` | {defaults['max_tool_calls']}",
            f"`max-tools-per-request` | {defaults['max_tools_per_request']}",
            f"`llm.temperature` | {defaults['temperature']}",
            f"`concurrency.request-timeout-ms` | {defaults['request_timeout_ms']}",
            f"`concurrency.tool-call-timeout-ms` | {defaults['tool_call_timeout_ms']}",
            f"`guard.rate-limit-per-minute` | {defaults['rate_limit_per_minute']}",
            f"`llm.max-context-window-tokens` | {defaults['max_context_window_tokens']}",
            f"`boundaries.input-max-chars` | {defaults['input_max_chars']}",
        ],
        "CLAUDE.md": [
            f"`max-tool-calls` | {defaults['max_tool_calls']}",
            f"`max-tools-per-request` | {defaults['max_tools_per_request']}",
            f"`llm.temperature` | {defaults['temperature']}",
            f"`concurrency.request-timeout-ms` | {defaults['request_timeout_ms']}",
            f"`concurrency.tool-call-timeout-ms` | {defaults['tool_call_timeout_ms']}",
            f"`guard.rate-limit-per-minute` | {defaults['rate_limit_per_minute']}",
            f"`llm.max-context-window-tokens` | {defaults['max_context_window_tokens']}",
            f"`boundaries.input-max-chars` | {defaults['input_max_chars']}",
        ],
        "README.ko.md": [
            f"rate-limit-per-minute: {defaults['rate_limit_per_minute']}",
            f"rate-limit-per-hour: {defaults['rate_limit_per_hour']}",
            f"temperature: {defaults['temperature']}",
            f"input-max-chars: {defaults['input_max_chars']}",
        ],
        "docs/en/getting-started/configuration.md": [
            f"temperature: {defaults['temperature']}",
            f"rate-limit-per-minute: {defaults['rate_limit_per_minute']}",
            f"rate-limit-per-hour: {defaults['rate_limit_per_hour']}",
            f"`input-max-chars` | Int | {defaults['input_max_chars']}",
        ],
        "docs/ko/getting-started/configuration.md": [
            f"temperature: {defaults['temperature']}",
            f"rate-limit-per-minute: {defaults['rate_limit_per_minute']}",
            f"rate-limit-per-hour: {defaults['rate_limit_per_hour']}",
            f"`input-max-chars` | Int | {defaults['input_max_chars']}",
        ],
        "docs/en/reference/feature-inventory.md": [
            f"temperature: {defaults['temperature']}",
            f"rate-limit-per-minute: {defaults['rate_limit_per_minute']}",
            f"rate-limit-per-hour: {defaults['rate_limit_per_hour']}",
            f"input-max-chars: {defaults['input_max_chars']}",
        ],
        "docs/ko/reference/feature-inventory.md": [
            f"temperature: {defaults['temperature']}",
            f"rate-limit-per-minute: {defaults['rate_limit_per_minute']}",
            f"rate-limit-per-hour: {defaults['rate_limit_per_hour']}",
            f"input-max-chars: {defaults['input_max_chars']}",
        ],
    }

    banned_strings = {
        "README.ko.md": ["max-input-length"],
        "docs/en/getting-started/configuration.md": ["max-input-length"],
        "docs/ko/getting-started/configuration.md": ["max-input-length"],
        "docs/en/reference/feature-inventory.md": ["max-input-length"],
        "docs/ko/reference/feature-inventory.md": ["max-input-length"],
    }

    errors: list[str] = []

    for path, snippets in required_strings.items():
        content = read_text(path)
        for snippet in snippets:
            if snippet not in content:
                errors.append(f"{path}: missing snippet -> {snippet}")

    for path, snippets in banned_strings.items():
        content = read_text(path)
        for snippet in snippets:
            if snippet in content:
                errors.append(f"{path}: banned legacy snippet still present -> {snippet}")

    if errors:
        print("Default config alignment check failed:")
        for error in errors:
            print(f"- {error}")
        return 1

    print("Default config alignment check passed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
