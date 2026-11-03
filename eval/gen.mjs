#!/usr/bin/env node
/**
 * 生成评测集：12 份「培养方案」文档 + 对应的 ground truth。
 *
 *   node eval/gen.mjs
 *
 * 为什么自己生成：手上没有真实学校的方案 PDF（也不能拿别人的成绩单）。
 * 生成的好处是标签绝对准确、可以精确到「哪门课的学分抽错了」；
 * 坏处是分布由我决定，**对规则通道可能偏友好**——这个局限写在 README 里。
 *
 * 4 种排版 × 3 种变体：
 *   R1 紧凑表格（代码 名称 学分 模块 先修 用空格分隔）
 *   R2 中文说明式（"CS101 程序设计基础，4 学分，先修：无"）
 *   R3 分模块标题 + 列表项（"一、通识教育（最低 28 学分）" + "- EN101 大学英语A 3 学分"）
 *   R4 PDF 乱排版（页眉页脚、页码、全角空格、断行）
 * 变体：full / no-total（不写总学分）/ inline-prereq（先修写成括号内说明）
 */
import fs from 'node:fs';
import path from 'node:path';

const SCHOOL = '示例大学';
const MAJOR = '软件工程';
const YEAR = 2023;

const MODULES = [
  { code: 'GENERAL', name: '通识教育', min: 24 },
  { code: 'MATH', name: '数学与自然科学', min: 17 },
  { code: 'MAJOR', name: '专业核心', min: 40 },
  { code: 'PRACTICE', name: '实践环节', min: 14 },
];

// 先修只写直接先修，避免评测里出现传递闭包的口径争议
const COURSES = [
  ['EN101', '大学英语A', 3, 'GENERAL', []],
  ['EN102', '大学英语B', 3, 'GENERAL', ['EN101']],
  ['PE101', '体育(一)', 1, 'GENERAL', []],
  ['PE102', '体育(二)', 1, 'GENERAL', []],
  ['PL101', '思想道德与法治', 3, 'GENERAL', []],
  ['PL102', '中国近现代史纲要', 3, 'GENERAL', []],
  ['GE101', '创新创业基础', 2, 'GENERAL', []],
  ['MA101', '高等数学A(上)', 4, 'MATH', []],
  ['MA102', '高等数学A(下)', 4, 'MATH', ['MA101']],
  ['MA201', '线性代数', 3, 'MATH', ['MA101']],
  ['MA202', '概率论与数理统计', 3, 'MATH', ['MA102']],
  ['PH101', '大学物理(上)', 3, 'MATH', ['MA101']],
  ['CS101', '程序设计基础', 4, 'MAJOR', []],
  ['CS102', '面向对象程序设计', 3, 'MAJOR', ['CS101']],
  ['CS201', '数据结构', 4, 'MAJOR', ['CS102']],
  ['CS202', '计算机组成原理', 4, 'MAJOR', ['CS101']],
  ['CS203', '操作系统', 4, 'MAJOR', ['CS201']],
  ['CS204', '计算机网络', 3, 'MAJOR', ['CS201']],
  ['CS205', '数据库系统', 3, 'MAJOR', ['CS201']],
  ['CS301', '软件工程', 3, 'MAJOR', ['CS201']],
  ['CS302', '编译原理', 3, 'MAJOR', ['CS201', 'MA201']],
  ['CS303', '分布式系统', 3, 'MAJOR', ['CS203', 'CS204']],
  ['CS304', '人工智能导论', 3, 'MAJOR', ['MA202', 'CS201']],
  ['CS305', '机器学习', 3, 'MAJOR', ['MA202', 'CS201']],
  ['PR101', '程序设计课程设计', 2, 'PRACTICE', ['CS101']],
  ['PR201', '生产实习', 4, 'PRACTICE', []],
  ['PR202', '毕业设计', 8, 'PRACTICE', ['PR101']],
].map(([code, name, credits, module, prereqs]) => ({ code, name, credits, module, prereqs }));

const TOTAL = COURSES.reduce((s, c) => s + c.credits, 0);
const moduleName = code => MODULES.find(m => m.code === code).name;

const header = (variant) => {
  const lines = [`${SCHOOL} ${MAJOR}专业 ${YEAR} 级培养方案`, ''];
  if (variant !== 'no-total') {
    lines.push(`毕业总学分：${TOTAL} 学分`);
  }
  return lines;
};

