#!/usr/bin/env python3
"""
Validate Atlassian MCP skill routing via user prompts.

Goal:
- Simulate user questions.
- Verify whether expected Atlassian skill is actually used (`toolsUsed`).
- Capture response quality and routing coverage.
"""

from __future__ import annotations

import argparse
import json
import random
import re
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Validate Atlassian skill routing through /api/chat.")
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--tenant-id", default="default")
    parser.add_argument("--server-name", default="atlassian")
    parser.add_argument("--admin-token", default="")
    parser.add_argument("--admin-email", default="admin@arc.local")
    parser.add_argument("--admin-password", default="AdminPassw0rd!")
    parser.add_argument("--user-email", default="")
    parser.add_argument("--user-password", default="passw0rd!")
    parser.add_argument("--user-name", default="Skill Routing QA")
    parser.add_argument("--max-tools", type=int, default=0, help="0 means all")
    parser.add_argument(
        "--tools",
        default="",
        help="Comma-separated subset of tool names to validate instead of the full server tool list.",
    )
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--case-delay-ms", type=int, default=300)
    parser.add_argument("--report-file", default="")
    parser.add_argument("--markdown-file", default="")
    parser.add_argument("--strict", action="store_true", help="Exit non-zero if any tool is not routed")
    return parser.parse_args()


def now_ms() -> int:
    return int(time.time() * 1000)


def http_json_request(
    method: str,
    url: str,
    headers: dict[str, str],
    json_body: Any | None = None,
    timeout_sec: float = 30,
) -> tuple[int, str, Any]:
    req_headers = dict(headers)
    payload = None
    if json_body is not None:
        payload = json.dumps(json_body).encode("utf-8")
        req_headers.setdefault("Content-Type", "application/json")

    request = urllib.request.Request(url=url, method=method.upper(), headers=req_headers, data=payload)
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


def login_with_credentials(base_url: str, email: str, password: str) -> tuple[int, str, Any]:
    login_payload = {"email": email, "password": password}
    return http_json_request(
        method="POST",
        url=f"{base_url}/api/auth/login",
        headers={},
        json_body=login_payload,
        timeout_sec=20,
    )


def register_or_login(base_url: str, email: str, password: str, name: str) -> str:
    login_code, login_body, login_json = login_with_credentials(base_url, email, password)
    if login_code == 200:
        token = str((login_json or {}).get("token", "")).strip()
        if token:
            return token
        raise RuntimeError(f"login succeeded but token missing: {login_body}")

    register_payload = {"email": email, "password": password, "name": name}
    register_code, register_body, register_json = http_json_request(
        method="POST",
        url=f"{base_url}/api/auth/register",
        headers={},
        json_body=register_payload,
        timeout_sec=20,
    )
    if register_code == 201:
        token = str((register_json or {}).get("token", "")).strip()
        if token:
            return token
        raise RuntimeError(f"register succeeded but token missing: {register_body}")
    if register_code == 409:
        login_code, login_body, login_json = login_with_credentials(base_url, email, password)
        if login_code != 200:
            raise RuntimeError(f"register returned 409 and login failed status={login_code} body={login_body}")
        token = str((login_json or {}).get("token", "")).strip()
        if not token:
            raise RuntimeError(f"login succeeded but token missing: {login_body}")
        return token
    if login_code == 401 and register_code in {401, 403}:
        raise RuntimeError("login failed and self-registration is unavailable")
    raise RuntimeError(f"register failed status={register_code} body={register_body}")


def login_only(base_url: str, email: str, password: str) -> str:
    login_code, login_body, login_json = login_with_credentials(base_url, email, password)
    if login_code != 200:
        raise RuntimeError(f"admin login failed status={login_code} body={login_body}")
    token = str((login_json or {}).get("token", "")).strip()
    if not token:
        raise RuntimeError(f"login succeeded but token missing: {login_body}")
    return token


def resolve_admin_token(args: argparse.Namespace) -> str:
    if args.admin_token:
        return args.admin_token
    return login_only(
        base_url=args.base_url,
        email=args.admin_email,
        password=args.admin_password,
    )


def fetch_server_tools(base_url: str, admin_token: str, server_name: str) -> list[str]:
    status, body_text, body_json = http_json_request(
        method="GET",
        url=f"{base_url}/api/mcp/servers/{server_name}",
        headers={"Authorization": f"Bearer {admin_token}"},
        timeout_sec=20,
    )
    if status != 200:
        raise RuntimeError(f"failed to fetch MCP server details status={status} body={body_text}")
    raw_tools = (body_json or {}).get("tools", [])
    if not isinstance(raw_tools, list):
        raise RuntimeError(f"invalid tools payload: {body_text}")
    return [str(t) for t in raw_tools]


