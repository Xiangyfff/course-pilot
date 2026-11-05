/* 课程规划 Agent —— 前端（无框架，直接跑在 Spring Boot 静态资源里） */
const state = {
    token: null,
    userId: null,
    programId: null,
    detail: null,
    dashboard: null,
    plans: [],
    activePlan: 0,
};

const $ = (id) => document.getElementById(id);
const esc = (s) => String(s ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;'
}[c]));

function toast(msg) {
    const el = $('toast');
    el.textContent = msg;
    el.classList.add('show');
    clearTimeout(el._t);
    el._t = setTimeout(() => el.classList.remove('show'), 2400);
}

async function api(path, options = {}) {
    const headers = Object.assign({}, options.headers || {});
    if (state.token) headers['Authorization'] = `Bearer ${state.token}`;
    if (options.body && !(options.body instanceof FormData)) {
        headers['Content-Type'] = 'application/json';
    }
    const res = await fetch(path, Object.assign({}, options, {headers}));
    const text = await res.text();
    let body;
    try {
        body = text ? JSON.parse(text) : {};
    } catch {
        throw new Error(`HTTP ${res.status}`);
    }
    if (!res.ok || body.code !== 0) {
        throw new Error(body.message || `HTTP ${res.status}`);
    }
    return body.data;
}

/** 读取 SSE：Spring 输出 event:xxx\ndata:yyy\n\n，这里按帧解析。 */
async function streamSSE(path, options, onEvent) {
    const headers = Object.assign({'Authorization': `Bearer ${state.token}`}, options.headers || {});
    if (options.body) headers['Content-Type'] = 'application/json';
    const res = await fetch(path, Object.assign({}, options, {headers}));
    if (!res.ok) {
        throw new Error(`HTTP ${res.status}`);
    }
    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    for (;;) {
        const {value, done} = await reader.read();
        if (done) break;
        buffer += decoder.decode(value, {stream: true});
        let idx;
        while ((idx = buffer.indexOf('\n\n')) >= 0) {
            const frame = buffer.slice(0, idx);
            buffer = buffer.slice(idx + 2);
            let event = 'message', data = '';
            for (const line of frame.split('\n')) {
                if (line.startsWith('event:')) event = line.slice(6).trim();
                else if (line.startsWith('data:')) data += line.slice(5).replace(/^ /, '');
            }
            if (data !== '' || event !== 'message') onEvent({event, data});
        }
    }
}

/* ---------------- 启动 ---------------- */

async function boot() {
    try {
        const r = await api('/api/auth/demo', {method: 'POST'});
        state.token = r.token;
        state.userId = r.user.id;
        $('user-chip').textContent = r.user.displayName;
    } catch (e) {
        try {
            const r = await api('/api/auth/login', {
                method: 'POST',
                body: JSON.stringify({username: 'demo', password: 'demo1234'})
            });
            state.token = r.token;
            state.userId = r.user.id;
            $('user-chip').textContent = r.user.displayName;
        } catch (e2) {
            $('user-chip').textContent = '未登录';
            toast('演示账号不可用：' + e2.message);
            return;
        }
    }
    await loadEngine();
    await loadPrograms();
}

async function loadEngine() {
    try {
        const h = await fetch('/api/health').then(r => r.json());
        const d = h.data || {};
        const pill = $('engine-pill');
        pill.textContent = `${d.llm === 'mock' ? '离线规则引擎' : d.model} · ${d.cache}`;
        pill.className = 'pill ' + (d.llm === 'mock' ? 'off' : 'ok');
    } catch {
        /* 忽略 */
    }
}

async function loadPrograms() {
    const list = await api('/api/programs');
    if (!list.length) {
        $('major-title').textContent = '还没有培养方案';
        $('program-sub').textContent = '把 PDF 拖到下面的上传区，30 秒后就能看到结果';
        return;
    }
    state.programId = list[0].id;
    await refreshAll();
}

async function refreshAll() {
    if (!state.programId) return;
    const [detail, dash] = await Promise.all([
        api(`/api/programs/${state.programId}`),
        api(`/api/dashboard/${state.programId}`),
    ]);
    state.detail = detail;
    state.dashboard = dash;
    renderOverview();
    await loadPlans();
}