const renderers = {
  // R1：一行一门课，字段用空格隔开
  table(variant) {
    const out = header(variant);
    out.push('课程代码 课程名称 学分 模块 先修');
    for (const c of COURSES) {
      // inline-prereq：先修不单独成列，塞进课程名后面的括号里
      const name = variant === 'inline-prereq' && c.prereqs.length
        ? `${c.name}（先修 ${c.prereqs.join(',')}）`
        : c.name;
      const pre = variant === 'inline-prereq' ? '' : (c.prereqs.length ? `先修:${c.prereqs.join(',')}` : '');
      out.push([c.code, name, c.credits, c.module, pre].filter(Boolean).join(' '));
    }
    return out.join('\n');
  },

  // R2：中文句子，学分在括号里，先修写成「先修：无」
  prose(variant) {
    const out = header(variant);
    for (const c of COURSES) {
      const pre = c.prereqs.length ? c.prereqs.join('、') : '无';
      const inline = variant === 'inline-prereq'
        ? `${c.code} ${c.name}，${c.credits} 学分（先修课程：${pre}），属于${moduleName(c.module)}模块。`
        : `${c.code} ${c.name}，${c.credits} 学分，先修：${pre}。`;
      out.push(inline);
    }
    return out.join('\n');
  },

  // R3：按模块分节，条目用「-」，学分写成「N 学分」
  sections(variant) {
    const out = header(variant);
    const ordinals = ['一', '二', '三', '四', '五'];
    MODULES.forEach((m, i) => {
      out.push(`${ordinals[i]}、${m.name}（最低 ${m.min} 学分）`);
      for (const c of COURSES.filter(c => c.module === m.code)) {
        const pre = variant === 'inline-prereq'
          ? (c.prereqs.length ? `（先修 ${c.prereqs.join('、')}）` : '')
          : (c.prereqs.length ? ` 先修${c.prereqs.join('、')}` : '');
        const name = variant === 'inline-prereq' ? `${c.name}${pre}` : c.name;
        out.push(`- ${c.code} ${name} ${c.credits} 学分${pre}`);
      }
      out.push('');
    });
    return out.join('\n');
  },

  // R4：模仿 PDF 抽取出来的脏文本：页眉、页码、全角空格、断行
  noisy(variant) {
    const out = [];
    out.push('第 1 页 共 6 页');
    out.push(`${SCHOOL}\u3000${MAJOR}专业培养方案（${YEAR}）`);
    if (variant !== 'no-total') {
      out.push(`总学分：${TOTAL}`);
    }
    let page = 1;
    COURSES.forEach((c, i) => {
      if (i % 6 === 0 && i > 0) {
        out.push(`\u3000\u3000第 ${++page} 页 共 6 页`);
      }
      const pre = variant === 'inline-prereq'
        ? (c.prereqs.length ? `（先修 ${c.prereqs.join(' ')}）` : '')
        : (c.prereqs.length ? `  先修：${c.prereqs.join(' ')}` : '');
      const base = variant === 'inline-prereq' ? `${c.name}${pre}` : c.name;
      const name = i % 3 === 0 ? `${base}\n` : base; // 断行模拟
      out.push(`${c.code}\u3000${name}\u3000${c.credits}${pre}`);
    });
    out.push('第 6 页 共 6 页');
    return out.join('\n');
  },
};

const dir = path.join(process.cwd(), 'eval', 'programs');
fs.rmSync(dir, { recursive: true, force: true });
fs.mkdirSync(dir, { recursive: true });

const labels = [];
let n = 0;
for (const [rname, render] of Object.entries(renderers)) {
  for (const variant of ['full', 'no-total', 'inline-prereq']) {
    const id = `${rname}-${variant}`;
    const text = render(variant);
    const file = path.join(dir, `${id}.txt`);
    fs.writeFileSync(file, text, 'utf8');
    labels.push({
      id,
      file: `eval/programs/${id}.txt`,
      school: SCHOOL,
      major: MAJOR,
      totalCredits: variant === 'no-total' ? null : TOTAL,
      // 只有 table / sections 这两种排版在文档里写了模块，另两种排版里的模块信息
      // 压根不存在，任何抽取器都只能靠猜——算模块准确率时要把它们排除，
      // 否则等于拿「文档没写」去罚模型。
      modulesStated: rname === 'table' || rname === 'sections',
      courses: COURSES,
      modules: MODULES,
    });
    n++;
  }
}

fs.writeFileSync(path.join(process.cwd(), 'eval', 'labels.json'), JSON.stringify(labels, null, 2), 'utf8');
console.log(`生成 ${n} 份文档 → eval/programs/*.txt`);
console.log(`标签 → eval/labels.json（${COURSES.length} 门课 / 总 ${TOTAL} 学分）`);