def prompt_for_tool(tool: str) -> str:
    prompt_map = {
        "jira_list_projects": "Jira 프로젝트 목록 보여줘.",
        "jira_search_issues": "JQL `project = DEV ORDER BY created DESC` 기준으로 최근 Jira 이슈를 찾아서 요약해줘.",
        "jira_get_issue": "Jira 이슈 DEV-123 내용을 확인해줘.",
        "jira_add_comment": "Jira DEV-123 이슈에 테스트 코멘트를 남겨줘.",
        "jira_transition_issue": "Jira DEV-123 이슈 상태를 In Progress로 변경 시도해줘.",
        "jira_update_issue": "Jira DEV-123 이슈 설명을 업데이트해줘.",
        "jira_assign_issue": "Jira DEV-123 이슈를 담당자에게 할당해줘.",
        "jira_link_issues": "Jira DEV-123과 DEV-456 이슈를 연관 관계로 연결해줘.",
        "jira_due_soon_issues": "마감 임박 Jira 이슈를 조회해줘.",
        "jira_get_transitions": "Jira DEV-123에서 가능한 전이 목록 보여줘.",
        "jira_daily_briefing": "Jira 프로젝트 DEV 기준으로 오늘 일일 브리핑을 만들어줘.",
        "jira_my_open_issues": "내가 담당한 Jira 오픈 이슈 목록 알려줘.",
        "jira_search_by_text": "Jira에서 payment 키워드로 검색해줘.",
        "jira_create_issue": "Jira에 테스트 이슈 생성해줘.",
        "jira_create_subtask": "Jira DEV-123 하위 작업 하나 생성해줘.",
        "jira_blocker_digest": "Jira 프로젝트 DEV 기준 blocker 이슈 요약 보고서를 만들어줘.",
        "bitbucket_list_repositories": "Bitbucket 저장소 목록 보여줘.",
        "bitbucket_list_prs": "Bitbucket workspace=jarvis, repository=arc-reactor 의 PR 목록을 조회해줘.",
        "bitbucket_get_pr": "Bitbucket workspace=jarvis, repository=arc-reactor, prId=1 의 PR 상세를 조회해줘.",
        "bitbucket_add_pr_comment": "Bitbucket workspace=jarvis, repository=arc-reactor, prId=1 에 '테스트 코멘트'를 추가해줘.",
        "bitbucket_approve_pr": "Bitbucket PR 승인 시도해줘.",
        "bitbucket_list_branches": "Bitbucket 브랜치 목록 보여줘.",
        "bitbucket_stale_prs": "Bitbucket workspace=jarvis, repository=arc-reactor, staleDays=7 기준으로 오래된 PR 찾아줘.",
        "bitbucket_review_queue": "Bitbucket 리뷰 대기열 정리해줘.",
        "bitbucket_review_sla_alerts": "Bitbucket 리뷰 SLA 경고를 점검해줘.",
        "confluence_list_spaces": "Confluence 스페이스 목록 보여줘.",
        "confluence_search": "Confluence에서 이번 주 리포트 관련 페이지를 찾아줘.",
        "confluence_search_by_text": "Confluence에서 sprint 키워드로 검색해줘.",
        "confluence_get_page": "Confluence pageId=123456 페이지 상세 정보를 조회해줘.",
        "confluence_get_page_content": "Confluence pageId=123456 페이지 본문 내용을 가져와줘.",
        "confluence_create_page": "Confluence에 테스트 페이지 생성해줘.",
        "confluence_update_page": "Confluence pageId=123456, version=1, title='E2E Update', body='자동 검증 업데이트'로 페이지를 업데이트해줘.",
        "confluence_create_runbook": "Confluence spaceId=ENG, title='Arc Reactor Runbook', serviceName='arc-reactor', summary='운영 런북', owner='kim', oncallChannel='#jarvis'로 런북 페이지 생성해줘.",
        "confluence_create_meeting_notes": "Confluence spaceId=ENG, title='주간 회의록', meetingDate='2026-03-03', attendees='kim,lee', agenda='진행상황', decisions='다음주 릴리즈', actionItems='테스트 자동화'로 회의록 페이지 만들어줘.",
        "confluence_create_incident_postmortem": "Confluence spaceId=ENG, title='장애 포스트모템', incidentDate='2026-03-01', summary='일시 장애', timeline='10:00 감지, 10:30 복구', rootCause='DB 연결 불안정', actionItems='재시도 정책 보강'으로 문서 생성해줘.",
        "confluence_create_weekly_status_report": "Confluence spaceId=ENG, title='주간 상태 리포트', weekLabel='2026-W10', team='Jarvis', highlights='안정화 진행', plannedWork='MCP 개선', risks='권한 이슈'로 페이지 만들어줘.",
        "confluence_create_sprint_summary": "Confluence spaceId=ENG, title='스프린트 요약', sprintName='Sprint-42', period='2026-03-01~2026-03-14', highlights='핵심 기능 완료', risks='일부 지연'으로 요약 페이지 만들어줘.",
        "confluence_create_weekly_auto_summary_page": "Confluence 주간 자동 요약 페이지 생성해줘.",
        "confluence_generate_weekly_auto_summary_draft": "Confluence 주간 자동 요약 초안 생성해줘.",
        "work_set_briefing_profile": "name=default, jiraProjectKey=DEV, bitbucketWorkspace=jarvis, bitbucketRepoSlug=arc-reactor, confluenceKeyword=weekly 로 업무 브리핑 프로필 저장해줘.",
        "work_list_briefing_profiles": "업무 브리핑 프로필 목록을 보여줘.",
        "work_delete_briefing_profile": "업무 브리핑 프로필 하나 삭제해줘.",
        "work_morning_briefing": "오늘 아침 업무 브리핑 만들어줘.",
        "work_release_readiness_pack": "releaseName='v1.0.0', jiraProjectKey='DEV', bitbucketWorkspace='jarvis', bitbucketRepoSlug='arc-reactor', confluenceKeyword='release', dueSoonDays=3, reviewSlaHours=24로 릴리즈 준비 상태 패키지 만들어줘.",
        "work_release_risk_digest": "releaseName='v1.0.0', jiraProjectKey='DEV', bitbucketWorkspace='jarvis', bitbucketRepoSlug='arc-reactor', confluenceKeyword='release'로 릴리즈 리스크 다이제스트 만들어줘.",
        "work_prepare_standup_update": "jiraProjectKey=DEV, bitbucketWorkspace=jarvis, bitbucketRepoSlug=arc-reactor, confluenceKeyword=weekly 조건으로 스탠드업 업데이트 초안 작성해줘.",
        "work_action_items_to_jira": "confluencePageId=123456, jiraProjectKey=DEV, issueType=Task, dryRun=true, maxCreate=5 조건으로 액션 아이템을 Jira 이슈로 변환해줘.",
        "work_owner_lookup": "PAY-123 이슈 기준으로 담당 서비스와 owner, 팀을 찾아줘.",
        "work_item_context": "PAY-123 이슈 전체 맥락을 정리해줘. 관련 문서와 PR, 다음 액션까지 포함해줘.",
        "work_service_context": "payments 서비스 기준으로 최근 Jira 이슈, 관련 문서, 열린 PR까지 한 번에 요약해줘.",
    }
    natural = prompt_map.get(tool, f"{tool} 관련 작업을 실행해줘.")
    return f"{natural} 반드시 `{tool}` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘."


