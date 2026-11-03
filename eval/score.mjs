#!/usr/bin/env node
/**
 * 打分：把 run-eval.mjs 导出的结果和 labels.json 比，输出字段级准确率。
 *
 *   node eval/score.mjs eval/pred-rule.json eval/pred-llm.json
 *
 * 指标口径（都按「每份文档」算，最后取平均）：
 *   课程识别  P/R/F1 —— 课程代码集合的精确率/召回率
 *   学分准确率      —— 识别对的课里，学分也相等的比例
 *   模块准确率      —— 模块归一化到 code 后相等的比例
 *   先修边 F1       —— (课程, 先修) 关系对，多写少写都算错
 *   总学分          —— 完全相等；文档里没写总学分的变体不计入
 */
import fs from 'node:fs';

const files = process.argv.slice(2);
if (files.length === 0) {
  console.error('用法：node eval/score.mjs <pred.json> [pred2.json ...]');
  process.exit(1);
}

const labels = JSON.parse(fs.readFileSync('eval/labels.json', 'utf8'));

function prf(tp, fp, fn) {
  const p = tp + fp === 0 ? 0 : tp / (tp + fp);
  const r = tp + fn === 0 ? 0 : tp / (tp + fn);
  const f = p + r === 0 ? 0 : (2 * p * r) / (p + r);
  return { p, r, f };
}

function scoreOne(label, detail) {
  const truth = label.courses;
  const got = detail?.courses ?? [];
  const truthCodes = new Set(truth.map(c => c.code));
  const gotCodes = new Set(got.map(c => c.code));

  let tp = 0;
  for (const code of truthCodes) if (gotCodes.has(code)) tp++;
  const codePrf = prf(tp, gotCodes.size - tp, truthCodes.size - tp);

  const gotByCode = new Map(got.map(c => [c.code, c]));
  let creditRight = 0;
  let moduleRight = 0;
  let matched = 0;
  for (const t of truth) {
    const g = gotByCode.get(t.code);
    if (!g) continue;
    matched++;
    if (Math.abs(g.credits - t.credits) < 1e-6) creditRight++;
    if (normalizeModule(g.module, detail?.modules) === t.module) moduleRight++;
  }

  const truthEdges = new Set();
  for (const c of truth) for (const p of c.prereqs) truthEdges.add(`${c.code}<-${p}`);
  const gotEdges = new Set();
  for (const c of got) for (const p of c.prereqs ?? []) gotEdges.add(`${c.code}<-${p}`);
  let edgeTp = 0;
  for (const e of truthEdges) if (gotEdges.has(e)) edgeTp++;
  const edgePrf = prf(edgeTp, gotEdges.size - edgeTp, truthEdges.size - edgeTp);

  return {
    id: label.id,
    codeP: codePrf.p, codeR: codePrf.r, codeF: codePrf.f,
    credits: matched ? creditRight / matched : 0,
    module: matched ? moduleRight / matched : 0,
    moduleStated: label.modulesStated,
    edgeF: edgePrf.f,
    total: label.totalCredits == null ? null : Math.abs((detail?.totalCredits ?? -1) - label.totalCredits) < 1e-6,
    school: (detail?.school ?? '').includes(label.school),
    major: (detail?.major ?? '').includes(label.major),
    ms: null,
  };
}

/** 模型/规则可能返回模块名，也可能返回 code；统一成 code 再比 */
function normalizeModule(value, modules) {
  if (!value) return '';
  const v = String(value).trim();
  // 先查固定映射：campus 文档里常只写中文模块名（「一、通识教育（最低 24 学分）」），
  // 这时抽取结果里的 code 本身就是中文名，若先按程序 modules 匹配，
  // 会把「通识教育」当成一个独立 code 返回，和标签里的 GENERAL 对不上。
  const table = { GENERAL: '通识教育', MATH: '数学与自然科学', MAJOR: '专业核心', PRACTICE: '实践环节' };
  for (const [code, name] of Object.entries(table)) if (v === name || v === code) return code;
  const hit = (modules ?? []).find(m => m.code === v || m.name === v);
  if (hit) return normalizeModule(hit.name, []) || hit.code;
  return v;
}

const all = {};
for (const file of files) {
  const preds = JSON.parse(fs.readFileSync(file, 'utf8'));
  const byId = new Map(preds.map(p => [p.id, p]));
  const rows = labels.map(label => {
    const p = byId.get(label.id);
    const s = scoreOne(label, p?.detail);
    s.ms = p?.ms ?? 0;
    s.status = p?.status ?? 'MISSING';
    return s;
  });
  all[file] = rows;
}

const fmt = v => (typeof v === 'number' ? (v * 100).toFixed(1) + '%' : v);
const names = Object.keys(all);

console.log('| 文档 | ' + names.map(n => shortName(n)).map(n => `${n} 课程F1`).join(' | ') + ' |');
console.log('| --- |' + names.map(() => ' --- |').join(''));

for (let i = 0; i < labels.length; i++) {
  const cells = names.map(n => fmt(all[n][i].codeF));
  console.log(`| ${labels[i].id} | ${cells.join(' | ')} |`);
}

function shortName(f) {
  return f.replace(/^.*pred-/, '').replace(/\.json$/, '');
}

const avg = arr => arr.reduce((a, b) => a + b, 0) / arr.length;

console.log('\n| 指标 | ' + names.map(shortName).join(' | ') + ' |');
console.log('| --- |' + names.map(() => ' --- |').join(''));

const metrics = [
  ['课程识别 P', r => avg(r.map(x => x.codeP))],
  ['课程识别 R', r => avg(r.map(x => x.codeR))],
  ['课程识别 F1', r => avg(r.map(x => x.codeF))],
  ['学分准确率', r => avg(r.map(x => x.credits))],
  ['模块准确率*', r => avg(r.filter(x => x.moduleStated).map(x => x.module))],
  ['先修边 F1', r => avg(r.map(x => x.edgeF))],
  ['总学分命中', r => avg(r.filter(x => x.total !== null).map(x => (x.total ? 1 : 0)))],
  ['学校命中', r => avg(r.map(x => (x.school ? 1 : 0)))],
  ['专业命中', r => avg(r.map(x => (x.major ? 1 : 0)))],
  ['平均耗时', r => avg(r.map(x => x.ms))],
];

for (const [name, fn] of metrics) {
  const cells = names.map(n => {
    const v = fn(all[n]);
    return name === '平均耗时' ? Math.round(v) + ' ms' : fmt(v);
  });
  console.log(`| ${name} | ${cells.join(' | ')} |`);
}

const failed = {};
for (const n of names) {
  const bad = all[n].filter(x => x.status !== 'SUCCEEDED').map(x => x.id);
  if (bad.length) failed[shortName(n)] = bad;
}
console.log('\n失败任务：' + (Object.keys(failed).length ? JSON.stringify(failed) : '无'));
console.log('* 模块准确率只统计文档里写了模块的 6 份（table / sections），另 6 份文档根本没写模块。');
