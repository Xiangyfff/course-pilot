#!/usr/bin/env node
/**
 * 跑评测：把 eval/programs/*.txt 逐份上传，等任务完成，导出最终入库的结果。
 *
 *   node eval/run-eval.mjs http://127.0.0.1:18080 eval/pred-llm.json
 *   node eval/run-eval.mjs http://127.0.0.1:18082 eval/pred-rule.json
 *
 * 两个地址分别对应 LLM_PROVIDER=openai-compatible 与 mock 的两个实例，
 * 这样比的是「同一条流水线、不同抽取通道」的最终数据，而不是两个函数。
 */
import fs from 'node:fs';

const base = process.argv[2] ?? 'http://127.0.0.1:18080';
const outFile = process.argv[3] ?? 'eval/pred.json';

const labels = JSON.parse(fs.readFileSync('eval/labels.json', 'utf8'));

const login = await fetch(`${base}/api/auth/demo`, { method: 'POST' }).then(r => r.json());
const token = login?.data?.token;
if (!token) {
  console.error('× 登录失败，应用起来了吗？', login);
  process.exit(1);
}
const auth = { Authorization: `Bearer ${token}` };

const results = [];
for (const label of labels) {
  const buf = fs.readFileSync(label.file);
  const form = new FormData();
  form.append('file', new Blob([buf], { type: 'text/plain' }), `${label.id}.txt`);

  const t0 = Date.now();
  const up = await fetch(`${base}/api/programs`, { method: 'POST', headers: auth, body: form }).then(r => r.json());
  if (up.code !== 0) {
    results.push({ id: label.id, error: up.message });
    console.log(`× ${label.id} 上传失败：${up.message}`);
    continue;
  }
  const { programId, jobId } = up.data;

  let status = 'PENDING';
  for (let i = 0; i < 200 && status !== 'SUCCEEDED' && status !== 'FAILED' && status !== 'DEAD'; i++) {
    await new Promise(r => setTimeout(r, 300));
    const job = await fetch(`${base}/api/jobs/${jobId}`, { headers: auth }).then(r => r.json());
    status = job.data?.status ?? status;
  }
  const ms = Date.now() - t0;

  const detail = await fetch(`${base}/api/programs/${programId}`, { headers: auth }).then(r => r.json());
  results.push({ id: label.id, status, ms, detail: detail.data });
  console.log(`· ${label.id}  ${status}  ${ms}ms  ${detail.data?.courses?.length ?? 0} 门`);
}

fs.writeFileSync(outFile, JSON.stringify(results, null, 2), 'utf8');
const ok = results.filter(r => r.status === 'SUCCEEDED').length;
console.log(`\n完成 ${ok}/${results.length} → ${outFile}`);
