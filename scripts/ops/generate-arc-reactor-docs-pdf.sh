#!/usr/bin/env bash
set -euo pipefail

export PATH="/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:$PATH"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
WORKSPACE_DIR="$(cd "$ROOT_DIR/.." && pwd)"
REPORT_DIR="$ROOT_DIR/reports"
TMP_DIR="$REPORT_DIR/.pdf-work"
PLAYWRIGHT_DIR="${ARC_REACTOR_ADMIN_RENDER_DIR:-$WORKSPACE_DIR/arc-reactor-admin}"
LOG_FILE="$REPORT_DIR/cron-generate-docs.log"
LOCK_FILE="$REPORT_DIR/.generate-docs.lock"
BLOCK_FILE_FULL="$REPORT_DIR/.auto-analysis-block-full.md"
BLOCK_FILE_CTO="$REPORT_DIR/.auto-analysis-block-cto.md"

FULL_DOC="$REPORT_DIR/arc-reactor-full-feature-guide.md"
CTO_DOC="$REPORT_DIR/arc-reactor-cto-usage-guide.md"
FULL_PDF="$REPORT_DIR/arc-reactor-full-feature-guide.pdf"
CTO_PDF="$REPORT_DIR/arc-reactor-cto-usage-guide.pdf"

ARC_REACTOR_REPO="$ROOT_DIR"
ASLAN_IAM_REPO="$WORKSPACE_DIR/aslan-iam"
SWAGGER_MCP_REPO="$WORKSPACE_DIR/swagger-mcp-server"
ATLASSIAN_MCP_REPO="$WORKSPACE_DIR/atlassian-mcp-server"
CLIPPING_MCP_REPO="$WORKSPACE_DIR/clipping-mcp-server"
ARC_ADMIN_REPO="$WORKSPACE_DIR/arc-reactor-admin"
ARC_WEB_REPO="$WORKSPACE_DIR/arc-reactor-web"
ARC_WEB_MODULE_REPO="$ARC_REACTOR_REPO/arc-web"
ARC_SLACK_REPO="$ARC_REACTOR_REPO/arc-slack"
ARC_VERS_REPO="$WORKSPACE_DIR/Aslan-Verse-Web"

ANALYSIS_WINDOW_MINUTES=2
FORCE_PDF_RUN=0

usage() {
  cat <<'EOF'
Usage:
  generate-arc-reactor-docs-pdf.sh [--pdf-now] [--help]

  --pdf-now : 문서 갱신 후 PDF를 즉시 강제 생성
  --help    : 사용법 출력
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --pdf-now|--force-pdf)
      FORCE_PDF_RUN=1
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      usage
      exit 1
      ;;
  esac
  shift
done

if [[ "$FORCE_PDF_RUN" == "1" ]]; then
  export FORCE_PDF=1
fi

mkdir -p "$TMP_DIR"

sanitize_md_cell() {
  printf '%s' "$1" | sed 's/|/｜/g'
}

first_heading() {
  local path="$1"
  local text
  text="$(sed -n '1,40p' "$path" | awk '/^# /{sub(/^# +/, "", $0); print; exit}')"
  if [[ -z "$text" ]]; then
    echo "(heading unavailable)"
  else
    echo "$text"
  fi
}

repo_snapshot_row() {
  local name="$1"
  local path="$2"
  local role="$3"

  local status="MISSING"
  local readme="-"
  local title="-"
  local branch="N/A"
  local commit="N/A"
  local dirty="N/A"
  local updated="N/A"

  if [[ -d "$path" ]]; then
    status="OK"
    if [[ -f "$path/README.ko.md" ]]; then
      readme="$path/README.ko.md"
    elif [[ -f "$path/README.md" ]]; then
      readme="$path/README.md"
    fi

    if [[ "$readme" != "-" ]]; then
      title="$(first_heading "$readme")"
    fi

    if git -C "$path" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      branch="$(git -C "$path" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "N/A")"
      commit="$(git -C "$path" rev-parse --short HEAD 2>/dev/null || echo "N/A")"
      updated="$(git -C "$path" log -1 --pretty=format:'%cr' 2>/dev/null || echo "N/A")"
      if [[ -n "$(git -C "$path" status --porcelain 2>/dev/null)" ]]; then
        dirty="dirty"
      else
        dirty="clean"
      fi
    fi
  else
    title="(repository missing)"
    readme="N/A"
  fi

  echo "| $(sanitize_md_cell "$name") | $(sanitize_md_cell "$path") | $(sanitize_md_cell "$role") | $(sanitize_md_cell "$status") | $(sanitize_md_cell "$readme") | $(sanitize_md_cell "$title") | $(sanitize_md_cell "$branch") | $(sanitize_md_cell "$commit") | $(sanitize_md_cell "$dirty") | $(sanitize_md_cell "$updated") |"
}

repo_snapshot_row_cto() {
  local name="$1"
  local path="$2"
  local role="$3"

  local status="MISSING"
  local title="-"
  local branch="N/A"
  local commit="N/A"
  local dirty="N/A"

  if [[ -d "$path" ]]; then
    status="OK"
    if [[ -f "$path/README.ko.md" ]]; then
      title="$(first_heading "$path/README.ko.md")"
    elif [[ -f "$path/README.md" ]]; then
      title="$(first_heading "$path/README.md")"
    fi

    if git -C "$path" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      branch="$(git -C "$path" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "N/A")"
      commit="$(git -C "$path" rev-parse --short HEAD 2>/dev/null || echo "N/A")"
      if [[ -n "$(git -C "$path" status --porcelain 2>/dev/null)" ]]; then
        dirty="dirty"
      else
        dirty="clean"
      fi
    fi
  else
    title="(repository missing)"
  fi

  echo "| $(sanitize_md_cell "$name") | $(sanitize_md_cell "$path") | $(sanitize_md_cell "$role") | $(sanitize_md_cell "$status") | $(sanitize_md_cell "$title") | $(sanitize_md_cell "$branch") | $(sanitize_md_cell "$commit") | $(sanitize_md_cell "$dirty") |"
}

first_match_line() {
  local file="$1"
  local pattern="$2"
  if [[ ! -f "$file" ]]; then
    echo "(not found)"
    return 0
  fi
  local found
  found="$(rg -n -F -m 1 -- "$pattern" "$file" | sed -E 's/^[0-9]+://g' | sed 's/[`\\]//g')"
  if [[ -z "$found" ]]; then
    echo "(not found)"
    return 0
  fi
  echo "$found" | tr '\n' ' ' | sed 's/`//g'
}