def pick_channel(index: int) -> str:
    channels = ["web", "slack", "api"]
    return channels[index % len(channels)]


def run_case(
    base_url: str,
    tenant_id: str,
    user_token: str,
    tool: str,
    run_id: str,
    channel: str,
) -> dict[str, Any]:
    prompt = prompt_for_tool(tool)
    payload = {
        "message": prompt,
        "metadata": {
            "sessionId": f"{run_id}-{tool}",
            "channel": channel,
            "source": "skill-routing-e2e",
            "activity": "user_question",
        },
    }
    started = now_ms()
    status, body_text, body_json = http_json_request(
        method="POST",
        url=f"{base_url}/api/chat",
        headers={
            "Authorization": f"Bearer {user_token}",
            "X-Tenant-Id": tenant_id,
        },
        json_body=payload,
        timeout_sec=60,
    )
    duration_ms = now_ms() - started

    success = bool((body_json or {}).get("success")) if isinstance(body_json, dict) else False
    tools_used = (body_json or {}).get("toolsUsed", []) if isinstance(body_json, dict) else []
    if not isinstance(tools_used, list):
        tools_used = []
    tools_used = [str(t) for t in tools_used]

    content = ""
    error_message = ""
    if isinstance(body_json, dict):
        content = str(body_json.get("content", "") or "")
        error_message = str(body_json.get("errorMessage", "") or "")
    if not content:
        content = body_text

    routed = tool in tools_used
    rate_limited = "Rate limit exceeded" in body_text
    quality_ok = bool(content.strip()) and "unknown error occurred" not in content.lower()
    execution_ok = status == 200 and success and routed and quality_ok

    return {
        "tool": tool,
        "channel": channel,
        "question": prompt,
        "httpStatus": status,
        "success": success,
        "routed": routed,
        "executionOk": execution_ok,
        "qualityOk": quality_ok,
        "rateLimited": rate_limited,
        "durationMs": duration_ms,
        "toolsUsed": tools_used,
        "errorMessage": error_message,
        "responseSnippet": content[:500],
    }


