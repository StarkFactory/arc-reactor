#!/usr/bin/env python3
"""
Detect and optionally remove redundant MCP servers.

A server is considered "redundant" when all of its exposed tools are shadowed by
other servers according to a deterministic preference rule.

Default mode is dry-run. Use --apply to execute DELETE calls.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any


@dataclass(frozen=True)
class HttpResult:
    status: int
    body: Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Find and optionally delete redundant MCP servers."
    )
    parser.add_argument(
        "--base-url",
        default="http://localhost:8080",
        help="Arc Reactor base URL (default: http://localhost:8080)",
    )
    parser.add_argument(
        "--tenant-id",
        default="default",
        help="Tenant ID header value (default: default)",
    )
    parser.add_argument(
        "--token",
        default="",
        help="Admin JWT token. If omitted, --email/--password login is used.",
    )
    parser.add_argument("--email", default="", help="Admin email for login")
    parser.add_argument("--password", default="", help="Admin password for login")
    parser.add_argument(
        "--keep",
        action="append",
        default=[],
        help="Server name to keep when duplicates exist. Repeatable.",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Apply deletions. Without this flag, script only reports.",
    )
    return parser.parse_args()


def request_json(
    method: str,
    url: str,
    headers: dict[str, str],
    body: dict[str, Any] | None = None,
) -> HttpResult:
    raw_data = None
    if body is not None:
        raw_data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url=url, method=method, headers=headers, data=raw_data)
    try:
        with urllib.request.urlopen(req, timeout=15) as resp:
            payload = resp.read().decode("utf-8")
            parsed = json.loads(payload) if payload else None
            return HttpResult(status=resp.status, body=parsed)
    except urllib.error.HTTPError as exc:
        payload = exc.read().decode("utf-8")
        parsed = json.loads(payload) if payload else payload
        return HttpResult(status=exc.code, body=parsed)


def resolve_token(args: argparse.Namespace) -> str:
    if args.token.strip():
        return args.token.strip()

    if not args.email.strip() or not args.password:
        print(
            "ERROR: provide --token or both --email and --password for admin login.",
            file=sys.stderr,
        )
        raise SystemExit(2)

    login_url = f"{args.base_url.rstrip('/')}/api/auth/login"
    result = request_json(
        method="POST",
        url=login_url,
        headers={"Content-Type": "application/json"},
        body={"email": args.email.strip(), "password": args.password},
    )
    if result.status != 200 or not isinstance(result.body, dict) or not result.body.get("token"):
        print(f"ERROR: login failed with status={result.status} body={result.body}", file=sys.stderr)
        raise SystemExit(3)
    return str(result.body["token"])


def pick_preferred_server(
    servers_for_tool: list[str],
    keep_servers: set[str],
) -> str:
    pinned = sorted([name for name in servers_for_tool if name in keep_servers])
    if pinned:
        return pinned[0]
    return sorted(servers_for_tool)[0]


def main() -> int:
    args = parse_args()
    token = resolve_token(args)

    common_headers = {
        "Authorization": f"Bearer {token}",
        "X-Tenant-Id": args.tenant_id,
        "Content-Type": "application/json",
    }
    base_url = args.base_url.rstrip("/")

    list_result = request_json("GET", f"{base_url}/api/mcp/servers", headers=common_headers)
    if list_result.status != 200 or not isinstance(list_result.body, list):
        print(
            f"ERROR: failed to list MCP servers status={list_result.status} body={list_result.body}",
            file=sys.stderr,
        )
        return 4

    server_names = sorted(
        [str(item.get("name")) for item in list_result.body if isinstance(item, dict) and item.get("name")]
    )
    if not server_names:
        print("No MCP servers found.")
        return 0

    keep_servers = set(args.keep)
    server_tools: dict[str, list[str]] = {}
    for name in server_names:
        encoded = urllib.parse.quote(name, safe="")
        detail_result = request_json(
            "GET",
            f"{base_url}/api/mcp/servers/{encoded}",
            headers=common_headers,
        )
        if detail_result.status != 200 or not isinstance(detail_result.body, dict):
            print(
                f"ERROR: failed to read MCP server '{name}' status={detail_result.status} body={detail_result.body}",
                file=sys.stderr,
            )
            return 5
        raw_tools = detail_result.body.get("tools") or []
        tools = [str(tool) for tool in raw_tools if isinstance(tool, str) and tool.strip()]
        server_tools[name] = sorted(set(tools))

    tool_to_servers: dict[str, list[str]] = {}
    for server_name, tools in server_tools.items():
        for tool in tools:
            tool_to_servers.setdefault(tool, []).append(server_name)

    duplicate_tools = {
        tool: sorted(servers) for tool, servers in tool_to_servers.items() if len(servers) > 1
    }
    if not duplicate_tools:
        print("No duplicate MCP tool names detected across servers.")
        return 0

    print("Duplicate MCP tools detected:")
    for tool in sorted(duplicate_tools):
        print(f"  - {tool}: {', '.join(duplicate_tools[tool])}")

    preferred_by_tool = {
        tool: pick_preferred_server(servers, keep_servers)
        for tool, servers in duplicate_tools.items()
    }

    redundant_servers: list[str] = []
    print("\nServer shadowing summary:")
    for server_name in server_names:
        tools = server_tools.get(server_name, [])
        if not tools:
            print(f"  - {server_name}: no tools")
            continue

        shadowed = [t for t in tools if preferred_by_tool.get(t) != server_name and t in duplicate_tools]
        unique_tools = [t for t in tools if t not in duplicate_tools]
        print(
            f"  - {server_name}: total={len(tools)}, shadowed={len(shadowed)}, unique={len(unique_tools)}"
        )

        if server_name in keep_servers:
            continue
        if shadowed and len(shadowed) == len(tools):
            redundant_servers.append(server_name)

    redundant_servers = sorted(set(redundant_servers))
    if not redundant_servers:
        print("\nNo fully redundant servers found. Nothing to delete.")
        return 0

    print("\nRedundant MCP servers (all tools shadowed):")
    for name in redundant_servers:
        print(f"  - {name}")

    if not args.apply:
        print("\nDry-run only. Re-run with --apply to delete redundant servers.")
        return 0

    print("\nApplying deletions...")
    deleted = 0
    for name in redundant_servers:
        encoded = urllib.parse.quote(name, safe="")
        result = request_json("DELETE", f"{base_url}/api/mcp/servers/{encoded}", headers=common_headers)
        if result.status not in (200, 202, 204):
            print(
                f"ERROR: delete failed for '{name}' status={result.status} body={result.body}",
                file=sys.stderr,
            )
            return 6
        print(f"  - deleted: {name}")
        deleted += 1

    print(f"\nDone. Deleted {deleted} server(s).")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
