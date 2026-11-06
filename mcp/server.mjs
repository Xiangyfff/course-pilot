#!/usr/bin/env node
// CoursePilot MCP server（stdio，JSON-RPC 2.0，newline-delimited）
// 作用：把 CoursePilot 的三个能力暴露给 Cursor / Claude Desktop 等 MCP 客户端。
//
// 用法（在 mcp.json 里）：
//   { "command": "node", "args": ["D:/dshddd/course-planner/mcp/server.mjs"],
//     "env": { "COURSEPILOT_BASE": "http://127.0.0.1:18080" } }
//
// 注意：它是个「代理」——真正干活的是已经在跑的 Spring Boot 服务。

const BASE = process.env.COURSEPILOT_BASE || 'http://127.0.0.1:18080';
let token = process.env.COURSEPILOT_TOKEN || null;
let cachedProgramId = null;

async function ensureToken() {
  if (token) return token;
  const res = await fetch(`${BASE}/api/auth/demo`, { method: 'POST' });
  if (!res.ok) throw new Error(`登录失败：HTTP ${res.status}`);
  const { data } = await res.json();
  token = data.token;
  return token;
}

async function api(path, options = {}) {
  const t = await ensureToken();
  const res = await fetch(`${BASE}${path}`, {
    ...options,
    headers: { Authorization: `Bearer ${t}`, 'Content-Type': 'application/json', ...(options.headers || {}) },
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}: ${(await res.text()).slice(0, 200)}`);
  return (await res.json()).data;
}

async function defaultProgramId() {
  if (cachedProgramId) return cachedProgramId;
  const list = await api('/api/programs');
  if (!list?.length) throw new Error('还没有培养方案，请先在网页上传一份');
  cachedProgramId = list[0].id;
  return cachedProgramId;
}

const tools = [
  {
    name: 'get_progress',
    description: '查询学分进度：总要求、已修、还差、各模块缺口、未完成必修课门数',
    inputSchema: {
      type: 'object',
      properties: { programId: { type: 'integer', description: '培养方案 id，省略则用最新一份' } },
    },
    handler: async (args) => api(`/api/dashboard/${args.programId || (await defaultProgramId())}`),
  },
  {
    name: 'solve_plan',
    description: '读取最近一次生成的排课方案（每学期课程、总学分、风险分）',
    inputSchema: {
      type: 'object',
      properties: { programId: { type: 'integer', description: '培养方案 id，省略则用最新一份' } },
    },
    handler: async (args) => api(`/api/plans/latest?programId=${args.programId || (await defaultProgramId())}`),
  },
  {
    name: 'search_program',
    description: '在培养方案原文里按关键词检索段落，用于回答学分要求、先修关系等问题',
    inputSchema: {
      type: 'object',
      properties: {
        keyword: { type: 'string', description: '检索关键词，例如「通识教育」「先修」' },
        programId: { type: 'integer', description: '培养方案 id，省略则用最新一份' },
      },
      required: ['keyword'],
    },
    handler: async (args) =>
      api(`/api/programs/${args.programId || (await defaultProgramId())}/search?keyword=${encodeURIComponent(args.keyword)}`),
  },
];

function send(msg) {
  process.stdout.write(JSON.stringify(msg) + '\n');
}

function ok(id, result) {
  send({ jsonrpc: '2.0', id, result });
}

function fail(id, code, message) {
  send({ jsonrpc: '2.0', id, error: { code, message } });
}

async function handle(req) {
  const { id, method, params } = req;
  if (method === 'initialize') {
    ok(id, {
      protocolVersion: '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: 'coursepilot', version: '1.0.0' },
    });
    return;
  }
  if (method === 'notifications/initialized') return;
  if (method === 'ping') return ok(id, {});
  if (method === 'tools/list') {
    return ok(id, { tools: tools.map(({ name, description, inputSchema }) => ({ name, description, inputSchema })) });
  }
  if (method === 'tools/call') {
    const tool = tools.find((t) => t.name === params?.name);
    if (!tool) return fail(id, -32601, `未知工具：${params?.name}`);
    try {
      const data = await tool.handler(params.arguments || {});
      ok(id, { content: [{ type: 'text', text: JSON.stringify(data, null, 2) }] });
    } catch (e) {
      ok(id, { content: [{ type: 'text', text: `调用失败：${e.message}` }], isError: true });
    }
    return;
  }
  fail(id, -32601, `未实现的方法：${method}`);
}

let buffer = '';
let pending = 0;
let ended = false;

function maybeExit() {
  if (ended && pending === 0) process.exit(0);
}

process.stdin.setEncoding('utf8');
process.stdin.on('data', (chunk) => {
  buffer += chunk;
  let idx;
  while ((idx = buffer.indexOf('\n')) >= 0) {
    const line = buffer.slice(0, idx).trim();
    buffer = buffer.slice(idx + 1);
    if (!line) continue;
    let req;
    try { req = JSON.parse(line); } catch { continue; }
    pending++;
    handle(req)
      .catch((e) => fail(req.id, -32603, String(e)))
      .finally(() => { pending--; maybeExit(); });
  }
});
// 注意：不能收到 end 就 exit，否则 tools/call 的异步结果还没写出去进程就没了。
process.stdin.on('end', () => { ended = true; maybeExit(); });
