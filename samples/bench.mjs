// 基准脚本：① 求解端到端耗时 ② 培养方案上传->解析完成耗时
// 用法：node samples/bench.mjs [baseUrl] [iterations]
// 说明：单进程长连接，避免 curl 进程启动开销把测量结果撑大。
const BASE = process.argv[2] || "http://127.0.0.1:18080";
const N = Number(process.argv[3] || 30);

const login = await (await fetch(`${BASE}/api/auth/demo`, { method: "POST" })).json();
const token = login.data.token;
const H = { Authorization: `Bearer ${token}` };

const pct = (arr, p) => arr[Math.min(arr.length - 1, Math.floor(arr.length * p))];

async function waitJob(jobId, timeoutMs = 30000) {
  const t0 = performance.now();
  for (;;) {
    const job = await (await fetch(`${BASE}/api/jobs/${jobId}`, { headers: H })).json();
    const s = job.data.status;
    if (s === "SUCCEEDED" || s === "FAILED" || s === "DEAD") return performance.now() - t0;
    if (performance.now() - t0 > timeoutMs) throw new Error(`job ${jobId} 超时`);
    await new Promise((r) => setTimeout(r, 5));
  }
}

// ① 求解：提交 -> 出方案
const solveTimes = [];
for (let i = 0; i < N; i++) {
  const t0 = performance.now();
  const r = await (await fetch(`${BASE}/api/plans`, {
    method: "POST",
    headers: { ...H, "Content-Type": "application/json" },
    body: JSON.stringify({ programId: 1, semesters: 8, maxCreditsPerTerm: 25, planCount: 3 }),
  })).json();
  await waitJob(r.data.jobId);
  solveTimes.push(performance.now() - t0);
}
solveTimes.sort((a, b) => a - b);
console.log(`求解 ${N} 次：p50=${pct(solveTimes, 0.5).toFixed(0)}ms  p95=${pct(solveTimes, 0.95).toFixed(0)}ms  max=${solveTimes.at(-1).toFixed(0)}ms`);

// ② 解析：上传 -> 任务完成（每次内容不同，避免命中幂等键）
const sample = await (await import("node:fs/promises")).readFile("samples/program-software-engineering.txt", "utf8");
const parseTimes = [];
for (let i = 0; i < N; i++) {
  const fd = new FormData();
  fd.append("file", new Blob([`${sample}\n附注 bench ${Date.now()} ${i}\n`], { type: "text/plain" }), `bench-${i}.txt`);
  const t0 = performance.now();
  const r = await (await fetch(`${BASE}/api/programs`, { method: "POST", headers: H, body: fd })).json();
  if (r.code !== 0) throw new Error("上传失败: " + JSON.stringify(r).slice(0, 200));
  await waitJob(r.data.jobId);
  parseTimes.push(performance.now() - t0);
}
parseTimes.sort((a, b) => a - b);
console.log(`解析 ${N} 次：p50=${pct(parseTimes, 0.5).toFixed(0)}ms  p95=${pct(parseTimes, 0.95).toFixed(0)}ms  max=${parseTimes.at(-1).toFixed(0)}ms`);
