#!/usr/bin/env node
// 评测打分 v2：稳定通过率（pass@1 / pass^k）、六类失败归因、按维度分组。
// 用法：node eval/score-agent.mjs eval/agent-agent.json [eval/agent-classic.json]
import { readFileSync } from 'node:fs';

const file = process.argv[2] || 'eval/agent-agent.json';
const classicFile = process.argv[3];
const data = JSON.parse(readFileSync(file, 'utf8'));
const k = data.repeats || 1;

// 期望值以用例文件为准（改期待值不必重跑）
const spec = new Map();
try {
  for (const c of JSON.parse(readFileSync('eval/agent-cases.json', 'utf8'))) spec.set(c.id, c);
} catch { /* 用例文件不在就用结果里记录的 */ }
for (const r of data.results) {
  const s = spec.get(r.id);
  if (s) {
    r.expectTools = s.expectTools || [];
    r.expectFacts = s.expectFacts || [];
    r.toolMatch = s.toolMatch;
    r.category = s.category || r.category;
  }
}

const norm = (s) => String(s || '').replace(/[\s,，、]/g, '');

function judge(c, run) {
  const expected = c.expectTools || [];
  // 同一个工具用不同参数查两次是合理的（问了两个子问题），按「去重后的工具集合」判定，
  // 重复调用次数单独统计，不算失败。
  const actual = [...new Set(run.tools || [])];
  const matchKind = c.toolMatch === 'any' ? 'any' : 'exact';
  let toolOk;
  if (matchKind === 'any') {
    toolOk = actual.every((t) => expected.includes(t));
  } else if (expected.length === 0) {
    toolOk = actual.length === 0;
  } else {
    toolOk = actual.length === expected.length && expected.every((t) => actual.includes(t));
  }
  const text = norm(run.text);
  const missingFacts = (c.expectFacts || []).filter((f) => !text.includes(norm(f)));
  const factOk = missingFacts.length === 0;
  const empty = !String(run.text || '').trim();
  const pass = !run.error && toolOk && factOk && !empty;
  return { pass, toolOk, factOk, missingFacts, empty, actual, expected };
}

function classify(c, run, verdict) {
  if (run.error) return 'error';
  if (verdict.empty) return 'empty_answer';
  if (!verdict.toolOk) {
    const expected = c.expectTools || [];
    const actual = run.tools || [];
    if (expected.length === 0) return 'unnecessary_tool';
    if (actual.length === 0) return 'no_tool';
    if (actual.every((t) => !expected.includes(t))) return 'wrong_tool';
    if (actual.length > expected.length) return 'extra_tool';
    return 'missing_tool';
  }
  if (!verdict.factOk) return 'wrong_fact';
  return 'pass';
}

const perCase = data.results.map((c) => {
  const verdicts = c.runs.map((r) => judge(c, r));
  const passes = verdicts.filter((v) => v.pass).length;
  const reasons = [];
  c.runs.forEach((r, i) => {
    if (!verdicts[i].pass) reasons.push(`${i + 1}:${classify(c, r, verdicts[i])}`);
  });
  return {
    id: c.id,
    category: c.category,
    passes,
    total: c.runs.length,
    passAt1: verdicts[0]?.pass ? 1 : 0,
    passPowK: passes === c.runs.length ? 1 : 0,
    avgSteps: c.runs.reduce((s, r) => s + (r.tools?.length || 0), 0) / c.runs.length,
    avgDup: c.runs.reduce((s, r) => s + ((r.tools?.length || 0) - new Set(r.tools || []).size), 0) / c.runs.length,
    avgMs: Math.round(c.runs.reduce((s, r) => s + (r.ms || 0), 0) / c.runs.length),
    reasons,
  };
});

const pct = (a, b) => (b === 0 ? '—' : `${((a / b) * 100).toFixed(1)}%`);
const n = perCase.length;
const passAt1 = perCase.filter((c) => c.passAt1).length;
const stable = perCase.filter((c) => c.passPowK).length;
const totalRuns = perCase.reduce((s, c) => s + c.total, 0);
const totalPassRuns = perCase.reduce((s, c) => s + c.passes, 0);
const avgMs = Math.round(perCase.reduce((s, c) => s + c.avgMs, 0) / n);
const avgSteps = (perCase.reduce((s, c) => s + c.avgSteps, 0) / n).toFixed(2);

const categories = [...new Set(perCase.map((c) => c.category))];
const byCat = categories.map((cat) => {
  const rows = perCase.filter((c) => c.category === cat);
  return {
    cat,
    count: rows.length,
    passAt1: pct(rows.filter((c) => c.passAt1).length, rows.length),
    stable: pct(rows.filter((c) => c.passPowK).length, rows.length),
    avgMs: Math.round(rows.reduce((s, c) => s + c.avgMs, 0) / rows.length),
  };
});

const allRuns = data.results.flatMap((c) => c.runs.map((r, i) => ({ c, r, v: judge(c, r) })));
const taxonomy = {};
for (const { c, r, v } of allRuns) {
  const key = classify(c, r, v);
  taxonomy[key] = (taxonomy[key] || 0) + 1;
}

console.log(`用例 ${n} 条 × ${k} 次 = ${totalRuns} 次调用\n`);
console.log('总体');
console.log(`  pass@1            ${pct(passAt1, n)}   (${passAt1}/${n})`);
console.log(`  pass^${k}（稳定）     ${pct(stable, n)}   (${stable}/${n})`);
console.log(`  单次通过率        ${pct(totalPassRuns, totalRuns)}   (${totalPassRuns}/${totalRuns})`);
console.log(`  平均工具步数      ${avgSteps}（其中重复调用 ${(perCase.reduce((s, c) => s + c.avgDup, 0) / n).toFixed(2)} 次/题）`);
console.log(`  平均耗时          ${avgMs} ms`);

console.log('\n按维度');
console.log('  维度          用例  pass@1   pass^k   平均耗时');
for (const row of byCat) {
  console.log(`  ${row.cat.padEnd(12)}  ${String(row.count).padEnd(4)}  ${row.passAt1.padEnd(7)}  ${row.stable.padEnd(7)}  ${row.avgMs} ms`);
}

console.log('\n失败归因（按单次调用计）');
const order = ['wrong_tool', 'extra_tool', 'missing_tool', 'no_tool', 'unnecessary_tool', 'wrong_fact', 'empty_answer', 'error'];
for (const key of order) {
  if (taxonomy[key]) console.log(`  ${key.padEnd(17)} ${taxonomy[key]}`);
}
console.log(`  ${'pass'.padEnd(17)} ${taxonomy.pass || 0}`);

const unstable = perCase.filter((c) => !c.passPowK);
if (unstable.length) {
  console.log('\n不稳定的用例');
  for (const c of unstable) {
    console.log(`  ${c.id.padEnd(24)} ${c.passes}/${c.total}  ${c.reasons.join(' ')}`);
  }
}

if (classicFile) {
  const classic = JSON.parse(readFileSync(classicFile, 'utf8'));
  const cRuns = classic.results.flatMap((c) => c.runs);
  const cEmpty = cRuns.filter((r) => !String(r.text || '').trim()).length;
  const cMs = Math.round(cRuns.reduce((s, r) => s + (r.ms || 0), 0) / cRuns.length);
  console.log('\n对照（classic 通道）');
  console.log(`  用例数 ${classic.results.length}，空回答 ${cEmpty}/${cRuns.length}，平均耗时 ${cMs} ms`);
}