append_code_evidence() {
  local repo="$1"
  local label="$2"
  local file="$3"
  local pattern
  local results

  if [[ ! -d "$repo" ]]; then
    echo "### ${label} 코드 근거" >> "$file"
    echo "- 저장소 미발견" >> "$file"
    echo >> "$file"
    return
  fi

  echo "### ${label} 코드/설정 근거" >> "$file"
  echo "- 저장소: $repo" >> "$file"

  for pattern in "@RestController|@Controller" "class .*Guard" "class .*Hook" "class .*Scheduler" "class .*Tool" "maxToolCalls" "max-tools-per-request" "/api/mcp/servers" "ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES" "SSE"; do
    echo "- 근거 패턴: \`$pattern\`" >> "$file"
    results="$(rg -n -P -g '*.kt' -g '*.java' -g '*.kts' -g '*.yml' -g '*.yaml' -g '*.properties' "$pattern" "$repo" 2>/dev/null | sed -n '1,4p' | sed 's/|/｜/g' | tr '\n' '\n' || true)"
    if [[ -n "$results" ]]; then
      while IFS= read -r line; do
        echo "  - $line" >> "$file"
      done <<< "$results"
    else
      echo "  - (해당 패턴 미발견)" >> "$file"
    fi
    echo >> "$file"
  done
}