/* ---------------- 概览 ---------------- */

function renderOverview() {
    const d = state.detail, dash = state.dashboard;
    $('major-title').textContent = `${d.school || '—'} · ${d.major || '培养方案'}`;
    const grade = d.gradeYear ? `${d.gradeYear} 级 · ` : '';
    $('program-sub').textContent = `${grade}毕业要求 ${d.totalCredits} 学分 · 解析引擎 ${
        d.status === 'READY' ? '已完成' : d.status}`;

    const pct = d.totalCredits > 0 ? Math.min(100, Math.round(dash.earnedCredits / d.totalCredits * 100)) : 0;
    $('remain-credits').textContent = dash.remainingCredits.toFixed(0);
    $('remain-courses').textContent = `还有 ${dash.remainingCourses} 门必修课没有修`;
    $('ring-label').textContent = pct + '%';
    const C = 2 * Math.PI * 56;
    $('ring-progress').setAttribute('stroke-dashoffset', String(C * (1 - pct / 100)));

    $('modules').innerHTML = dash.modules.map(m => {
        const ratio = m.required > 0 ? Math.min(100, m.earned / m.required * 100) : 0;
        const warn = m.remaining > 0 ? 'warn' : '';
        return `<div class="module">
            <div class="name">${esc(m.name)}</div>
            <div class="num">${m.earned.toFixed(1)}<small> / ${m.required.toFixed(0)} 学分</small></div>
            <div class="bar ${warn}"><i style="width:${ratio}%"></i></div>
            <div style="font-size:12px;color:var(--ink-3);margin-top:8px">
                ${m.remaining > 0 ? `还差 ${m.remaining.toFixed(1)} 学分 · ${m.coursesLeft} 门必修` : '已达标'}
            </div>
        </div>`;
    }).join('');

    $('course-count').textContent = `共 ${d.courses.length} 门课`;
    $('course-body').innerHTML = d.courses.map(c => `
        <tr>
            <td class="code">${esc(c.code)}</td>
            <td>${esc(c.name)}</td>
            <td>${c.credits.toFixed(1)}</td>
            <td>${esc(moduleName(c.module))}</td>
            <td><span class="tag ${c.type === 'REQUIRED' ? 'req' : 'ele'}">${c.type === 'REQUIRED' ? '必修' : '选修'}</span></td>
            <td>${c.terms.length ? c.terms.join('/') : '不限'}</td>
            <td class="code">${c.prereqs.join(', ') || '—'}</td>
        </tr>`).join('');

    if (d.warnings && d.warnings.length) {
        $('upload-warnings').innerHTML = `<div class="warnbox">解析提示：<br>${d.warnings.map(esc).join('<br>')}</div>`;
    } else {
        $('upload-warnings').innerHTML = '';
    }
}

function moduleName(code) {
    // 模块的中文名在 dashboard 里（program 详情只带模块 code）
    const m = (state.dashboard?.modules || []).find(x => x.code === code);
    return m ? m.name : code;
}

/* ---------------- 上传 ---------------- */

function wireUpload() {
    const drop = $('drop'), file = $('file');
    drop.addEventListener('click', () => file.click());
    drop.addEventListener('dragover', e => { e.preventDefault(); drop.classList.add('hot'); });
    drop.addEventListener('dragleave', () => drop.classList.remove('hot'));
    drop.addEventListener('drop', e => {
        e.preventDefault();
        drop.classList.remove('hot');
        if (e.dataTransfer.files[0]) uploadProgram(e.dataTransfer.files[0]);
    });
    file.addEventListener('change', () => {
        if (file.files[0]) uploadProgram(file.files[0]);
    });

    const tdrop = $('transcript-drop'), tfile = $('transcript-file');
    tdrop.addEventListener('click', () => tfile.click());
    tdrop.addEventListener('dragover', e => { e.preventDefault(); tdrop.classList.add('hot'); });
    tdrop.addEventListener('dragleave', () => tdrop.classList.remove('hot'));
    tdrop.addEventListener('drop', e => {
        e.preventDefault();
        tdrop.classList.remove('hot');
        if (e.dataTransfer.files[0]) uploadTranscript(e.dataTransfer.files[0]);
    });
    tfile.addEventListener('change', () => {
        if (tfile.files[0]) uploadTranscript(tfile.files[0]);
    });
}

