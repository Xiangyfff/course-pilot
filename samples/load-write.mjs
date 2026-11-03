// 写路径压测：上传培养方案（异步解析） + 提交求解（异步求解），测三段时间。
//
//   提交延迟 = POST 返回耗时（用户点完按钮看到「已提交」的时间）
//   排队时延 = job.started_at - job.created_at（任务在队列里等了多久）
//   端到端   = 提交到任务结束
//
// 用法：node samples/load-write.mjs [baseUrl] [并发] [总任务数] [并发模式]
//   node samples/load-write.mjs http://127.0.0.1:18080 20 20
//
// 为什么每份文档要加一行唯一的「备注」：
// ProgramService 对相同内容做 hash 复用（findRecentByHash），内容一样只会复用一个 program/job。
// 压测要的是「N 个独立任务」，所以每份内容必须真的不同；顺带也验证了 hash 去重确实生效。

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const here = dirname(fileURLToPath(import.meta.url));
const base = (process.argv[2] ?? 'http://127.0.0.1:18080').replace(/\/$/, '');
const concurrency = Number(process.argv[3] ?? 20);
const total = Number(process.argv[4] ?? 20);
const programText = readFileSync(join(here, 'program-software-engineering.txt'), 'utf8');

function pct(arr, p) {
  if (!arr.length) return 0;
  const s = [...arr].sort((a, b) => a - b);
  return s[Math.min(s.length - 1, Math.floor(p * (s.length - 1)))];
}
const ms = (v) => `${v.toFixed(0)}ms`;
const summary = (label, arr) =>
  `${label.padEnd(22)} n=${String(arr.length).padStart(2)}  p50=${ms(pct(arr, 0.5)).padStart(6)}  p95=${ms(pct(arr, 0.95)).padStart(6)}  p99=${ms(pct(arr, 0.99)).padStart(6)}  max=${ms(Math.max(...arr, 0))}`;

async function login() {
  const r = await fetch(`${base}/api/auth/demo`, { method: 'POST' });
  const j = await r.json();
  if (!j?.data?.token) throw new Error(`登录失败: ${JSON.stringify(j)}`);
  return j.data.token;
}

async function api(path, token, init = {}) {
  const r = await fetch(`${base}${path}`, {
    ...init,
    headers: { Authorization: `Bearer ${token}`, ...(init.headers ?? {}) },
    signal: AbortSignal.timeout(150_000),
  });
  const j = await r.json().catch(() => ({}));
  if (!r.ok || j?.code) throw new Error(`${path} -> ${r.status} ${JSON.stringify(j).slice(0, 200)}`);
  return j.data;
}

// 轮询任务到终态，返回 {status, createdAt, startedAt}
async function waitJob(token, jobId) {
  const deadline = Date.now() + 150_000;
  for (;;) {
    const job = await api(`/api/jobs/${jobId}`, token);
    if (['SUCCEEDED', 'FAILED', 'DEAD'].includes(job.status)) return job;
    if (Date.now() > deadline) return { ...job, status: 'TIMEOUT' };
    await new Promise((r) => setTimeout(r, 40));
  }
}

const queueMs = (job) => {
  if (!job.startedAt) return null;
  const started = Date.parse(job.startedAt);
  const created = Date.parse(job.createdAt);
  return Number.isFinite(started) && Number.isFinite(created) ? started - created : null;
};

async function oneTask(token, i) {
  const out = { i, ok: true };
  try {
    const text = `${programText}\n备注：压测样本 ${i} ${Date.now()}-${Math.random().toString(36).slice(2, 8)}\n`;
    const fd = new FormData();
    fd.append('file', new Blob([text], { type: 'text/plain' }), `program-${i}.txt`);

    let t = performance.now();
    const created = await api('/api/programs', token, { method: 'POST', body: fd });
    out.uploadSubmit = performance.now() - t;

    t = performance.now();
    const parseJob = await waitJob(token, created.jobId);
    out.parseE2E = performance.now() - t;
    out.parseQueue = queueMs(parseJob);
    out.parseStatus = parseJob.status;

    t = performance.now();
    const solve = await api('/api/plans', token, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ programId: created.programId }),
    });
    out.solveSubmit = performance.now() - t;

    t = performance.now();
    const solveJob = await waitJob(token, solve.jobId);
    out.solveE2E = performance.now() - t;
    out.solveQueue = queueMs(solveJob);
    out.solveStatus = solveJob.status;
    if (solveJob.status !== 'SUCCEEDED') out.ok = false;
  } catch (e) {
    out.ok = false;
    out.error = String(e.message ?? e);
  }
  return out;
}

async function pool(token, n, jobs) {
  const results = [];
  let next = 0;
  await Promise.all(
    Array.from({ length: n }, async () => {
      for (;;) {
        const i = next++;
        if (i >= jobs) return;
        results.push(await oneTask(token, i));
      }
    }),
  );
  return results;
}

const token = await login();
console.log(`目标 ${base}  并发 ${concurrency}  任务 ${total}\n`);

// 预热：让 JIT / 连接池 / 缓存先热起来，否则第一个任务总是异常慢
await oneTask(token, 0);
console.log('预热完成\n');

const startedAt = Date.now();
const rows = await pool(token, concurrency, total);
const wall = Date.now() - startedAt;

const ok = rows.filter((r) => r.ok);
const failed = rows.filter((r) => !r.ok);
const has = (k) => rows.filter((r) => r[k] != null).map((r) => r[k]);

console.log(summary('上传·提交延迟', has('uploadSubmit')));
console.log(summary('解析·排队时延', has('parseQueue')));
console.log(summary('解析·端到端', has('parseE2E')));
console.log(summary('求解·提交延迟', has('solveSubmit')));
console.log(summary('求解·排队时延', has('solveQueue')));
console.log(summary('求解·端到端', has('solveE2E')));
console.log(
  `\n成功 ${ok.length}/${rows.length}，墙钟 ${(wall / 1000).toFixed(1)}s，` +
    `吞吐 ${(rows.length / (wall / 1000)).toFixed(2)} 任务/s`,
);
if (failed.length) {
  console.log(`失败 ${failed.length} 条：`);
  for (const f of failed.slice(0, 5)) console.log(`  #${f.i} ${f.error ?? f.solveStatus ?? f.parseStatus}`);
}
const parseStatuses = rows.reduce((m, r) => ((m[r.parseStatus ?? 'ERR'] = (m[r.parseStatus ?? 'ERR'] ?? 0) + 1), m), {});
console.log(`解析状态分布：${JSON.stringify(parseStatuses)}`);