def to_markdown(report: dict[str, Any]) -> str:
    lines: list[str] = []
    s = report["summary"]
    lines.append("# Atlassian Skill Routing E2E Report")
    lines.append("")
    lines.append(f"- generatedAt: {report['generatedAtMs']}")
    lines.append(f"- runId: `{report['runId']}`")
    lines.append(f"- baseUrl: `{report['baseUrl']}`")
    lines.append(f"- tenantId: `{report['tenantId']}`")
    lines.append(f"- toolsTotal: {s['toolsTotal']}")
    lines.append(f"- executionOk: {s['executionOk']}")
    lines.append(f"- routed: {s['routed']}")
    lines.append(f"- notRouted: {s['notRouted']}")
    lines.append(f"- rateLimited: {s['rateLimited']}")
    lines.append("")
    lines.append("## Questions And Results")
    lines.append("")
    lines.append("| Tool | Routed | Success | Status | Question | Snippet |")
    lines.append("|---|---:|---:|---:|---|---|")
    for item in report["results"]:
        question = item["question"].replace("|", "\\|")
        snippet = item["responseSnippet"].replace("|", "\\|").replace("\n", " ")
        lines.append(
            f"| `{item['tool']}` | {'Y' if item['routed'] else 'N'} | "
            f"{'Y' if item['success'] else 'N'} | {item['httpStatus']} | "
            f"{question} | {snippet} |"
        )
    return "\n".join(lines) + "\n"


def main() -> int:
    args = parse_args()
    random.seed(args.seed)

    run_id = f"skill-e2e-{int(time.time())}-{random.randint(1000, 9999)}"
    user_email = args.user_email or f"qa-skill-routing-{int(time.time())}-{random.randint(1000, 9999)}@example.com"

    admin_token = resolve_admin_token(args)
    user_token = register_or_login(args.base_url, user_email, args.user_password, args.user_name)
    tools = fetch_server_tools(args.base_url, admin_token, args.server_name)

    requested_tools = [tool.strip() for tool in args.tools.split(",") if tool.strip()]
    if requested_tools:
        available = set(tools)
        missing = [tool for tool in requested_tools if tool not in available]
        if missing:
            raise RuntimeError(f"requested tools are not exposed by server '{args.server_name}': {missing}")
        tools = requested_tools

    if args.max_tools > 0:
        tools = tools[: args.max_tools]

    print(f"Running skill routing for {len(tools)} tools")
    print(f"Run ID: {run_id}")
    print(f"User email: {user_email}")

    results: list[dict[str, Any]] = []
    for idx, tool in enumerate(tools, start=1):
        channel = pick_channel(idx)
        print(f"[{idx}/{len(tools)}] {tool} channel={channel}")
        result = run_case(
            base_url=args.base_url,
            tenant_id=args.tenant_id,
            user_token=user_token,
            tool=tool,
            run_id=run_id,
            channel=channel,
        )
        results.append(result)
        routed_text = "ROUTED" if result["routed"] else "NOT_ROUTED"
        print(f"      status={result['httpStatus']} success={result['success']} {routed_text}")
        if idx != len(tools) and args.case_delay_ms > 0:
            time.sleep(args.case_delay_ms / 1000.0)

    routed = sum(1 for r in results if r["routed"])
    execution_ok = sum(1 for r in results if r["executionOk"])
    rate_limited = sum(1 for r in results if r["rateLimited"])

    report = {
        "generatedAtMs": now_ms(),
        "runId": run_id,
        "baseUrl": args.base_url,
        "tenantId": args.tenant_id,
        "serverName": args.server_name,
        "modelHint": "runtime-dependent",
        "summary": {
            "toolsTotal": len(results),
            "executionOk": execution_ok,
            "routed": routed,
            "notRouted": len(results) - routed,
            "rateLimited": rate_limited,
        },
        "results": results,
    }

    report_file = args.report_file.strip() or f"/tmp/atlassian-skill-routing-{int(time.time())}.json"
    Path(report_file).write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    markdown_file = args.markdown_file.strip()
    if markdown_file:
        Path(markdown_file).write_text(to_markdown(report), encoding="utf-8")

    print("Summary:")
    print(
        f"  toolsTotal={len(results)} executionOk={execution_ok} routed={routed} "
        f"notRouted={len(results) - routed} rateLimited={rate_limited}"
    )
    print(f"  report={report_file}")
    if markdown_file:
        print(f"  markdown={markdown_file}")

    if args.strict and routed != len(results):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