async function uploadProgram(file) {
    const box = $('upload-progress');
    box.classList.add('show');
    setProgress('upload', 2, '上传中');
    try {
        const fd = new FormData();
        fd.append('file', file);
        const r = await api('/api/programs', {method: 'POST', body: fd});
        state.programId = r.programId;
        await watchJob(r.jobId, 'upload');
        await refreshAll();
        toast('培养方案解析完成');
        // 解析完直接求解：上传 → 看到方案，中间不用再点一次按钮
        await requestSolve();
    } catch (e) {
        setProgress('upload', 100, '失败：' + e.message);
        toast('解析失败：' + e.message);
    }
}

async function uploadTranscript(file) {
    const box = $('transcript-progress');
    box.classList.add('show');
    $('transcript-bar').style.width = '40%';
    $('transcript-stage').textContent = '解析成绩单…';
    try {
        const fd = new FormData();
        fd.append('file', file);
        const r = await api('/api/transcripts', {method: 'POST', body: fd});
        $('transcript-bar').style.width = '100%';
        $('transcript-stage').textContent = `识别 ${r.courseCount} 门课，共 ${r.totalCredits.toFixed(1)} 学分`;
        await refreshAll();
        toast('成绩单已更新');
    } catch (e) {
        $('transcript-stage').textContent = '失败：' + e.message;
    }
}

function setProgress(prefix, pct, stage) {
    $(prefix + '-bar').style.width = Math.max(0, Math.min(100, pct)) + '%';
    $(prefix + '-pct').textContent = Math.round(pct) + '%';
    $(prefix + '-stage').textContent = stage || '';
}

/** 订阅任务进度，终态时 resolve。 */
function watchJob(jobId, prefix) {
    return new Promise((resolve, reject) => {
        let settled = false;
        streamSSE(`/api/jobs/${jobId}/events`, {}, (ev) => {
            if (ev.event !== 'progress' || settled) return;
            let job;
            try {
                job = JSON.parse(ev.data);
            } catch {
                return;
            }
            setProgress(prefix, job.progress, job.message || job.stage || '');
            if (job.status === 'SUCCEEDED') {
                settled = true;
                setProgress(prefix, 100, job.message || '完成');
                resolve(job);
            } else if (job.status === 'FAILED' || job.status === 'DEAD') {
                settled = true;
                setProgress(prefix, 100, '失败：' + (job.message || job.errorCode));
                reject(new Error(job.message || job.errorCode || '任务失败'));
            }
        }).catch(e => {
            if (!settled) {
                settled = true;
                reject(e);
            }
        });
    });
}

/* ---------------- 方案 ---------------- */

async function loadPlans() {
    try {
        const list = await api(`/api/plans/latest?programId=${state.programId}`);
        if (list && list.length) {
            state.plans = list;
            runRenderPlans();
        }
    } catch {
        /* 没有历史方案很正常 */
    }
}

async function requestSolve() {
    const btn = $('solve-btn');
    btn.disabled = true;
    $('solve-progress').classList.add('show');
    setProgress('solve', 1, '提交任务');
    try {
        const r = await api('/api/plans', {method: 'POST', body: JSON.stringify({programId: state.programId})});
        await watchJob(r.jobId, 'solve');
        const view = await api(`/api/plans/${r.jobId}`);
        state.plans = view.plans;
        state.activePlan = 0;
        runRenderPlans();
        toast(`生成 ${view.plans.length} 套方案`);
    } catch (e) {
        toast('求解失败：' + e.message);
        setProgress('solve', 100, '失败：' + e.message);
    } finally {
        btn.disabled = false;
    }
}