append_api_surface() {
  local repo="$1"
  local label="$2"
  local file="$3"

  local controllers
  local endpoint_hits
  local path
  local line_no
  local func_line
  local context_lines
  local count=0

  echo "### ${label} API 엔드포인트 근거" >> "$file"
  echo "- 저장소: $repo" >> "$file"

  if [[ ! -d "$repo" ]]; then
    echo "  - 저장소 미발견" >> "$file"
    echo >> "$file"
    return
  fi

  controllers="$(rg -l --no-heading -g '*.kt' -g '*.java' -e '@(RestController|Controller)' "$repo" 2>/dev/null | rg -v '/test/' | head -n 80 || true)"
  if [[ -z "$controllers" ]]; then
    echo "  - 컨트롤러 구현 파일 미발견(또는 테스트만 존재)" >> "$file"
    echo >> "$file"
    return
  fi

  while IFS= read -r path; do
    [[ -z "$path" ]] && continue
    [[ $count -ge 12 ]] && break
    count=$((count + 1))

    echo "  - $(sanitize_md_cell "${path#$repo/}")" >> "$file"
    endpoint_hits="$(rg -n -e '@(GetMapping|PostMapping|PutMapping|DeleteMapping|PatchMapping|RequestMapping)' -g '*.kt' -g '*.java' "$path" 2>/dev/null | head -n 60 || true)"
    if [[ -z "$endpoint_hits" ]]; then
      echo "    - (매핑 애노테이션 미발견)" >> "$file"
      continue
    fi

  while IFS= read -r endpoint_hits_line; do
      [[ -z "$endpoint_hits_line" ]] && continue
      line_no="${endpoint_hits_line%%:*}"
      func_line="$(awk -v start="$line_no" 'NR > start && NR <= (start + 12) { if ($0 ~ /fun[[:space:]]/) { print NR ":" $0; exit } }' "$path" || true)"
      context_lines="$(awk -v start="$line_no" '
        NR >= start && NR <= (start + 18) {
          if ($0 ~ /@Operation|@Tag|@RequestBody|@RequestParam|@PathVariable|@RequestHeader|@Valid|@Size|@NotBlank|fun[[:space:]]/) {
            print NR ":" $0
          }
          if ($0 ~ /fun[[:space:]]/) { exit }
        }' "$path" | sed -E "s/^[[:space:]]+//" || true)"

      if [[ -n "$func_line" ]]; then
        echo "    - $(sanitize_md_cell "$endpoint_hits_line") -> $(sanitize_md_cell "$func_line")" >> "$file"
      else
        echo "    - $(sanitize_md_cell "$endpoint_hits_line")" >> "$file"
      fi
      if [[ -n "$context_lines" ]]; then
        while IFS= read -r context_line; do
          [[ -z "$context_line" ]] && continue
          echo "      - $(sanitize_md_cell "$context_line")" >> "$file"
        done <<< "$context_lines"
      fi
    done <<< "$endpoint_hits"
  done <<< "$controllers"

  echo >> "$file"
}

append_interlink_evidence() {
  local file="$1"
  local repo_path
  local repo_label

  local target_key
  local target_pattern
  local hit_lines
  local relation_targets=(
    "aslan-iam|aslan-iam|aslan-iam"
    "swagger-mcp-server|swagger-mcp|swagger"
    "atlassian-mcp-server|atlassian-mcp|atlassian"
    "clipping-mcp-server|clipping-mcp|clipping"
    "arc-reactor-admin|arc-reactor-admin|arc-reactor-admin"
    "arc-reactor-web|arc-reactor-web|arc-reactor-web"
    "arc-slack|arc-reactor-slack|arc-slack|슬랙|Slack"
    "MCP|MCP|MCP"
    "JWT|JWT|jwt"
    "SSE|SSE|sse"
    "tenant|tenant"
  )

  echo "- 분석 방식: 각 저장소의 구성 파일/컨트롤러에서 다른 컴포넌트 명칭을 근거로 상호 연동 단서를 추출합니다." >> "$file"
  echo >> "$file"

  while IFS='|' read -r repo_label repo_path rest; do
    echo "#### ${repo_label}" >> "$file"
    echo "- 저장소: ${repo_path}" >> "$file"

    for target in "${relation_targets[@]}"; do
      IFS='|' read -r target_key target_pattern _ <<< "$target"
      [[ -z "$target_key" ]] && continue
      [[ "$repo_label" == "$target_key" ]] && continue

      hit_lines="$(rg -n --no-heading \
        -g '*.md' -g '*.kt' -g '*.java' -g '*.yml' -g '*.yaml' -g '*.properties' -g '*.kts' \
        -e "$target_pattern" "$repo_path" 2>/dev/null | head -n 2 | sed 's/|/｜/g' || true)"
   if [[ -n "$hit_lines" ]]; then
        echo "  - ${target_key} 언급/연동 증거" >> "$file"
        while IFS= read -r hit_line; do
          [[ -z "$hit_line" ]] && continue
          echo "    - ${hit_line}" >> "$file"
        done <<< "$hit_lines"
      fi
    done
    echo >> "$file"
  done <<'EOF'
arc-reactor|/Users/jinan/ai/arc-reactor|
aslan-iam|/Users/jinan/ai/aslan-iam|
swagger-mcp-server|/Users/jinan/ai/swagger-mcp-server|
atlassian-mcp-server|/Users/jinan/ai/atlassian-mcp-server|
clipping-mcp-server|/Users/jinan/ai/clipping-mcp-server|
arc-reactor-admin|/Users/jinan/ai/arc-reactor-admin|
arc-reactor-web|/Users/jinan/ai/arc-reactor-web|
Aslan-Verse-Web|/Users/jinan/ai/Aslan-Verse-Web|
EOF
}

write_change_summary() {
  local repo="$1"
  local label="$2"
  local file="$3"
  local since="${ANALYSIS_WINDOW_MINUTES} minutes ago"
  local commits
  local changed
  local commit_count=0

  if [[ ! -d "$repo" ]] || ! git -C "$repo" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "### ${label} 변경량 요약" >> "$file"
    echo "- Git 저장소 상태: 확인 불가" >> "$file"
    echo >> "$file"
    return
  fi

  commits="$(git -C "$repo" log --since="$since" --oneline --decorate=short --max-count=6 2>/dev/null | sed -n '1,6p')"
  while IFS= read -r line; do
    if [[ -n "$line" ]]; then
      commit_count=$((commit_count + 1))
    fi
  done <<< "$commits"

  changed="$(git -C "$repo" status --short 2>/dev/null | sed -n '1,8p')"

  echo "### ${label} 변경량 요약" >> "$file"
  if [[ "$commit_count" -gt 0 ]]; then
    echo "- 최근 ${ANALYSIS_WINDOW_MINUTES}분 커밋(최대 6):" >> "$file"
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      echo "  - $line"
    done <<< "$commits" >> "$file"
  else
    echo "- 최근 ${ANALYSIS_WINDOW_MINUTES}분 커밋: 없음" >> "$file"
  fi

  if [[ -n "$changed" ]]; then
    echo "- 현재 워크트리 변경(최대 8):" >> "$file"
    while IFS= read -r line; do
      [[ -z "$line" ]] && continue
      echo "  - $line" >> "$file"
    done <<< "$changed"
  else
    echo "- 현재 워크트리 변경: 없음" >> "$file"
  fi
  echo >> "$file"
}

write_full_analysis_block() {
  local block_file="${1:-$BLOCK_FILE_FULL}"
  local run_ts
  run_ts="$(date '+%Y-%m-%d %H:%M:%S %z')"
  local eco_file="$ARC_REACTOR_REPO/ECOSYSTEM.md"
  local aslan_verse_plan="$ARC_VERS_REPO/docs/superpowers/specs/2026-04-05-aslan-verse-web-fe-design.md"

  local arc_postpoint arc_allowlist arc_ops arc_iam arc_verse
  local row_arc_reactor row_aslan_iam row_swagger row_atlassian row_clipping row_admin row_arc_web_module row_arc_slack_module row_web row_aslan_verse
  local code_block_file
  local change_block_file
  local api_block_file
  local relation_block_file
  arc_postpoint="$(sanitize_md_cell "$(first_match_line "$eco_file" "/api/mcp/servers")")"
  arc_allowlist="$(sanitize_md_cell "$(first_match_line "$eco_file" "ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES")")"
  arc_ops="$(sanitize_md_cell "$(first_match_line "$ARC_REACTOR_REPO/README.ko.md" "/api/mcp/servers")")"
  arc_iam="$(sanitize_md_cell "$(first_match_line "$ASLAN_IAM_REPO/README.md" "공개키")")"
  arc_verse="$(sanitize_md_cell "$(first_match_line "$aslan_verse_plan" "가상 휴넷")")"
  row_arc_reactor="$(repo_snapshot_row "arc-reactor" "$ARC_REACTOR_REPO" "핵심 런타임")"
  row_aslan_iam="$(repo_snapshot_row "aslan-iam" "$ASLAN_IAM_REPO" "중앙 인증(IAM)")"
  row_swagger="$(repo_snapshot_row "swagger-mcp-server" "$SWAGGER_MCP_REPO" "API 도구 MCP")"
  row_atlassian="$(repo_snapshot_row "atlassian-mcp-server" "$ATLASSIAN_MCP_REPO" "Jira/Confluence/Bitbucket MCP")"
  row_clipping="$(repo_snapshot_row "clipping-mcp-server" "$CLIPPING_MCP_REPO" "클리핑·요약 MCP")"
  row_admin="$(repo_snapshot_row "arc-reactor-admin" "$ARC_ADMIN_REPO" "운영/모니터링 UI")"
  row_arc_web_module="$(repo_snapshot_row "arc-reactor/arc-web" "$ARC_WEB_MODULE_REPO" "채팅/REST 모듈(내부)")"
  row_arc_slack_module="$(repo_snapshot_row "arc-reactor/arc-slack" "$ARC_SLACK_REPO" "슬랙 채널 모듈(내부)")"
  row_web="$(repo_snapshot_row "arc-reactor-web" "$ARC_WEB_REPO" "사용자 UI")"
  row_aslan_verse="$(repo_snapshot_row "Aslan-Verse-Web" "$ARC_VERS_REPO" "실험형 멀티 페르소나 조직 플랫폼")"
  if [[ "$arc_verse" == "(not found)" && -f "$aslan_verse_plan" ]]; then
    arc_verse="$(sanitize_md_cell "$(sed -n '1,30p' "$aslan_verse_plan" | tr '\n' ' ')")"
  fi
  code_block_file="$block_file.code"
  change_block_file="$block_file.change"
  api_block_file="$block_file.api"
  relation_block_file="$block_file.rel"

  : > "$code_block_file"
  append_code_evidence "$ARC_REACTOR_REPO" "arc-reactor" "$code_block_file"
  append_code_evidence "$ASLAN_IAM_REPO" "aslan-iam" "$code_block_file"
  append_code_evidence "$SWAGGER_MCP_REPO" "swagger-mcp-server" "$code_block_file"
  append_code_evidence "$ATLASSIAN_MCP_REPO" "atlassian-mcp-server" "$code_block_file"
  append_code_evidence "$CLIPPING_MCP_REPO" "clipping-mcp-server" "$code_block_file"
  append_code_evidence "$ARC_ADMIN_REPO" "arc-reactor-admin" "$code_block_file"
  append_code_evidence "$ARC_WEB_MODULE_REPO" "arc-reactor/arc-web" "$code_block_file"
  append_code_evidence "$ARC_SLACK_REPO" "arc-reactor/arc-slack" "$code_block_file"
  append_code_evidence "$ARC_WEB_REPO" "arc-reactor-web" "$code_block_file"
  append_code_evidence "$ARC_VERS_REPO" "Aslan-Verse-Web" "$code_block_file"

  : > "$change_block_file"
  write_change_summary "$ARC_REACTOR_REPO" "arc-reactor" "$change_block_file"
  write_change_summary "$ASLAN_IAM_REPO" "aslan-iam" "$change_block_file"
  write_change_summary "$SWAGGER_MCP_REPO" "swagger-mcp-server" "$change_block_file"
  write_change_summary "$ATLASSIAN_MCP_REPO" "atlassian-mcp-server" "$change_block_file"
  write_change_summary "$CLIPPING_MCP_REPO" "clipping-mcp-server" "$change_block_file"
  write_change_summary "$ARC_ADMIN_REPO" "arc-reactor-admin" "$change_block_file"
  write_change_summary "$ARC_WEB_MODULE_REPO" "arc-reactor/arc-web" "$change_block_file"
  write_change_summary "$ARC_SLACK_REPO" "arc-reactor/arc-slack" "$change_block_file"
  write_change_summary "$ARC_WEB_REPO" "arc-reactor-web" "$change_block_file"
  write_change_summary "$ARC_VERS_REPO" "Aslan-Verse-Web" "$change_block_file"

  : > "$api_block_file"
  append_api_surface "$ARC_REACTOR_REPO" "arc-reactor" "$api_block_file"
  append_api_surface "$ASLAN_IAM_REPO" "aslan-iam" "$api_block_file"
  append_api_surface "$SWAGGER_MCP_REPO" "swagger-mcp-server" "$api_block_file"
  append_api_surface "$ATLASSIAN_MCP_REPO" "atlassian-mcp-server" "$api_block_file"
  append_api_surface "$CLIPPING_MCP_REPO" "clipping-mcp-server" "$api_block_file"
  append_api_surface "$ARC_ADMIN_REPO" "arc-reactor-admin" "$api_block_file"
  append_api_surface "$ARC_WEB_MODULE_REPO" "arc-reactor/arc-web" "$api_block_file"
  append_api_surface "$ARC_SLACK_REPO" "arc-reactor/arc-slack" "$api_block_file"
  append_api_surface "$ARC_WEB_REPO" "arc-reactor-web" "$api_block_file"
  append_api_surface "$ARC_VERS_REPO" "Aslan-Verse-Web" "$api_block_file"

  : > "$relation_block_file"
  append_interlink_evidence "$relation_block_file"

cat > "$block_file" <<'EOF'
- 마지막 동기화: __RUN_TS__
- 분석 범위: /Users/jinan/ai 내 아슬란 생태계 프로젝트 8개 (arc-reactor, aslan-iam, swagger-mcp-server, atlassian-mcp-server, clipping-mcp-server, arc-reactor-admin, arc-reactor-web, Aslan-Verse-Web)
- 생성 방식: README/ECOSYSTEM.md 기반 정합성 스냅샷 + git 메타데이터

### 0) 작성 규칙(문체/근거/의사결정)

- 톤: 임원 보고용으로 한국어 존댓말(입니다/됩니다) 사용
- 증거 우선: 기능/관계/운영 포인트는 가능한 경우 구현 파일(경로·라인)로 뒷받침
- 미확인 항목 표기: “미확인(운영 연동/실행 검증 필요)”로 분리
- 반복 억제: 동일 주장 재사용을 피하고, 단락당 핵심 포인트 1개 이하 정렬
- 용어 정합성: Arc Reactor, MCP, IAM, ReAct, Slack, SSE를 일관된 표기 사용
- CTO 문서 반영 가이드: 각 섹션 말미에 ‘의사결정 포인트’ 1개 이상 삽입(리스크/기대효과/우선순위)

- 독자 가이드:
  - 임원/CTO: 지금 문서는 “왜 이 조합이 사업/조직에 필요한가”에 대한 판단 근거를 빠르게 찾을 수 있게 정리합니다.
  - 기획/PO: 사용자 가치, 업무 적용 시나리오, 운영 제약을 중심으로 읽을 수 있게 핵심 동선을 앞부분에 배치합니다.
  - 개발자: API/도구/연동 코어 근거를 코드 라인 단위로 추적 가능한 형태로 정리합니다.
  - 운영/보안: 배포·가드·모니터링·권한 관리 포인트를 점검 항목 형태로 정리해 추적하기 쉽게 합니다.

### 1) 한눈에 보는 핵심 요약(직무 공통)

- 이 문서는 “무엇이 있는지”와 “왜 쓸만한지”를 별도로 구분해 보여줍니다.
- Arc Reactor는 aslan-iam 인증 기반 + MCP 생태계 동적 등록 + 웹/슬랙 채널 확장 + 관리 콘솔로 이어지는 중앙형 플랫폼입니다.
- 실무 도입 시점에는 기능 존재 여부보다 “운영 연동, 정책 일관성, 비용/안전 통제”의 확인이 우선입니다.

### 2) 프로젝트 상태 스냅샷

| 프로젝트 | 경로 | 역할 | 상태 | 근거 README | README 제목 | 브랜치 | 커밋 | 워크트리 | 갱신 |
|---|---|---|---|---|---|---|---|---|---|
__ROW_ARC_REACTOR__
__ROW_ASLAN_IAM__
__ROW_SWAGGER__
__ROW_ATLASSIAN__
__ROW_CLIPPING__
__ROW_ADMIN__
__ROW_ARC_WEB_MODULE__
__ROW_ARC_SLACK_MODULE__
__ROW_WEB__
__ROW_ASLAN_VERSE__

### 3) 아슬란 세계관 관점 관계(문서 근거 기반)

- Arc Reactor의 인증 중심은 aslan-iam의 공개키 검증 흐름과 결합되어 JWT를 로컬 검증하고, 로그인·권한·토큰 발급은 aslan-iam에서 분리 운영합니다 (`aslan-iam/README.md`, `aslan-iam/README.md`의 API 항목).
- Arc Reactor는 MCP 서버를 실행 중 등록만으로 붙이기 때문에, swagger/atlassian/clipping MCP는 `POST /api/mcp/servers` 중심의 런타임 등록/프록시 구조로 관리됩니다 (`arc-reactor/README.ko.md` 및 `arc-reactor/ECOSYSTEM.md`).
- 관리자 채널(`arc-reactor-admin`)은 arc-reactor Admin API를 통해 MCP preflight, 정책, 스케줄러, 도구 정책을 검증/운영하는 데 사용됩니다 (`arc-reactor-admin/README.md`, `arc-reactor/ECOSYSTEM.md`).
- 웹 채널(`arc-reactor-web`)은 세션 채팅, SSE 스트리밍, Persona/Tool Policy/Output Guard/스케줄러 관리 화면을 제공합니다 (`arc-reactor-web/README.ko.md`).
- Slack 채널(`arc-slack`)은 Socket Mode 또는 Events API로 동일 ReAct 엔진을 채널 확장합니다 (`arc-reactor/README.ko.md`의 채널 구성).
- Aslan-Verse-Web은 실험형 시뮬레이션 좌표로서, 조직·페르소나·태스크 네트워크 구조를 Arc Reactor 페르소나/툴 정책 모델과 결합해 팀형 실험을 구성하는 대상군으로 정의합니다 (`Aslan-Verse-Web/docs/superpowers/specs/2026-04-05-aslan-verse-web-fe-design.md`).

### 3-1) 아슬란 통합 철학(흩어진 업무의 수렴)

- 기본 철학: 업무 도메인(인증, 커뮤니티, 클리핑, 보고/요약, 협업 자동화 등)이 분산되어 있어도, Arc Reactor를 중심으로 인증·도구·채널·운영 정책을 통합해 하나의 운영 체계로 묶습니다.
- 현재 근거:
  - 인증은 `aslan-iam`이 담당하고 Reactor는 공개키 기반 인증 토폴로지로 정책 수용 경로를 둡니다.
  - 작업 도구는 `POST /api/mcp/servers` 기반의 런타임 등록으로 공통 패턴에 편입됩니다.
  - 채널(웹/Slack)과 관리(운영콘솔)는 동일 정책 언어(guard/hook/tool policy)로 구동되어 조직 단위의 일관성을 유지합니다.
- 확장 로드맵: Reactor Work/Workflow를 빌더로 제공해, 팀 단위의 업무 흐름(예: 아이디어 수집→요약→승인→실행→리포팅)을 템플릿화하면 서비스별 도메인 로직만 붙여 재사용할 수 있습니다.
- 아슬란 세계관 확장 방향(계획 반영): 향후 신규 서비스가 추가될 경우, 각 서비스는 먼저 "MCP 서버 등록 방식 + Persona/Policy 설계 + Reactor 관찰 지표"의 3단계로 결합하면 기존 생태계 규칙을 유지하면서 확장할 수 있습니다.
- 의사결정 포인트: 향후 확장 시 “새 서비스마다 개별 보안 스택”보다 “Reactor 기반 정책 표준화”를 우선할지, 아니면 초기 PoC는 레이어별 게이트를 분리 적용할지 판단이 필요합니다.

### 4) 연동 포인트 근거 추출

- arc-reactor MCP 등록 포트/예시: __ARC_POSTPOINT__
- arc-reactor MCP allowlist 가드: __ARC_ALLOWLIST__
- MCP 방식 운영 포인트(REST 등록, 접근 정책/Preflight): __ARC_OPS__
- Aslan-iam 인증 키 기반 검증: __ARC_IAM__
- 실험 플랫폼 연결 근거: __ARC_VERSE__

### 5) 주의/검증 항목(자동 보고)

- `arc-reactor`는 현재 문서 스냅샷 기준으로 동적 MCP 등록/관리 API와 스케줄러/가드 설정이 구성되어 있음이 확인됩니다.
- 프로젝트별 README 존재 유무만으로 상태를 판정합니다. 문서 기반 근거가 변경되면 다음 실행에 반영됩니다.
- 더 정확한 실시간 상태(헬스/연동 테스트)는 현재 cron 스크립트 범위 밖입니다. 필요 시 별도 운영 모니터링 Job에 연결하세요.

### 6) 코드/설정 정합성 근거(자동 수집)

__CODE_EVIDENCE__

### 7) API 구현 근거(Controller/Route)

__API_SURVEY__

### 8) 이번 주기 변경량 요약

__CHANGE_SUMMARY__

### 9) 저장소 상호연동 근거(키워드 매칭)

__RELATION_EVIDENCE__
EOF

  awk -v run_ts="$run_ts" \
    -v arc_postpoint="$arc_postpoint" \
    -v arc_allowlist="$arc_allowlist" \
    -v arc_ops="$arc_ops" \
    -v arc_iam="$arc_iam" \
    -v arc_verse="$arc_verse" \
    -v row_arc_reactor="$row_arc_reactor" \
    -v row_aslan_iam="$row_aslan_iam" \
    -v row_swagger="$row_swagger" \
    -v row_atlassian="$row_atlassian" \
    -v row_clipping="$row_clipping" \
    -v row_admin="$row_admin" \
    -v row_arc_web_module="$row_arc_web_module" \
    -v row_arc_slack_module="$row_arc_slack_module" \
    -v row_web="$row_web" \
    -v row_aslan_verse="$row_aslan_verse" \
    -v code_block_file="$code_block_file" \
    -v change_block_file="$change_block_file" \
    -v api_block_file="$api_block_file" \
    -v relation_block_file="$relation_block_file" \
    '
      function emit_file(path,    line) {
        while ((getline line < path) > 0) {
          print line
        }
        close(path)
      }
      {
        gsub("__RUN_TS__", run_ts)
        gsub("__ARC_POSTPOINT__", arc_postpoint)
        gsub("__ARC_ALLOWLIST__", arc_allowlist)
        gsub("__ARC_OPS__", arc_ops)
        gsub("__ARC_IAM__", arc_iam)
        gsub("__ARC_VERSE__", arc_verse)
        gsub("__ROW_ARC_REACTOR__", row_arc_reactor)
        gsub("__ROW_ASLAN_IAM__", row_aslan_iam)
        gsub("__ROW_SWAGGER__", row_swagger)
        gsub("__ROW_ATLASSIAN__", row_atlassian)
        gsub("__ROW_CLIPPING__", row_clipping)
        gsub("__ROW_ADMIN__", row_admin)
        gsub("__ROW_ARC_WEB_MODULE__", row_arc_web_module)
        gsub("__ROW_ARC_SLACK_MODULE__", row_arc_slack_module)
        gsub("__ROW_WEB__", row_web)
        gsub("__ROW_ASLAN_VERSE__", row_aslan_verse)
        if ($0 == "__CODE_EVIDENCE__") {
          emit_file(code_block_file)
          next
        }
        if ($0 == "__API_SURVEY__") {
          emit_file(api_block_file)
          next
        }
        if ($0 == "__CHANGE_SUMMARY__") {
          emit_file(change_block_file)
          next
        }
      if ($0 == "__RELATION_EVIDENCE__") {
        emit_file(relation_block_file)
        next
      }
      print
    }' "$block_file" > "$block_file.tmp"
  mv "$block_file.tmp" "$block_file"
  rm -f "$code_block_file" "$change_block_file" "$api_block_file" "$relation_block_file"
}

write_cto_analysis_block() {
  local block_file="${1:-$BLOCK_FILE_CTO}"
  local run_ts
  run_ts="$(date '+%Y-%m-%d %H:%M:%S %z')"
  local eco_file="$ARC_REACTOR_REPO/ECOSYSTEM.md"
  local aslan_verse_plan="$ARC_VERS_REPO/docs/superpowers/specs/2026-04-05-aslan-verse-web-fe-design.md"

  local arc_postpoint arc_allowlist arc_ops arc_iam arc_verse
  local row_arc_reactor row_aslan_iam row_swagger row_atlassian row_clipping row_admin row_arc_web_module row_arc_slack_module row_web row_aslan_verse
  local cto_change_block_file
  local cto_control_point_file
  local cto_key_guard cto_key_policy

  arc_postpoint="$(sanitize_md_cell "$(first_match_line "$eco_file" "/api/mcp/servers")")"
  arc_allowlist="$(sanitize_md_cell "$(first_match_line "$eco_file" "ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES")")"
  arc_ops="$(sanitize_md_cell "$(first_match_line "$ARC_REACTOR_REPO/README.ko.md" "/api/mcp/servers")")"
  arc_iam="$(sanitize_md_cell "$(first_match_line "$ASLAN_IAM_REPO/README.md" "공개키")")"
  arc_verse="$(sanitize_md_cell "$(first_match_line "$aslan_verse_plan" "가상 휴넷")")"
  row_arc_reactor="$(repo_snapshot_row_cto "arc-reactor" "$ARC_REACTOR_REPO" "핵심 런타임")"
  row_aslan_iam="$(repo_snapshot_row_cto "aslan-iam" "$ASLAN_IAM_REPO" "중앙 인증(IAM)")"
  row_swagger="$(repo_snapshot_row_cto "swagger-mcp-server" "$SWAGGER_MCP_REPO" "API 도구 MCP")"
  row_atlassian="$(repo_snapshot_row_cto "atlassian-mcp-server" "$ATLASSIAN_MCP_REPO" "Jira/Confluence/Bitbucket MCP")"
  row_clipping="$(repo_snapshot_row_cto "clipping-mcp-server" "$CLIPPING_MCP_REPO" "클리핑·요약 MCP")"
  row_admin="$(repo_snapshot_row_cto "arc-reactor-admin" "$ARC_ADMIN_REPO" "운영/모니터링 UI")"
  row_arc_web_module="$(repo_snapshot_row_cto "arc-reactor/arc-web" "$ARC_WEB_MODULE_REPO" "채팅/REST 모듈(내부)")"
  row_arc_slack_module="$(repo_snapshot_row_cto "arc-reactor/arc-slack" "$ARC_SLACK_REPO" "슬랙 채널 모듈(내부)")"
  row_web="$(repo_snapshot_row_cto "arc-reactor-web" "$ARC_WEB_REPO" "사용자 UI")"
  row_aslan_verse="$(repo_snapshot_row_cto "Aslan-Verse-Web" "$ARC_VERS_REPO" "실험형 멀티 페르소나 조직 플랫폼")"
  if [[ "$arc_verse" == "(not found)" && -f "$aslan_verse_plan" ]]; then
    arc_verse="$(sanitize_md_cell "$(sed -n '1,30p' "$aslan_verse_plan" | tr '\n' ' ')")"
  fi

  if [[ "$arc_postpoint" == "(not found)" ]]; then
    arc_postpoint="문서 근거 미비"
  fi

  cto_key_guard="$(rg -n -g '*.kt' -g '*.java' -m 1 'class .*Guard|class .*Hook|BlockRate' "$ARC_REACTOR_REPO" 2>/dev/null | head -n 1 | tr '\n' ' ' | sed 's/|/｜/g')"
  cto_key_policy="$(rg -n -g '*.kt' -g '*.java' -m 1 'ToolApprovalPolicy|maxToolCalls|max-tools-per-request|mcp/servers|ARC_REACTOR_MCP_ALLOWED_SERVER_NAMES' "$ARC_REACTOR_REPO/arc-core/src/main" "$ARC_REACTOR_REPO/arc-core/src/test" "$ARC_REACTOR_REPO/helm" 2>/dev/null | head -n 1 | tr '\n' ' ' | sed 's/|/｜/g')"

  cto_change_block_file="$block_file.cto-change"
  cto_control_point_file="$block_file.cto-control"

  : > "$cto_change_block_file"
  while IFS='|' read -r name path role; do
    [[ -z "$path" ]] && continue
    local branch="N/A"
    local commit_count=0
    if [[ -d "$path" ]] && git -C "$path" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
      branch="$(git -C "$path" rev-parse --abbrev-ref HEAD 2>/dev/null || echo "N/A")"
      commit_count="$(git -C "$path" log --since="${ANALYSIS_WINDOW_MINUTES} minutes ago" --oneline --max-count=50 2>/dev/null | wc -l | tr -d ' ')"
    fi
    echo "- ${name} (${role}) : branch ${branch}, 최근 ${ANALYSIS_WINDOW_MINUTES}분 변경 ${commit_count}건" >> "$cto_change_block_file"
  done <<'EOF'
arc-reactor|/Users/jinan/ai/arc-reactor|핵심 런타임
aslan-iam|/Users/jinan/ai/aslan-iam|중앙 인증(IAM)
swagger-mcp-server|/Users/jinan/ai/swagger-mcp-server|API MCP
atlassian-mcp-server|/Users/jinan/ai/atlassian-mcp-server|Atlassian MCP
clipping-mcp-server|/Users/jinan/ai/clipping-mcp-server|Clipping MCP
arc-reactor-admin|/Users/jinan/ai/arc-reactor-admin|운영 UI
arc-reactor-web|/Users/jinan/ai/arc-reactor-web|웹 채널
arc-reactor/arc-web|/Users/jinan/ai/arc-reactor/arc-web|채팅/REST 모듈
arc-reactor/arc-slack|/Users/jinan/ai/arc-reactor/arc-slack|슬랙 모듈
Aslan-Verse-Web|/Users/jinan/ai/Aslan-Verse-Web|실험형 조직 플랫폼
EOF

  cat > "$cto_control_point_file" <<'EOF'
- 리스크: 외부 MCP/챗봇 의존도 증가 시 장애 영향면이 커질 수 있어 실패 격리(Guard/Hook/Timeout)와 운영 알림을 선행해야 합니다.
- 기대효과: 중앙 인증 분리 + 표준 MCP 등록 흐름으로 팀별 Agent 실험(Aslan-Verse-Web)과 비즈니스 채널을 분리 배포할 수 있습니다.
- 의사결정 포인트(3개): 
  - 1차 8주 내 도입 범위를 MCP/관리자 UI와 웹 채널에 한정해 안정성 우선 구축할지 결정
  - 가드 정책(도구 승인/속도 제한/오류 격리)은 fail-close를 고정할지, 최소 기능 집합부터 단계 적용할지 결정
  - 운영 KPI(일일 요청 수, 도구 실패율, 정책 우회율) 기준으로 점진 확장 여부를 결정
EOF

cat > "$block_file" <<'EOF'
- 마지막 동기화: __RUN_TS__
- 분석 범위: /Users/jinan/ai 내 아슬란 생태계 프로젝트 8개 (arc-reactor, aslan-iam, swagger-mcp-server, atlassian-mcp-server, clipping-mcp-server, arc-reactor-admin, arc-reactor-web, Aslan-Verse-Web)
- 생성 방식: README/ECOSYSTEM.md 기반 정합성 스냅샷 + git 메타데이터

### 0) 작성 규칙(문체/근거/의사결정)

- 톤: CTO 의사결정 보고용 한국어 존댓말(입니다/됩니다) 사용
- 근거 우선: 각 주장 끝에 구현 파일 경로(가능 시 라인) 또는 git 상태 근거를 붙입니다.
- 요약 우선: 비즈니스 영향 → 운영 리스크 → 실행 결정의 3단 구조를 유지합니다.
- 미확인 항목 표기: “미확인(운영 연동/실행 검증 필요)”로 분리합니다.
- 문체: 과장 표현 없이 확인된 근거 기반으로만 기술합니다.

### 1) CTO 의사결정 요약

- 판단 프레임: arc-reactor 중심으로 IAM 인증, MCP 동적 연동, 채널 확장, 관리자 제어를 묶는 중앙 제어형 아키텍처입니다.
- 현재 판단 근거: `POST /api/mcp/servers` 동적 등록 체계, Arc Reactor MCP allowlist, aslan-iam 공개키 기반 인증, arc-reactor-admin 운영 콘솔, 웹/슬랙 채널 경로가 문서 및 코드에 존재합니다.
- 의사결정 포인트: 우선 도입 범위를 “운영 안정화가 검증된 핵심 경로”로 좁혀 단계적으로 롤아웃하는 쪽이 리스크 최소화가 유리합니다.

### 1-1) 아슬란 통합 철학(CTO 판단 포인트)

- 아슬란의 목표는 흩어진 업무를 하나의 운영 지점으로 정렬하는 것입니다. 인증·커뮤니티·클리핑·향후 확장 서비스가 Reactor를 통해 같은 정책 언어로 연결되면, 신규 서비스 오픈 속도는 올라가고 통제면은 유지됩니다.
- 근거:
  - 인증은 `aslan-iam`이 중앙에서 처리하고, Reactor는 인증 토큰/권한 체계를 전파해 사용/도구 레이어에서 재검증합니다.
  - 기능 연동은 MCP 등록 API(`POST /api/mcp/servers`)와 allowlist 기반 제어로 표준화되어 있어 신규 외부 서비스의 편입 절차가 단순합니다.
  - 운영/보안은 `arc-reactor-admin`와 Guard/Hook 정책으로 같은 프레임에서 감시할 수 있어 서비스별 이기종 운영의 위험을 줄입니다.
  - Reactor Work/Workflow 빌더 전략을 적용하면, 승인/감사/비용 통제 지점이 내장된 업무 흐름을 팀 단위로 템플릿화해 반복 구축 비용을 줄일 수 있습니다.
- 제안 우선순위:
  - 1단계: 핵심(인증·웹·MCP·운영 UI) 우선 통합
  - 2단계: 커뮤니티/클리핑/협업형 MCP를 동일 정책 템플릿으로 연결
  - 3단계: 신규 서비스별 팀형 리액터(페르소나) 병렬 시범 운영 후 단계적 확장
- 경영 판단: “모든 신규 서비스는 Reactor 기준선 + 독립 비즈니스 기능”의 구조로 설계하면, 기술부채보다 제어력(컴플라이언스·보안·운영 가시성)을 먼저 확보할 수 있습니다.

### 2) 프로젝트 상태 스냅샷

| 프로젝트 | 경로 | 역할 | 상태 | README 제목 | 브랜치 | 커밋 | 워크트리 |
|---|---|---|---|---|---|---|---|
__CTO_ROW_ARC_REACTOR__
__CTO_ROW_ASLAN_IAM__
__CTO_ROW_SWAGGER__
__CTO_ROW_ATLASSIAN__
__CTO_ROW_CLIPPING__
__CTO_ROW_ADMIN__
__CTO_ROW_ARC_WEB_MODULE__
__CTO_ROW_ARC_SLACK_MODULE__
__CTO_ROW_WEB__
__CTO_ROW_ASLAN_VERSE__

### 3) CTO 운영 관점 정합성 점검

- 핵심 연동 점검: __ARC_POSTPOINT__
- 보안/정책 게이트 점검: __ARC_ALLOWLIST__
- API/토큰 운영 포인트: __ARC_OPS__
- 인증 연동 점검: __ARC_IAM__
- 실험 플랫폼 연계: __ARC_VERSE__

### 4) 핵심 제어 포인트(근거 기반)

__CTO_CONTROL_POINTS__

### 5) 2분 주기 변경 감시(요약)

__CTO_CHANGE_SUMMARY__
EOF

  cto_key_guard="${cto_key_guard:-"(not found)"}"
  cto_key_policy="${cto_key_policy:-"(not found)"}"

  awk -v run_ts="$run_ts" \
    -v arc_postpoint="$arc_postpoint" \
    -v arc_allowlist="$arc_allowlist" \
    -v arc_ops="$arc_ops" \
    -v arc_iam="$arc_iam" \
    -v arc_verse="$arc_verse" \
    -v row_arc_reactor="$row_arc_reactor" \
    -v row_aslan_iam="$row_aslan_iam" \
    -v row_swagger="$row_swagger" \
    -v row_atlassian="$row_atlassian" \
    -v row_clipping="$row_clipping" \
    -v row_admin="$row_admin" \
    -v row_arc_web_module="$row_arc_web_module" \
    -v row_arc_slack_module="$row_arc_slack_module" \
    -v row_web="$row_web" \
    -v row_aslan_verse="$row_aslan_verse" \
    -v cto_control_point_file="$cto_control_point_file" \
    -v cto_change_block_file="$cto_change_block_file" \
    -v cto_key_guard="$cto_key_guard" \
    -v cto_key_policy="$cto_key_policy" \
    '
      function emit_file(path,    line) {
        while ((getline line < path) > 0) {
          print line
        }
        close(path)
      }
      {
        gsub("__RUN_TS__", run_ts)
        gsub("__ARC_POSTPOINT__", arc_postpoint)
        gsub("__ARC_ALLOWLIST__", arc_allowlist)
        gsub("__ARC_OPS__", arc_ops)
        gsub("__ARC_IAM__", arc_iam)
        gsub("__ARC_VERSE__", arc_verse)
        gsub("__CTO_ROW_ARC_REACTOR__", row_arc_reactor)
        gsub("__CTO_ROW_ASLAN_IAM__", row_aslan_iam)
        gsub("__CTO_ROW_SWAGGER__", row_swagger)
        gsub("__CTO_ROW_ATLASSIAN__", row_atlassian)
        gsub("__CTO_ROW_CLIPPING__", row_clipping)
        gsub("__CTO_ROW_ADMIN__", row_admin)
        gsub("__CTO_ROW_ARC_WEB_MODULE__", row_arc_web_module)
        gsub("__CTO_ROW_ARC_SLACK_MODULE__", row_arc_slack_module)
        gsub("__CTO_ROW_WEB__", row_web)
        gsub("__CTO_ROW_ASLAN_VERSE__", row_aslan_verse)
        if ($0 == "__CTO_CONTROL_POINTS__") {
          print "- 가드/보안 제어 근거: " cto_key_guard
          print "- 운영 정책 제어 근거: " cto_key_policy
          emit_file(cto_control_point_file)
          next
        }
        if ($0 == "__CTO_CHANGE_SUMMARY__") {
          emit_file(cto_change_block_file)
          next
        }
        print
      }' "$block_file" > "$block_file.tmp"
  mv "$block_file.tmp" "$block_file"
  rm -f "$cto_change_block_file" "$cto_control_point_file"
}

update_marked_blocks() {
  local file_path="$1"
  local block_file="$2"
  local tmp_file
  tmp_file="$(mktemp)"

  awk -v block_file="${block_file:-$BLOCK_FILE_FULL}" '
    function print_block(    line) {
      while ((getline line < block_file) > 0) {
        print line
      }
    }
    {
      if ($0 == "<!-- AUTO-ANALYSIS-BLOCK-START -->") {
        print
        print_block()
        in_block = 1
        next
      }
      if (in_block == 1 && $0 == "<!-- AUTO-ANALYSIS-BLOCK-END -->") {
        in_block = 0
        print
        next
      }
      if (in_block == 1) {
        next
      }
      print
    }
  ' "$file_path" > "$tmp_file"
  mv "$tmp_file" "$file_path"
}

render_pdf() {
  local source_md="$1"
  local output_pdf="$2"

  local base_name
  base_name="$(basename "$source_md" .md)"
  local html_body="$TMP_DIR/${base_name}.body.html"
  local html_file="$TMP_DIR/${base_name}.html"

  cd "$ROOT_DIR"
  if command -v marked >/dev/null 2>&1; then
    marked "$source_md" > "$html_body"
  else
    npx --yes marked "$source_md" > "$html_body"
  fi

  cat > "$html_file" <<'EOF'
<!doctype html>
<html lang="ko">
<head>
  <meta charset="UTF-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1.0" />
  <title>Document</title>
  <style>
    :root { color: #111827; font-family: "Apple SD Gothic Neo","Malgun Gothic","Noto Sans KR",Arial,sans-serif; }
    body { margin: 24px; font-size: 13px; line-height: 1.55; }
    h1 { font-size: 30px; line-height: 1.2; }
    h2 { margin-top: 28px; font-size: 20px; }
    h3 { font-size: 15px; }
    pre { background: #f6f8fa; padding: 12px; border-radius: 6px; overflow: auto; }
    code { font-size: 12px; }
    table { width: 100%; border-collapse: collapse; margin: 8px 0; }
    th, td { border: 1px solid #d1d5db; padding: 6px 8px; text-align: left; }
    th { background: #f3f4f6; }
    ul, ol { padding-left: 20px; }
    p, li { page-break-inside: avoid; }
  </style>
</head>
<body>
EOF
  cat "$html_body" >> "$html_file"
  cat >> "$html_file" <<'EOF'
</body>
</html>
EOF

  if [[ -x "$PLAYWRIGHT_DIR/node_modules/.bin/playwright" ]]; then
    (cd "$PLAYWRIGHT_DIR" && ./node_modules/.bin/playwright pdf "file://$html_file" "$output_pdf" --paper-format=A4)
  else
    (cd "$PLAYWRIGHT_DIR" && npx --yes playwright pdf "file://$html_file" "$output_pdf" --paper-format=A4)
  fi
}

log() {
  echo "[$(date '+%Y-%m-%d %H:%M:%S %z')] $*"
}

should_generate_hourly_pdfs() {
  [[ "${FORCE_PDF:-}" == "1" ]] && return 0
  [[ "$(date '+%M')" == "00" ]]
}

{
  exec 9>"$LOCK_FILE"
  if command -v flock >/dev/null 2>&1; then
    if ! flock -n 9; then
      log "Another generator instance is already running. Skip."
      exit 0
    fi
  fi

  write_full_analysis_block "$BLOCK_FILE_FULL"
  write_cto_analysis_block "$BLOCK_FILE_CTO"
  update_marked_blocks "$FULL_DOC" "$BLOCK_FILE_FULL"
  update_marked_blocks "$CTO_DOC" "$BLOCK_FILE_CTO"
  log "Updated auto-analysis blocks in docs"

  if should_generate_hourly_pdfs; then
    render_pdf "$FULL_DOC" "$FULL_PDF"
    log "Generated: $FULL_PDF"

    render_pdf "$CTO_DOC" "$CTO_PDF"
    log "Generated: $CTO_PDF"
  else
    log "PDF generation skipped (next run at :00)."
  fi

  log "Done"
} | tee -a "$LOG_FILE"
