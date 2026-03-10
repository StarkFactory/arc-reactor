#!/bin/bash

# Arc Reactor — Ralph Loop Runner
# 사용법: ./loop.sh [최대반복횟수]
# 예시:  ./loop.sh 60   (약 5시간)
#         ./loop.sh       (기본값 80회)

MAX_ITERATIONS=${1:-80}
ITERATION=0
LOG_FILE="loop-run.log"
COMPLETION_PROMISE="IMPROVEMENTS COMPLETE"

echo "============================================"
echo " Arc Reactor Autonomous Improvement Loop"
echo " Max iterations: $MAX_ITERATIONS"
echo " Started: $(date)"
echo "============================================" | tee -a "$LOG_FILE"

while [ $ITERATION -lt $MAX_ITERATIONS ]; do
  ITERATION=$((ITERATION + 1))
  echo ""
  echo "--- Iteration $ITERATION / $MAX_ITERATIONS  [$(date '+%H:%M:%S')] ---" | tee -a "$LOG_FILE"

  # dev 브랜치 기반인지 확인
  CURRENT_BRANCH=$(git branch --show-current)
  if [ "$CURRENT_BRANCH" != "dev" ]; then
    echo "[WARN] Not on dev branch (on: $CURRENT_BRANCH). Switching to dev..." | tee -a "$LOG_FILE"
    git checkout dev
  fi

  # Claude 실행 — PROMPT.md를 읽고 작업 수행
  OUTPUT=$(claude --dangerously-skip-permissions --print "$(cat PROMPT.md)" 2>&1)
  EXIT_CODE=$?

  echo "$OUTPUT" | tee -a "$LOG_FILE"

  # 완료 프로미스 감지
  if echo "$OUTPUT" | grep -q "$COMPLETION_PROMISE"; then
    echo ""
    echo "============================================" | tee -a "$LOG_FILE"
    echo " Loop completed after $ITERATION iterations" | tee -a "$LOG_FILE"
    echo " Finished: $(date)"                          | tee -a "$LOG_FILE"
    echo "============================================" | tee -a "$LOG_FILE"
    exit 0
  fi

  # Claude 오류 감지
  if [ $EXIT_CODE -ne 0 ]; then
    echo "[ERROR] Claude exited with code $EXIT_CODE. Retrying next iteration..." | tee -a "$LOG_FILE"
    sleep 10
  fi

done

echo ""
echo "============================================" | tee -a "$LOG_FILE"
echo " Max iterations ($MAX_ITERATIONS) reached"   | tee -a "$LOG_FILE"
echo " Finished: $(date)"                           | tee -a "$LOG_FILE"
echo "============================================" | tee -a "$LOG_FILE"
