#!/usr/bin/env node
// Agent 模式评测：每条用例跑 N 次（默认 3），记录工具轨迹、耗时、回答。
// 用法：node eval/run-agent-eval.mjs <baseUrl> <out.json> [repeats]
import { readFileSync, writeFileSync } from 'node:fs';

const base = process.argv[2] || 'http://127.0.0.1:18080';
const outFile = process.argv[3] || 'eval/agent-agent.json';
const repeats = Number(process.argv[4] || 3);
const cases = JSON.parse(readFileSync('eval/agent-cases.json', 'utf8'));

const login = await fetch(`${base}/api/auth/demo`, { method: 'POST' });
const { data: auth } = await login.json();
const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${auth.token}` };

const programs = await (await fetch(`${base}/api/programs`, { headers })).json();
const defaultProgramId = programs.data[0].id;

async function ask(c, round) {
  const started = Date.now();
  const body = { programId: c.programId || defaultProgramId, question: c.question };
  if (Array.isArray(c.history) && c.history.length) body.history = c.history;
  const res = await fetch(`${base}/api/qa/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
  const raw = await res.text();
  const events = [];
  for (const block of raw.split('\n\n')) {
    const name = /^event:(.+)$/m.exec(block)?.[1]?.trim();
    const data = /^data:(.*)$/m.exec(block)?.[1] ?? '';
    if (name) events.push({ name, data });
  }
  const meta = events.find((e) => e.name === 'meta');
  const text = events.filter((e) => e.name === 'delta').map((e) => e.data).join('');
  let parsed = {};
  try { parsed = JSON.parse(meta?.data || '{}'); } catch { /* ignore */ }
  return {
    round,
    ms: Date.now() - started,
    engine: parsed.engine || '',
    tools: (parsed.steps || []).map((s) => s.tool),
    steps: parsed.steps || [],
    citations: (parsed.citations || []).length,
    text: text.trim(),
  };
}

const results = [];
for (const c of cases) {
  const runs = [];
  for (let i = 1; i <= repeats; i++) {
    try {
      runs.push(await ask(c, i));
    } catch (e) {
      runs.push({ round: i, error: String(e), tools: [], text: '', ms: 0 });
    }
  }
  const first = runs[0];
  console.log(`${c.id.padEnd(24)} ${runs.map((r) => (r.error ? 'ERR' : `[${r.tools.join(',')}]`)).join(' | ')}`);
  results.push({
    id: c.id,
    category: c.category || 'unknown',
    question: c.question,
    expectTools: c.expectTools || [],
    expectFacts: c.expectFacts || [],
    runs,
  });
}

writeFileSync(outFile, JSON.stringify({ base, programId: defaultProgramId, repeats, cases: cases.length, results }, null, 2));
console.log(`\n写入 ${outFile}：${results.length} 条 × ${repeats} 次`);
