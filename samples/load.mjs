#!/usr/bin/env node
/**
 * 并发压测：固定并发数打读接口，统计 QPS / p50 / p95 / p99。
 *
 *   node samples/load.mjs http://127.0.0.1:18080 50 10
 *                                   baseUrl      并发 秒
 *
 * 为什么不用 wrk：这里要顺带测「登录 + 带 Bearer 头」的真实路径，
 * 而且要区分 2xx 与业务错误码，Node 的 fetch 足够，也不用装东西。
 * 每个 worker 自己保持一条 keep-alive 连接，避免 TCP 握手把数字压低。
 */
const base = process.argv[2] ?? 'http://127.0.0.1:18080';
const concurrency = Number(process.argv[3] ?? 50);
const seconds = Number(process.argv[4] ?? 10);
const path = process.env.LOAD_PATH ?? '/api/dashboard/1';

const health = await fetch(`${base}/api/health`).then(r => r.json()).catch(() => null);
if (!health) {
  console.error(`× 连不上 ${base}，先启动应用`);
  process.exit(1);
}

const login = await fetch(`${base}/api/auth/demo`, { method: 'POST' }).then(r => r.json());
const token = login?.data?.token;
if (!token) {
  console.error('× 登录失败', login);
  process.exit(1);
}

const lat = [];
let ok = 0;
let bad = 0;
const statusCount = {};
const deadline = Date.now() + seconds * 1000;

async function worker() {
  while (Date.now() < deadline) {
    const t0 = performance.now();
    try {
      const res = await fetch(`${base}${path}`, { headers: { Authorization: `Bearer ${token}` } });
      const body = await res.json();
      const dt = performance.now() - t0;
      lat.push(dt);
      statusCount[res.status] = (statusCount[res.status] ?? 0) + 1;
      if (res.ok && body.code === 0) ok++;
      else bad++;
    } catch {
      lat.push(performance.now() - t0);
      bad++;
      statusCount['ERR'] = (statusCount['ERR'] ?? 0) + 1;
    }
  }
}

// 预热，避免把 JIT 与连接建立算进统计
await Promise.all(Array.from({ length: concurrency }, () => fetch(`${base}${path}`, {
  headers: { Authorization: `Bearer ${token}` },
}).then(r => r.arrayBuffer())));

const started = Date.now();
await Promise.all(Array.from({ length: concurrency }, worker));
const elapsed = (Date.now() - started) / 1000;

lat.sort((a, b) => a - b);
const pick = p => lat[Math.min(lat.length - 1, Math.floor(lat.length * p))];

console.log(`并发 ${concurrency} / ${seconds}s / ${path}`);
console.log(`请求 ${lat.length}  成功 ${ok}  失败 ${bad}  状态 ${JSON.stringify(statusCount)}`);
console.log(`QPS ${(lat.length / elapsed).toFixed(0)}`);
console.log(`p50 ${pick(0.50).toFixed(1)}ms  p95 ${pick(0.95).toFixed(1)}ms  p99 ${pick(0.99).toFixed(1)}ms  max ${lat[lat.length - 1].toFixed(1)}ms`);
