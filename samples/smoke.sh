#!/usr/bin/env bash
# 冒烟测试：把整条链路从登录打到问答，验证接口没有 500。
# 用法：bash samples/smoke.sh [http://127.0.0.1:18080]
set -u
BASE=${1:-http://127.0.0.1:18080}
J='-H Content-Type:application/json'

jget() { sed -n "s/.*\"$1\":\([0-9]*\).*/\1/p" | head -1; }
sjget() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1; }

echo "== 1. 健康检查 =="
curl -s "$BASE/api/health"; echo

echo "== 2. 演示账号登录 =="
AUTH=$(curl -s -X POST "$BASE/api/auth/demo")
echo "$AUTH" | head -c 300; echo
TOKEN=$(echo "$AUTH" | sjget token)
if [ -z "$TOKEN" ]; then echo "!! 登录失败"; exit 1; fi
H="Authorization: Bearer $TOKEN"

echo "== 3. 培养方案列表 =="
PROGRAMS=$(curl -s -H "$H" "$BASE/api/programs")
echo "$PROGRAMS" | head -c 400; echo
PID=$(echo "$PROGRAMS" | jget id)
echo "programId = $PID"

echo "== 4. 方案详情 =="
curl -s -H "$H" "$BASE/api/programs/$PID" | head -c 400; echo

echo "== 5. 概览看板 =="
DASH=$(curl -s -H "$H" "$BASE/api/dashboard/$PID")
echo "$DASH" | head -c 600; echo

echo "== 6. 最近一次方案 =="
curl -s -H "$H" "$BASE/api/plans/latest?programId=$PID" | head -c 300; echo

echo "== 7. 提交求解任务 =="
SOLVE=$(curl -s -X POST -H "$H" $J -d "{\"programId\":$PID}" "$BASE/api/plans")
echo "$SOLVE"; echo
JOB=$(echo "$SOLVE" | jget jobId)
echo "jobId = $JOB"

echo "== 8. SSE 任务进度 =="
curl -sN --max-time 40 -H "$H" "$BASE/api/jobs/$JOB/events" | grep -E '^data:' | tail -5

echo "== 9. 取求解结果 =="
curl -s -H "$H" "$BASE/api/plans/$JOB" | head -c 800; echo

echo "== 10. 问答流式 =="
# 中文请求体走文件：Windows 下 curl 命令行参数会被转成 GBK，服务端按 UTF-8 解析会报错
printf '{"programId":%s,"question":"我还差哪些必修课？"}' "$PID" > /tmp/qa-body.json
curl -sN --max-time 30 -X POST -H "$H" $J \
  --data-binary @/tmp/qa-body.json \
  "$BASE/api/qa/stream" | head -c 800; echo

echo "== 11. 上传示例培养方案 =="
UP=$(curl -s -H "$H" -F "file=@samples/program-software-engineering.txt" "$BASE/api/programs")
echo "$UP"; echo
UPJOB=$(echo "$UP" | jget jobId)
echo "upload jobId = $UPJOB"
curl -sN --max-time 40 -H "$H" "$BASE/api/jobs/$UPJOB/events" | grep -E '^data:' | tail -3

echo "== 12. 上传示例成绩单 =="
curl -s -H "$H" -F "file=@samples/transcript-demo.txt" "$BASE/api/transcripts" | head -c 400; echo

echo "== 13. 重新看概览 =="
curl -s -H "$H" "$BASE/api/dashboard/$PID" | head -c 600; echo
echo "== 完成 =="
