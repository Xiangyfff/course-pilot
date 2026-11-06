#!/usr/bin/env node
// 给 Agent 评测结果打分，并可选地与 classic 基线对比。
// 用法：node eval/score-agent.mjs eval/agent-agent.json [eval/agent-classic.json]
import { readFileSync } from 'node:fs';

const file = process.argv[2];
const baselineFile = process.argv[3];
if (!file) {
  console.error('用法：node eval/score-agent.mjs <agent.json> [classic.json]');
  process.exit(1);
}

const run = JSON.parse(readFileSync(file, 'utf8'));
const results = run.results;

const sameSet = (a, b) => {
  const x = [...new Set(a)].sort().join(',');
  const y = [...new Set(b)].sort().join(',');
  return x === y;
};

const pct = (n, d) => (d === 0 ? '—' : `${((n / d) * 100).toFixed(1)}%`);
const avg = (arr) => (arr.length ? arr.reduce((a, b) => a + b, 0) / arr.length : 0);

const ok = results.filter((r) => !r.error);
const toolOk = ok.filter((r) => sameSet(r.tools || [], r.expectTools || []));
const firstToolOk = ok.filter((r) => (r.tools?.[0] || '') === (r.expectTools?.[0] || ''));
const withSteps = ok.filter((r) => (r.tools || []).length > 0);
const factCases = ok.filter((r) => (r.expectFacts || []).length);
const factOk = factCases.filter((r) => (r.expectFacts || []).every((f) => (r.text || '').includes(f)));
const empty = ok.filter((r) => !r.text || r.text.length < 10);

const stats = {
  cases: results.length,
  errors: results.length - ok.length,
  toolAccuracy: ok.length ? toolOk.length / ok.length : 0,
  firstToolAccuracy: ok.length ? firstToolOk.length / ok.length : 0,
  avgSteps: avg(withSteps.map((r) => r.tools.length)),
  avgMs: Math.round(avg(ok.map((r) => r.ms || 0))),
  factAccuracy: factCases.length ? factOk.length / factCases.length : 0,
  emptyRate: ok.length ? empty.length / ok.length : 0,
};

console.log('== Agent 模式 ==');
console.log(`用例 ${stats.cases}  报错 ${stats.errors}`);
console.log(`工具集合准确率   ${pct(toolOk.length, ok.length)}  (${toolOk.length}/${ok.length})`);
console.log(`首个工具准确率   ${pct(firstToolOk.length, ok.length)}`);
console.log(`平均工具步数     ${stats.avgSteps.toFixed(2)}`);
console.log(`事实命中率       ${pct(factOk.length, factCases.length)}  (${factOk.length}/${factCases.length})`);
console.log(`空回答率         ${pct(empty.length, ok.length)}`);
console.log(`平均耗时         ${stats.avgMs} ms`);

const bad = ok.filter((r) => !sameSet(r.tools || [], r.expectTools || []));
if (bad.length) {
  console.log('\n== 工具选择不符的用例 ==');
  for (const r of bad) {
    console.log(`- ${r.id}: 期望 [${r.expectTools.join(', ')}] 实际 [${r.tools.join(', ')}]  (${r.ms}ms)`);
    console.log(`  答：${(r.text || '').slice(0, 90).replace(/\n/g, ' ')}`);
  }
}

for (const r of ok.filter((x) => (x.expectFacts || []).length && (x.expectFacts || []).some((f) => !(x.text || '').includes(f)))) {
  console.log(`\n事实未命中 ${r.id}: 期望包含 [${r.expectFacts.join(', ')}]`);
  console.log(`  答：${(r.text || '').slice(0, 120).replace(/\n/g, ' ')}`);
}

for (const r of ok.filter((x) => (x.steps || []).some((s) => (s.observation || '').includes('"error"')))) {
  console.log(`\n工具报错 ${r.id}: ${r.steps.filter((s) => (s.observation || '').includes('"error"')).map((s) => s.tool).join(', ')}`);
}

if (baselineFile) {
  const base = JSON.parse(readFileSync(baselineFile, 'utf8')).results;
  const bOk = base.filter((r) => !r.error);
  const bFactCases = bOk.filter((r) => (r.expectFacts || []).length);
  const bFactOk = bFactCases.filter((r) => (r.expectFacts || []).every((f) => (r.text || '').includes(f)));
  const bMs = Math.round(avg(bOk.map((r) => r.ms || 0)));
  const bEmpty = bOk.filter((r) => !r.text || r.text.length < 10);
  console.log('\n== 与 classic 基线对比 ==');
  console.log(`                classic        agent`);
  console.log(`平均耗时        ${String(bMs).padStart(6)} ms    ${String(stats.avgMs).padStart(6)} ms`);
  console.log(`事实命中率      ${pct(bFactOk.length, bFactCases.length).padStart(7)}      ${pct(factOk.length, factCases.length).padStart(7)}`);
  console.log(`空回答率        ${pct(bEmpty.length, bOk.length).padStart(7)}      ${pct(empty.length, ok.length).padStart(7)}`);
  console.log(`平均引用数      ${avg(bOk.map((r) => r.citations || 0)).toFixed(2).padStart(7)}      ${avg(ok.map((r) => r.citations || 0)).toFixed(2).padStart(7)}`);
}