function runRenderPlans() {
    const chips = $('plan-chips');
    if (!state.plans.length) {
        chips.innerHTML = '';
        $('plan-detail').innerHTML = '<div class="empty">还没有方案。点右上角「生成 / 刷新方案」。</div>';
        return;
    }
    chips.innerHTML = state.plans.map((p, i) => `
        <button class="plan-chip ${i === state.activePlan ? 'active' : ''}" data-plan="${i}">
            <div class="t">方案 ${i + 1} · ${p.semesters} 学期</div>
            <div class="m">${p.totalCredits.toFixed(1)} 学分 · 风险 ${p.riskScore}</div>
        </button>`).join('');
    chips.querySelectorAll('.plan-chip').forEach(el => {
        el.addEventListener('click', () => {
            state.activePlan = Number(el.dataset.plan);
            runRenderPlans();
        });
    });

    const p = state.plans[state.activePlan];
    const notes = (p.notes || []).map(n => `<div class="warnbox">${esc(n)}</div>`).join('');
    $('plan-detail').innerHTML = notes + '<div class="timeline">' + p.terms.map(t => `
        <div class="term">
            <div class="th">第 ${t.term} 学期<small>${t.credits.toFixed(1)} 学分 / ${t.courses.length} 门</small></div>
            <div class="courses">${t.courses.map(c => `
                <div class="course-chip"><b>${esc(c.name)}</b><span>${esc(c.code)} · ${c.credits.toFixed(1)}</span></div>
            `).join('')}</div>
        </div>`).join('') + '</div>';
}

/* ---------------- 问答 ---------------- */

async function ask() {
    const input = $('chat-input');
    const q = input.value.trim();
    if (!q) return;
    if (!state.programId) {
        toast('先上传一份培养方案');
        return;
    }
    input.value = '';
    const log = $('chat-log');
    const userEl = document.createElement('div');
    userEl.className = 'msg user';
    userEl.textContent = q;
    log.appendChild(userEl);

    const botEl = document.createElement('div');
    botEl.className = 'msg bot';
    botEl.textContent = '思考中…';
    log.appendChild(botEl);
    log.scrollTop = log.scrollHeight;

    let text = '';
    try {
        await streamSSE('/api/qa/stream', {
            method: 'POST',
            body: JSON.stringify({programId: state.programId, question: q}),
        }, (ev) => {
            if (ev.event === 'meta') {
                try {
                    const meta = JSON.parse(ev.data);
                    botEl.dataset.citations = JSON.stringify(meta.citations || []);
                    botEl.dataset.engine = meta.engine || '';
                } catch { /* ignore */ }
            } else if (ev.event === 'delta') {
                if (text === '') botEl.textContent = '';
                text += ev.data;
                botEl.textContent = text;
                log.scrollTop = log.scrollHeight;
            } else if (ev.event === 'done') {
                const citations = JSON.parse(botEl.dataset.citations || '[]');
                if (citations.length) {
                    const cite = document.createElement('span');
                    cite.className = 'cite';
                    cite.textContent = '依据：' + citations.map(c => c.snippet).join(' / ');
                    botEl.appendChild(cite);
                }
            } else if (ev.event === 'error') {
                botEl.textContent = '出错了：' + ev.data;
            }
        });
    } catch (e) {
        botEl.textContent = '出错了：' + e.message;
    }
    log.scrollTop = log.scrollHeight;
}

/* ---------------- 视图切换 ---------------- */

function activateTab(view) {
    document.querySelectorAll('.tabs button').forEach(b => b.classList.toggle('active', b.dataset.view === view));
    document.querySelectorAll('.view').forEach(v => { v.hidden = v.id !== 'view-' + view; });
    if (location.hash !== '#' + view) history.replaceState(null, '', '#' + view);
}

function wireTabs() {
    document.querySelectorAll('.tabs button').forEach(btn => {
        btn.addEventListener('click', () => activateTab(btn.dataset.view));
    });
    // 支持 #plan / #qa 深链：可以直接把某一页的地址发给别人
    const initial = location.hash.replace('#', '');
    if (initial === 'plan' || initial === 'qa') {
        activateTab(initial);
    }
}

document.addEventListener('DOMContentLoaded', async () => {
    wireTabs();
    wireUpload();
    $('solve-btn').addEventListener('click', requestSolve);
    $('chat-send').addEventListener('click', ask);
    $('chat-input').addEventListener('keydown', e => {
        if (e.key === 'Enter') ask();
    });
    await boot();
});
