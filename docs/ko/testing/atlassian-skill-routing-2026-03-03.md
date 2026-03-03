# Atlassian Skill Routing E2E Report

- generatedAt: 1772502988888
- runId: `skill-e2e-1772502884-2824`
- baseUrl: `http://localhost:8080`
- tenantId: `default`
- toolsTotal: 47
- executionOk: 47
- routed: 47
- notRouted: 0
- rateLimited: 0

## Questions And Results

| Tool | Routed | Success | Status | Question | Snippet |
|---|---:|---:|---:|---|---|
| `confluence_create_weekly_auto_summary_page` | Y | Y | 200 | Confluence 주간 자동 요약 페이지 생성해줘. 반드시 `confluence_create_weekly_auto_summary_page` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | Confluence 주간 자동 요약 페이지를 생성하는 과정에서 접근이 거부되었습니다. CONFLUENCE_ALLOWED_SPACE_KEYS 환경 변수에 해당 공간 키가 포함되어 있는지 확인해 주세요. |
| `jira_search_issues` | Y | Y | 200 | JQL `project = DEV ORDER BY created DESC` 기준으로 최근 Jira 이슈를 찾아서 요약해줘. 반드시 `jira_search_issues` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 죄송하지만, Jira 프로젝트에 대한 접근 권한이 없어 최근 Jira 이슈를 검색할 수 없습니다. Jira 관리자에게 문의하여 JIRA_ALLOWED_PROJECT_KEYS 설정을 확인해 주십시오. |
| `work_morning_briefing` | Y | Y | 200 | 오늘 아침 업무 브리핑 만들어줘. 반드시 `work_morning_briefing` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 오늘 아침 업무 브리핑 결과입니다. Jira 연체 이슈 0개, 차단 이슈 0개, PR SLA 위반 0건입니다. 특별한 문제가 감지되지 않았으므로 계획된 업무를 진행하시면 됩니다. |
| `bitbucket_get_pr` | Y | Y | 200 | Bitbucket workspace=jarvis, repository=arc-reactor, prId=1 의 PR 상세를 조회해줘. 반드시 `bitbucket_get_pr` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 죄송합니다. 해당 저장소에 접근할 권한이 없습니다. |
| `confluence_search` | Y | Y | 200 | Confluence에서 이번 주 리포트 관련 페이지를 찾아줘. 반드시 `confluence_search` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | Confluence 검색 결과에 대한 접근 권한이 없는 것 같습니다. Confluence 접근 권한을 확인하거나 다른 도구를 사용해 보세요. |
| `confluence_create_page` | Y | Y | 200 | Confluence에 테스트 페이지 생성해줘. 반드시 `confluence_create_page` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | Confluence에 테스트 페이지를 생성하는 데 실패했습니다. Confluence 스페이스 키가 허용되지 않았습니다. |
| `confluence_create_runbook` | Y | Y | 200 | Confluence spaceId=ENG, title='Arc Reactor Runbook', serviceName='arc-reactor', summary='운영 런북', owner='kim', oncallChannel='#jarvis'로 런북 페이지 생성해줘. 반드시 `confluence_create_runbook` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | {"content":"","success":true,"model":null,"toolsUsed":["confluence_create_runbook"],"errorMessage":null} |
| `jira_transition_issue` | Y | Y | 200 | Jira DEV-123 이슈 상태를 In Progress로 변경 시도해줘. 반드시 `jira_transition_issue` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | Error: Access denied: issue is not in allowed projects. Jira DEV-123 이슈의 상태를 In Progress로 변경할 수 없습니다. |
| `bitbucket_add_pr_comment` | Y | Y | 200 | Bitbucket workspace=jarvis, repository=arc-reactor, prId=1 에 '테스트 코멘트'를 추가해줘. 반드시 `bitbucket_add_pr_comment` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | {'error': 'Access denied: repository is not allowed: arc-reactor (set BITBUCKET_ALLOWED_REPOSITORIES)'}  |
| `confluence_get_page_content` | Y | Y | 200 | Confluence pageId=123456 페이지 본문 내용을 가져와줘. 반드시 `confluence_get_page_content` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 인증에 실패했습니다. API 토큰을 확인해주세요. |
| `jira_update_issue` | Y | Y | 200 | Jira DEV-123 이슈 설명을 업데이트해줘. 반드시 `jira_update_issue` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 이슈 설명 업데이트에 실패했습니다. DEV-123 이슈는 현재 프로젝트에서 수정할 수 없는 것으로 보입니다. 다른 프로젝트에 속한 이슈인지 확인하거나, 올바른 프로젝트 키를 알려주세요. |
| `work_release_readiness_pack` | Y | Y | 200 | releaseName='v1.0.0', jiraProjectKey='DEV', bitbucketWorkspace='jarvis', bitbucketRepoSlug='arc-reactor', confluenceKeyword='release', dueSoonDays=3, reviewSlaHours=24로 릴리즈 준비 상태 패키지 만들어줘. 반드시 `work_release_readiness_pack` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 릴리스 준비 상태 패키지를 생성하는 과정에서 오류가 발생했습니다. Confluence 페이지 ID가 누락되어 작업이 실패했습니다. |
| `confluence_create_weekly_status_report` | Y | Y | 200 | Confluence spaceId=ENG, title='주간 상태 리포트', weekLabel='2026-W10', team='Jarvis', highlights='안정화 진행', plannedWork='MCP 개선', risks='권한 이슈'로 페이지 만들어줘. 반드시 `confluence_create_weekly_status_report` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | {"content":"","success":true,"model":null,"toolsUsed":["confluence_create_weekly_status_report"],"errorMessage":null} |
| `confluence_create_sprint_summary` | Y | Y | 200 | Confluence spaceId=ENG, title='스프린트 요약', sprintName='Sprint-42', period='2026-03-01~2026-03-14', highlights='핵심 기능 완료', risks='일부 지연'으로 요약 페이지 만들어줘. 반드시 `confluence_create_sprint_summary` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | {"content":"","success":true,"model":null,"toolsUsed":["confluence_create_sprint_summary"],"errorMessage":null} |
| `jira_link_issues` | Y | Y | 200 | Jira DEV-123과 DEV-456 이슈를 연관 관계로 연결해줘. 반드시 `jira_link_issues` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | DEV-123 이슈와 DEV-456 이슈를 'Relates' 타입으로 연결했습니다. 하지만 권한 문제로 연결에 실패했습니다. |
| `jira_due_soon_issues` | Y | Y | 200 | 마감 임박 Jira 이슈를 조회해줘. 반드시 `jira_due_soon_issues` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | Jira 프로젝트 설정에 대한 접근 권한이 없어 마감 임박 이슈를 조회할 수 없습니다. Jira 프로젝트 키를 설정하거나, Jira 프로젝트 관리자에게 문의하여 접근 권한을 확인해주세요. |
| `work_delete_briefing_profile` | Y | Y | 200 | 업무 브리핑 프로필 하나 삭제해줘. 반드시 `work_delete_briefing_profile` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | "sample"이라는 이름의 프로필은 존재하지 않아 삭제할 수 없습니다. |
| `work_set_briefing_profile` | Y | Y | 200 | name=default, jiraProjectKey=DEV, bitbucketWorkspace=jarvis, bitbucketRepoSlug=arc-reactor, confluenceKeyword=weekly 로 업무 브리핑 프로필 저장해줘. 반드시 `work_set_briefing_profile` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 'default'라는 이름의 업무 브리핑 프로필이 성공적으로 저장되었습니다. |
| `bitbucket_review_sla_alerts` | Y | Y | 200 | Bitbucket 리뷰 SLA 경고를 점검해줘. 반드시 `bitbucket_review_sla_alerts` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 죄송합니다. Bitbucket workspace ID를 제공해야 `bitbucket_review_sla_alerts` 도구를 호출할 수 있습니다. workspace ID를 알려주시겠어요? |
| `jira_list_projects` | Y | Y | 200 | Jira 프로젝트 목록 보여줘. 반드시 `jira_list_projects` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 현재 Jira 프로젝트 목록은 비어 있습니다. |
| `confluence_generate_weekly_auto_summary_draft` | Y | Y | 200 | Confluence 주간 자동 요약 초안 생성해줘. 반드시 `confluence_generate_weekly_auto_summary_draft` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | Confluence 주간 자동 요약 초안이 생성되었습니다. |
| `confluence_update_page` | Y | Y | 200 | Confluence pageId=123456, version=1, title='E2E Update', body='자동 검증 업데이트'로 페이지를 업데이트해줘. 반드시 `confluence_update_page` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 페이지를 업데이트하지 못했습니다. API 토큰 인증에 실패했습니다. |
| `bitbucket_approve_pr` | Y | Y | 200 | Bitbucket PR 승인 시도해줘. 반드시 `bitbucket_approve_pr` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 워크스페이스 ID를 제공해주시면 PR 승인을 시도하겠습니다. |
| `bitbucket_review_queue` | Y | Y | 200 | Bitbucket 리뷰 대기열 정리해줘. 반드시 `bitbucket_review_queue` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 리뷰 대기열을 정리하려면 Bitbucket 워크스페이스를 제공해야 합니다. |
| `jira_my_open_issues` | Y | Y | 200 | 내가 담당한 Jira 오픈 이슈 목록 알려줘. 반드시 `jira_my_open_issues` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 오픈 이슈 목록을 가져오는 데 실패했습니다. 다시 시도해 주세요. |
| `jira_get_transitions` | Y | Y | 200 | Jira DEV-123에서 가능한 전이 목록 보여줘. 반드시 `jira_get_transitions` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | {"content":"","success":true,"model":null,"toolsUsed":["jira_get_transitions"],"errorMessage":null} |
| `work_list_briefing_profiles` | Y | Y | 200 | 업무 브리핑 프로필 목록을 보여줘. 반드시 `work_list_briefing_profiles` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 현재 사용 가능한 업무 브리핑 프로필은 'default' 하나입니다. |
| `bitbucket_list_prs` | Y | Y | 200 | Bitbucket workspace=jarvis, repository=arc-reactor 의 PR 목록을 조회해줘. 반드시 `bitbucket_list_prs` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | ```json {"bitbucket_list_prs_response": {"result": "Parameter specified as non-null is null: method com.atlassian.mcpserver.tool.bitbucket.BitbucketPRTool.bitbucket_list_prs, parameter state"}} ``` |
| `work_prepare_standup_update` | Y | Y | 200 | jiraProjectKey=DEV, bitbucketWorkspace=jarvis, bitbucketRepoSlug=arc-reactor, confluenceKeyword=weekly 조건으로 스탠드업 업데이트 초안 작성해줘. 반드시 `work_prepare_standup_update` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 이전 호출에서 오류가 발생했기 때문에 스탠드업 업데이트 초안을 작성할 수 없습니다. 오류 메시지는 `NullPointerException`이며, 이는 `work_prepare_standup_update` 도구에서 기대하는 값 중 하나가 null이어서 발생한 것으로 보입니다.  문제를 해결하기 위해 다음 정보와 함께 다시 시도해 주시겠어요?  *   `jiraProjectKey` *   `bitbucketWorkspace` *   `bitbucketRepoSlug` *   `confluenceKeyword`  또한, `work_prepare_standup_update` 도구에 대한 추가 매개변수(예: `daysLookback`, `jiraMaxResults`)가 필요한 경우 제공해 주세요. |
| `confluence_create_meeting_notes` | Y | Y | 200 | Confluence spaceId=ENG, title='주간 회의록', meetingDate='2026-03-03', attendees='kim,lee', agenda='진행상황', decisions='다음주 릴리즈', actionItems='테스트 자동화'로 회의록 페이지 만들어줘. 반드시 `confluence_create_meeting_notes` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | {"content":"","success":true,"model":null,"toolsUsed":["confluence_create_meeting_notes"],"errorMessage":null} |
| `work_release_risk_digest` | Y | Y | 200 | releaseName='v1.0.0', jiraProjectKey='DEV', bitbucketWorkspace='jarvis', bitbucketRepoSlug='arc-reactor', confluenceKeyword='release'로 릴리즈 리스크 다이제스트 만들어줘. 반드시 `work_release_risk_digest` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 음, 죄송하지만 릴리즈 리스크 다이제스트를 생성하는 과정에서 오류가 발생했습니다. 오류 메시지에 따르면 `java.lang.NullPointerException`이 발생했으며, 이는 특정 값이 null이어서 발생한 것으로 보입니다. 이 문제를 해결하기 위해 필요한 정보가 더 있을 수 있으므로, 해당 도구를 다시 시도해 보거나 다른 옵션을 고려해 볼 수 있습니다. |
| `jira_daily_briefing` | Y | Y | 200 | Jira 프로젝트 DEV 기준으로 오늘 일일 브리핑을 만들어줘. 반드시 `jira_daily_briefing` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | {"content":"","success":true,"model":null,"toolsUsed":["jira_daily_briefing"],"errorMessage":null} |
| `confluence_list_spaces` | Y | Y | 200 | Confluence 스페이스 목록 보여줘. 반드시 `confluence_list_spaces` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 인증에 실패했습니다. API 토큰을 확인해 주세요. |
| `jira_create_subtask` | Y | Y | 200 | Jira DEV-123 하위 작업 하나 생성해줘. 반드시 `jira_create_subtask` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | DEV-123 하위 작업 생성 요청이 거부되었습니다. 부모 이슈가 허용된 프로젝트에 속하지 않는 것 같습니다. |
| `confluence_search_by_text` | Y | Y | 200 | Confluence에서 sprint 키워드로 검색해줘. 반드시 `confluence_search_by_text` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | Confluence 스페이스에 접근할 수 없습니다. Confluence 접근 권한을 확인하거나, 사용 가능한 Confluence 스페이스 키를 설정해주세요. |
| `confluence_get_page` | Y | Y | 200 | Confluence pageId=123456 페이지 상세 정보를 조회해줘. 반드시 `confluence_get_page` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 인증에 실패했습니다. API 토큰을 확인해주세요. |
| `jira_search_by_text` | Y | Y | 200 | Jira에서 payment 키워드로 검색해줘. 반드시 `jira_search_by_text` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 'jira' 프로젝트에서 'payment' 키워드로 Jira 이슈를 검색했으나, 해당 프로젝트에 접근 권한이 없어 실패했습니다. 다른 프로젝트로 다시 시도해 주시겠어요? |
| `confluence_create_incident_postmortem` | Y | Y | 200 | Confluence spaceId=ENG, title='장애 포스트모템', incidentDate='2026-03-01', summary='일시 장애', timeline='10:00 감지, 10:30 복구', rootCause='DB 연결 불안정', actionItems='재시도 정책 보강'으로 문서 생성해줘. 반드시 `confluence_create_incident_postmortem` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | {"content":"","success":true,"model":null,"toolsUsed":["confluence_create_incident_postmortem"],"errorMessage":null} |
| `bitbucket_stale_prs` | Y | Y | 200 | Bitbucket workspace=jarvis, repository=arc-reactor, staleDays=7 기준으로 오래된 PR 찾아줘. 반드시 `bitbucket_stale_prs` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | {"content":"","success":true,"model":null,"toolsUsed":["bitbucket_stale_prs"],"errorMessage":null} |
| `jira_add_comment` | Y | Y | 200 | Jira DEV-123 이슈에 테스트 코멘트를 남겨줘. 반드시 `jira_add_comment` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 테스트 코멘트를 남기려고 했으나, 해당 이슈에 접근할 권한이 없어 코멘트를 남길 수 없었습니다. |
| `bitbucket_list_repositories` | Y | Y | 200 | Bitbucket 저장소 목록 보여줘. 반드시 `bitbucket_list_repositories` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 저장소 목록을 보여드리려면 워크스페이스 이름을 알아야 합니다. 워크스페이스 이름을 알려주시겠어요? |
| `jira_create_issue` | Y | Y | 200 | Jira에 테스트 이슈 생성해줘. 반드시 `jira_create_issue` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 테스트 프로젝트에 이슈를 생성하려고 했으나, 접근이 거부되었습니다. 다른 프로젝트를 지정해 주시겠어요? |
| `bitbucket_list_branches` | Y | Y | 200 | Bitbucket 브랜치 목록 보여줘. 반드시 `bitbucket_list_branches` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 죄송합니다. 해당 저장소에 접근할 권한이 없습니다. 다른 저장소 이름을 알려주시겠어요? |
| `jira_blocker_digest` | Y | Y | 200 | Jira 프로젝트 DEV 기준 blocker 이슈 요약 보고서를 만들어줘. 반드시 `jira_blocker_digest` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | {"content":"","success":true,"model":null,"toolsUsed":["jira_blocker_digest"],"errorMessage":null} |
| `jira_assign_issue` | Y | Y | 200 | Jira DEV-123 이슈를 담당자에게 할당해줘. 반드시 `jira_assign_issue` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | DEV-123 이슈를 담당자에게 할당할 수 없습니다. 권한이 없거나 해당 이슈가 허용된 프로젝트에 속해있지 않습니다. |
| `work_action_items_to_jira` | Y | Y | 200 | confluencePageId=123456, jiraProjectKey=DEV, issueType=Task, dryRun=true, maxCreate=5 조건으로 액션 아이템을 Jira 이슈로 변환해줘. 반드시 `work_action_items_to_jira` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | 123456 페이지 ID에 해당하는 Confluence 페이지에서 액션 아이템을 Jira 이슈로 변환하는 작업은 Confluence 페이지 ID가 제공되지 않아 실패했습니다. |
| `jira_get_issue` | Y | Y | 200 | Jira 이슈 DEV-123 내용을 확인해줘. 반드시 `jira_get_issue` 도구를 1회 호출해서 한국어 한 문장으로 결과만 답해줘. | DEV-123 이슈에 접근할 수 없습니다. |
