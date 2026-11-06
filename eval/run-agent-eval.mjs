#!/usr/bin/env node
// Agent 模式的评测：逐条提问，记录模型实际调用了哪些工具、几轮、多久、答了什么。
// 用法：node eval/run-agent-eval.mjs http://127.0.0.1:18080 eval/agent-agent.json
import { readFileSync, writeFileSync } from 'node:fs';

const base = process.argv[2] || 'http://127.0.0.1:18080';
const outFile = process.argv[3] || 'eval/agent-agent.json';
const cases = JSON.parse(readFileSync('eval/agent-cases.json', 'utf8'));

const login = await fetch(`${base}/api/auth/demo`, { method: 'POST' });
const { data: auth } = await login.json();
const token = auth.token;
const headers = { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` };

// 拿一个方案 id
const programs = await (await fetch(`${base}/api/programs`, { headers })).json();
const programId = programs.data[0].id;

async function ask(question) {
  const started = Date.now();
  const res = await fetch(`${base}/api/qa/stream`, {
    method: 'POST',
    headers,
    body: JSON.stringify({ programId, question }),
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${await res.text()}`);
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
    ms: Date.now() - started,
    engine: parsed.engine || '',
    steps: parsed.steps || [],
    citations: parsed.citations || [],
    text: text.trim(),
  };
}

const results = [];
for (const c of cases) {
  try {
    const r = await ask(c.question);
    results.push({
      id: c.id,
      question: c.question,
      expectTools: c.expectTools,
      expectFacts: c.expectFacts || [],
      tools: r.steps.map((s) => s.tool),
      steps: r.steps,
      ms: r.ms,
      engine: r.engine,
      citations: r.citations.length,
      text: r.text,
    });
    console.log(`${c.id.padEnd(24)} ${String(r.ms).padStart(6)}ms  工具=[${r.steps.map((s) => s.tool).join(', ')}]`);
  } catch (e) {
    results.push({ id: c.id, question: c.question, expectTools: c.expectTools, error: String(e) });
    console.log(`${c.id.padEnd(24)} ERROR ${e}`);
  }
}

writeFileSync(outFile, JSON.stringify({ base, programId, results }, null, 2));
console.log(`\n写入 ${outFile}（${results.length} 条）`);
