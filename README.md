# 课程规划 Agent ·「毕业前我还差什么」

把 30 页的培养方案 PDF，变成一张「接下来每学期该修什么」的可执行课表。

上传培养方案 + 成绩单 → LLM 只负责把非结构化文本**翻译**成结构化数据 → 后台异步任务做校验与入库 →
约束求解器在「先修关系 + 模块学分下限 + 学期学分上限 + 开课学期」下回溯搜索出多套可行方案 →
再基于方案与培养方案原文做带引用的流式问答。

> 核心原则：**LLM 负责翻译，不负责决策。**
> 排课交给可验证的算法——LLM 会编出根本不存在的课程，而且它的输出无法证明「无解」。

---

## 界面

概览页（右上角胶囊显示当前抽取引擎）：

![概览](docs/screenshots/overview-deepseek.png)

排课方案页（多套可行方案 + 风险提示 + 学期时间线）：

![方案](docs/screenshots/plans.png)

## 一分钟跑起来

**方式 A：本机（最快，零外部依赖）**

```bash
cd course-planner
./mvnw -B spring-boot:run          # Windows: mvnw.cmd -B spring-boot:run
# 打开 http://127.0.0.1:18080 ，演示账号 demo / demo1234（页面会自动登录）
```

默认 profile `dev`：H2 内存库（MySQL 兼容模式）+ Flyway 建表 + 自动播种演示数据 + `app.llm.provider=mock`。
启动约 20 秒，不需要 MySQL / Redis / 任何 API Key。

想换成真模型（以 DeepSeek 为例），只改 `course-planner/.env` 四行再重启，代码不用动：

```properties
LLM_PROVIDER=openai-compatible
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_API_KEY=sk-你的key
LLM_MODEL=deepseek-chat
```

`application.yml` 里有 `spring.config.import: optional:file:.env[.properties]`，本机启动会读这个文件（Docker 里由 compose 注入同样的变量），没有 `.env` 也不会报错。验证：`curl /api/health` 从 `"llm":"mock"` 变成 `"llm":"openai-compatible"`，页面右上角胶囊同步变化。

**方式 B：Docker**

```bash
cd course-planner
cp .env.example .env
docker compose up --build          # → http://127.0.0.1:18080
```

镜像已在本机验证：`docker build` 产出约 858 MB 镜像，容器起来后 `bash samples/smoke.sh http://127.0.0.1:18083` 13 步全过。
构建用 BuildKit cache mount 缓存 `~/.m2`，第一次要下依赖（几分钟），之后改代码重建只做编译。

**方式 C：MySQL 8 + Redis（更接近生产）**

```bash
docker compose --profile mysql up -d mysql          # 映射到宿主机 3307，避免和本机 3306 冲突
SPRING_PROFILES_ACTIVE=mysql DB_PORT=3307 DB_USER=planner DB_PASSWORD=planner ./mvnw spring-boot:run
# 需要 Redis 时再加：--app.cache.mode=redis
```

---

## 它到底做了什么

### 1. 解析：非结构化 → 结构化（异步任务 + 双重校验）

```
POST /api/programs (multipart)  →  建 job(PENDING)  →  返回 jobId
        │                                   │
        │                                   └─ MQ/线程池派发 → claim() 抢占
        ▼
  PDFBox / 文本解码（UTF-8 严格解码失败退 GBK）→ 魔数校验
        ▼
  LLM 结构化抽取（JSON Schema）—— 失败自动降级到规则抽取
        ▼
  校验责任链：结构 → 模块补全 → 先修闭环（DFS 三色删环）→ 学分守恒
        ▼
  写 course / course_prereq / module_requirement，program.status=READY
```

- **双通道抽取**：`app.llm.provider=mock` 走正则规则抽取（零依赖、结果稳定、可跑测试）；`openai-compatible` 走任意 OpenAI 兼容端点（OpenAI / DeepSeek / 通义 / Ollama）。
- **校验链不是装饰**：LLM 常见错误是「先修指向不存在的课」「学分合计对不上」「同一门课重复两次」「课程写模块名、模块表里却是 code」，责任链逐条拦掉，剩下的才入库。模块名 → code 的归一化放在 `ModuleValidator` 里，真接 DeepSeek 时正是靠它把 `通识教育/专业核心` 映射到 `GE/CS`，否则会凭空补出 4 个 0 学分模块。
- **幂等**：`job.idempotency_key` 唯一索引 + `UPDATE ... WHERE status='PENDING'` 抢占，保证同一条任务只被执行一次。
- **围栏令牌**：任务卡在 RUNNING 超时会被 `recoverStuck()` 重排，但「重排时原来那个线程其实还活着」是真实窗口。认领时把 `version` 带下去，之后所有写状态/进度的 SQL 都加 `AND version = :fencing`，旧执行者写回时更新到 0 行，只记一条 warn，不会把新线程正在跑的任务改成 SUCCEEDED。`JobFencingTest` 钉住了这个行为。

### 2. 求解：约束满足，不是让模型「感觉一下」

`BacktrackingSolver` 把排课当成 CSP：

| 要素 | 实现 |
| --- | --- |
| 变量 | 每门待修课程 → 一个学期编号 |
| 约束 | 先修必须严格早于后修；每学期学分 ≤ 上限；课程只在开课学期可选；总学期数 ≤ 8 |
| 搜索顺序 | 先修深度**升序**（拓扑序）+ 同层 MRV（可选学期少、学分重的先排） |
| 多样性 | 3 种候选学期偏好（最早 / 最空 / 后置）→ 去重后得到多套方案 |
| 兜底 | 时间预算 1500ms，超时即返回已有方案并标注 |
| 可信度 | `riskScore`：学期学分 > 90% 上限 +8；先修链只隔 1 学期 +6 |

已通过的课直接跳过；已修学分先抵扣对应模块的要求，必修排完后再按**真实差额**补选修（不做这件事时，明明修满的模块还会被塞选修课）。

> 这里踩过一个真实的坑：最初按「先修深度降序」（先排后修课）做 MRV，结果先修还没落位时
> `prereqBefore` 查不到学期记录，被当成「无约束」放行，`CS102` 和它的先修 `CS101` 排进了同一学期。
> 单测抓出来后改成拓扑序 + 同层 MRV，并把「先修未落位」明确判为不满足。

### 3. 问答：先算数，再说话

`POST /api/qa/stream`（SSE）的帧顺序：`meta`（引用片段 + 引擎）→ `delta`（逐段吐字）→ `done`。

回答内容来自「看板统计 + 最新方案 + 培养方案原文检索（中文二字组重合度 top3）」，有 LLM 时用它组织语言，没有就用模板拼。
每个回答都能点回原文片段——**引用溯源**是这类 Agent 能不能被信任的分水岭。

---

## 技术栈与选型理由

| 层 | 选择 | 为什么不是另一个 |
| --- | --- | --- |
| Web | Spring Boot 3.5 / Java 17 | 本机只装了 17；虚拟线程方案因此作废（8 核以下收益也有限） |
| 数据访问 | JdbcClient + 手写 SQL | 11 张表、查询模式固定，手写 SQL 能直接讲 EXPLAIN 与索引；MyBatis 的映射在这里是纯成本 |
| 迁移 | Flyway | 同一份 DDL 在 H2(MODE=MySQL) 与 MySQL 8 上跑，测试与生产不漂移 |
| 缓存 | Caffeine 默认 / Redis 可切 | 单机演示用进程内缓存最快；`app.cache.mode=redis` 即可换成 RedisCacheManager |
| 消息 | 进程内线程池派发（默认）/ RocketMQ 适配器 | 单机不需要 broker 才能证明「异步 + 幂等」；接口 `JobDispatcher` 留好了扩展点 |
| 鉴权 | 手写 HS256 JWT + BCrypt | 只为 demo 发 token，引整套 Spring Security 只会让启动变慢、演示更难讲清 |
| 前端 | 原生 JS + 手写 CSS，Spring Boot 静态托管 | 三个页面，零构建；省下的时间放在 SSE 和方案可视化上 |
| LLM | 可插拔 Provider | mock 保证「没有 key 也能演示、CI 也能跑」 |

数据库 11 张表：`app_user` `program` `course` `course_prereq` `module_requirement` `transcript`
`transcript_course` `job` `plan_result` `llm_call_log` `qa_message`。

索引与约束：`uk_app_user_username`、`idx_program_user`、`uk_course_program_code`、
`uk_job_idem`（幂等）、`idx_job_status`（补偿扫描）、`program.version`（乐观锁）。

---

## 接口

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/health` | 健康检查（返回当前 LLM / MQ / 缓存模式） |
| POST | `/api/auth/demo` | 演示账号一键登录，返回 JWT |
| POST | `/api/auth/login` | 用户名密码登录 |
| GET | `/api/auth/me` | 当前用户 |
| POST | `/api/programs` | 上传培养方案（multipart），返回 `programId` + `jobId` |
| GET | `/api/programs` | 我的培养方案列表 |
| GET | `/api/programs/{id}` | 方案详情（课程 + 模块 + 先修数） |
| POST | `/api/transcripts` | 上传成绩单（multipart），同步解析 |
| GET | `/api/transcripts` | 我的成绩单 |
| GET | `/api/jobs/{id}` | 任务状态快照 |
| GET | `/api/jobs/{id}/events` | 任务进度 SSE（`progress` / `stage` / `status`） |
| POST | `/api/plans` | 发起求解，返回 `jobId` |
| GET | `/api/plans/{jobId}` | 取该次求解的 N 套方案 |
| GET | `/api/plans/latest?programId=` | 取最新一批方案 |
| GET | `/api/dashboard/{programId}` | 学分看板（每模块已修 / 还差 / 剩余门数） |
| POST | `/api/qa/stream` | 流式问答（SSE：`meta` → `delta` → `done`） |
| GET | `/api/qa/history` | 历史问答 |

所有业务接口需要 `Authorization: Bearer <token>`，并且只能访问自己的数据（`program.user_id` 校验，防 IDOR）。

---

## 配置

| 环境变量 | 默认 | 说明 |
| --- | --- | --- |
| `APP_PORT` | `18080` | 8080 常被别的项目占用，所以默认错开 |
| `APP_ADDRESS` | `127.0.0.1` | 容器内需设 `0.0.0.0` |
| `SPRING_PROFILES_ACTIVE` | `dev` | `dev`=H2 内存，`mysql`=MySQL 8 |
| `LLM_PROVIDER` | `mock` | `mock` / `openai-compatible` |
| `LLM_BASE_URL` | `http://127.0.0.1:11434/v1` | 默认指向本机 Ollama |
| `LLM_API_KEY` | 空 | 云端厂商填这里 |
| `LLM_MODEL` | `qwen2.5:7b` | |
| `CACHE_MODE` | `caffeine` | `redis` 切 Redis |
| `MQ_MODE` | `local` | `rocketmq` 走 RocketMQ 适配器 |
| `JWT_SECRET` | 开发用固定串 | **生产必须换** |
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `127.0.0.1` / `3306` / `course_planner` / `planner` / `planner` | mysql profile |

接真实模型（以 DeepSeek 为例）：

```bash
LLM_PROVIDER=openai-compatible \
LLM_BASE_URL=https://api.deepseek.com/v1 \
LLM_API_KEY=sk-xxx \
LLM_MODEL=deepseek-chat \
./mvnw spring-boot:run
```

Ollama 本地：`LLM_PROVIDER=openai-compatible LLM_MODEL=qwen2.5:7b ./mvnw spring-boot:run`（`LLM_BASE_URL` 默认已指向 11434）。

---

## 性能实测

本机：Ryzen 9 7940HX（16C/32T）、15.2 GB、Windows + Docker Desktop 跑 MySQL 8。
场景：内置演示方案 39 门课 / 117 学分 / 8 学期 / 一次出 3 套方案。

| 场景 | 后端 | p50 | p95 |
| --- | --- | --- | --- |
| 上传培养方案 → 结构化入库 | H2 内存 | 31 ms | 33 ms |
| 同上 | MySQL 8（Docker，同机） | 139 ms | 158 ms |
| 提交求解 → 3 套方案落库 | H2 内存 | 12 ms | 28 ms |
| 同上 | MySQL 8（Docker，同机） | 29 ms | 45 ms |
| 上传培养方案 → 结构化入库（**真模型**，23 门课） | DeepSeek `deepseek-chat` | 3.8 s | — |

复现：`node samples/bench.mjs http://127.0.0.1:18080 30`（长连接 + 5ms 轮询，避免 curl 进程开销污染结果）。

一次真实的优化记录：解析写库最初是逐行 `INSERT`（40 门课 + 先修边共 60+ 次往返），
MySQL 下 p50 200 ms；换成 `NamedParameterJdbcTemplate` 批量写入并打开 `rewriteBatchedStatements=true` 后降到 139 ms。
剩余耗时主要在任务状态更新与文件解析，继续压需要合并状态更新。

> 这些是单机、无并发、无模型调用的数字。接真实 LLM 后主导项会变成模型延迟（秒级），
> 那时该讨论的是超时、重试、并发度，而不是 SQL。

### 并发（读接口）

`samples/load.mjs`：固定并发、keep-alive、预热后统计，打 `GET /api/dashboard/1`（看板，命中 Caffeine）。

| 并发 | QPS | p50 | p95 | p99 | 失败 |
| --- | --- | --- | --- | --- | --- |
| 50 | 2477 | 18.3 ms | 32.2 ms | 44.7 ms | 0 |
| 100 | 2639 | 34.9 ms | 57.8 ms | 70.7 ms | 0 |

```bash
node samples/load.mjs http://127.0.0.1:18080 50 10    # 并发 50，跑 10 秒
```

瓶颈不在 Spring：50 → 100 并发时 QPS 只涨 6.5%，延迟翻倍，说明已经压到单机的 CPU/连接调度上限；
换 MySQL 后同样的接口会掉到几百 QPS（每请求一次查询）。

### 写路径（异步任务：排队与吞吐）

`samples/load-write.mjs` 同时打两条写链路：上传培养方案（→ 解析任务）与提交求解（→ 求解任务）。
每份上传内容都带唯一备注，否则会被 `findRecentByHash` 去重成同一个 program。采集三段：

- **提交延迟**：POST 返回耗时（用户点按钮到看到「已提交」）
- **排队时延**：`job.started_at - job.created_at`（任务在队列里等的时间，`GET /api/jobs/{id}` 可直接看）
- **端到端**：提交到任务终态

线程池：`parsePool=4`、`solvePool=2`（`AppProperties` 可调）。

真模型（DeepSeek，单份解析约 3.4 s）：

| 并发 | 解析·提交 p50 | 解析·排队 p50 / p95 | 解析·端到端 p50 | 求解·排队 p50 | 求解·端到端 p50 | 吞吐 |
| --- | --- | --- | --- | --- | --- | --- |
| 10 | 55 ms | 3.4 s / 6.7 s | 6.7 s | < 1 ms | 45 ms | 0.98 任务/s |
| 20 | 72 ms | **7.5 s / 14.3 s** | 10.7 s | 1 ms | 45 ms | 1.11 任务/s |

规则通道（`LLM_PROVIDER=mock`，单份解析约 10 ms）同样 20 并发：

| 并发 | 解析·排队 p50 | 解析·端到端 p50 | 求解·端到端 p50 | 吞吐 |
| --- | --- | --- | --- | --- |
| 20 | 9 ms | 25 ms | 18 ms | 99.5 任务/s |

三点值得说：

1. **提交永远快**：上传 p50 55–72 ms、求解 p50 4 ms，不随并发变化——因为提交只写一行 job 就返回 202，重活全在后台。这就是「异步」的意义，不是把接口写慢。
2. **排队时延随并发线性涨**：20 个任务、4 个 worker、单个 3.4 s，按 Little 定律排队约 7.5 s，实测正好对上。要压下去只有三条路——加 worker（受 LLM 限流约束）、缩短单任务（减 token / 改小模型）、或者接受排队并把进度如实告诉用户（本项目选了第三条：SSE 实时推进度）。
3. **求解池 2 个线程几乎不排队**：CPU 型任务单次 20 ms，20 并发下 p50 也只有 1 ms；瓶颈不在这里，所以没有理由再加大。

```bash
node samples/load-write.mjs http://127.0.0.1:18080 20 20
```

> 口径：本机压测（16C/32T 笔记本），不是线上；真实部署的 2C 机器上 worker 数和排队会完全不同，别把这个表当容量承诺。

### 求解器对照（贪心 vs 回溯）

`src/test/java/com/courseplanner/solve/SolverComparisonBench.java`，输入是演示方案的 32 门必修、已修 18 门、
8 学期，每个场景跑 300 次取中位数：

| 场景 | 贪心（无回溯） | 回溯 + 拓扑序 + MRV |
| --- | --- | --- |
| 25 学分上限、开课不限 | 300/300 排完，45 学分，p50 0.15 ms | 300/300，45 学分，p50 0.32 ms |
| **13 学分上限**、开课不限 | **0/300**，p50 0.04 ms | **300/300**，45 学分，p50 0.20 ms |
| 13 学分上限 + 隔学期开课 | 300/300，p50 0.09 ms | 300/300，p50 1.38 ms |

```bash
./mvnw -B test -Dtest=SolverComparisonBench
```

结论要如实讲：**宽松约束下贪心又快又够用，回溯的代价是 2～15 倍耗时**；但一旦学分上限收紧，
贪心会把学分先塞给「什么时候都能上」的课，等到只开一个学期的课就没位置了，而且它不会退回去——
300 次全部失败。回溯多花的这零点几毫秒，买的是「能证明排不出」这件事。
另外回溯版目前不追求最短毕业期，它会摊平成 8 个学期求均衡，所以看起来学期更多。

测试里还钉了一个 4 门课的最小反例（两门课各只开一个学期、每学期上限 8 学分）：
贪心排不出、回溯排得出，作为回归断言。

---

## 抽取评测集

`eval/` 是一套可重复的评测：12 份文档 = 4 种排版 × 3 种变体，覆盖同一份知识（27 门课 / 总 87 学分 / 4 个模块 / 18 条先修边），但写法各不相同。

| 排版 | 长什么样 |
| --- | --- |
| `table` | 代码、名称、学分、模块、先修 分列对齐 |
| `prose` | 中文句子：「CS101 程序设计基础，4 学分，先修：无」 |
| `sections` | 模块标题 + 列表：「一、通识教育（最低 24 学分）」+「- EN101 大学英语A 3 学分」 |
| `noisy` | 模拟 PDF 抽出的脏文本：页眉页脚、页码、全角空格、随机断行 |

变体：`full`（完整）/ `no-total`（不写总学分）/ `inline-prereq`（先修塞进课程名括号，没有「先修：」这种显式标记）。

跑法：

```bash
node eval/gen.mjs                                     # 重新生成 12 份文档 + labels.json
node eval/run-eval.mjs http://127.0.0.1:18080 eval/pred-llm.json    # 逐份上传、等任务、导出结果
node eval/run-eval.mjs http://127.0.0.1:18082 eval/pred-rule.json   # 18082 = LLM_PROVIDER=mock
node eval/score.mjs eval/pred-rule.json eval/pred-llm.json          # 出下面这张表
```

结果（同一台机器、同一份语料，各跑一遍，12 份取平均）：

| 指标 | 规则抽取（mock） | 真模型（deepseek-chat） |
| --- | --- | --- |
| 课程识别 P / R / F1 | 75.0 / 62.7 / **65.9%** | 100 / 100 / **100%** |
| 学分准确率 | 75.0% | **100%** |
| 模块准确率 | 85.2% | **100%** |
| 先修边 F1 | 28.4% | **100%** |
| 总学分命中 | 75.0% | **100%** |
| 学校 / 专业命中 | 75.0% / 75.0% | **100% / 100%** |
| 平均耗时 | **336 ms** | 4394 ms |
| 失败的任务 | 3 / 12（散文式文档，无课程行 → 直接进死信） | 0 / 12 |

几个值得说的点：

1. **规则通道的短板是结构，不是知识**。表格和分节排版能拿 100%，但整段散文（「CS101 程序设计基础，4 学分，先修：无。」）没有「代码 + 名称 + 学分」的列结构，正则一条都匹配不到，任务直接判为不可重试并进死信——**330 ms 出结果，不占着重试三次的线程池**。
2. **先修关系最容易被吃掉**。规则通道只有 28.4%，因为 `先修EN101`（无冒号）、`（先修 EN101）`（括号内）这些写法各占三分之一；真模型在补了「先修标记的四种写法」这条 prompt 规则之后从 66.7% 提到 100%。
3. **模块名不是模块代码**。`sections` 排版只给中文模块名（甚至整行标题「一、通识教育（最低 24 学分）」），真模型会把整行抄成模块 code。`ModuleValidator` 里做了标签清洗（去序号、去「最低 X 学分」），并有单测钉住，所以这门课不会「挂在一个不存在的模块下，学分永远算不满」。
4. **耗时差 13 倍，但这是两种用途**：规则通道是兜底（模型挂了、没配 key 也要能用），真模型是主力。`LLM_PROVIDER=mock` 时全链路照跑，这是离线演示的前提。

**这套评测的边界**（别拿它当「准确率 100%」宣传）：

- 语料是程序生成的合成文档，4 种排版 × 3 变体，不是从真实学校官网爬的 30 页 PDF；真实文档的表格错行、跨页断表、扫描件，它都没覆盖。
- 12 份文档共享同一份 27 门课的知识，模型只要学会一种写法就能迁移，绝对分数偏高。
- 只评「抽取」，不评「求解」与「问答」的质量。

参见 `eval/gen.mjs`（造数据）、`eval/run-eval.mjs`（跑）、`eval/score.mjs`（算分），三个文件都能独立读。

---

## 测试

```bash
./mvnw -B test                    # 22 个单元测试
bash samples/smoke.sh             # 13 步端到端冒烟（需应用已在 18080 运行）
```

单测覆盖：求解器（先修顺序、学期上限、无解、多方案去重、已修跳过、已修学分填模块缺口）、先修校验（缺课 / 自引用 / 成环 / 合法链）、结构校验（重复码、非法字段、全空、归一化）、模块标签清洗、标题抽取（校名 + 专业切分、课程行不误判）、**任务幂等**（同一 PENDING 只有一个认领成功、重排后旧执行者的写回全部落空）。

`samples/` 里有可直接上传的示例：

- `program-software-engineering.txt`：4 个模块（16 / 20 / 28 / 14 学分，总 78）、23 门课，含 `先修:CODE` 写法
- `transcript-demo.txt`：13 门课 / 42 学分
- `smoke.sh`：从登录到问答的一条龙脚本（Windows 下中文请求体走 `--data-binary @file`，避免 GBK 乱码）
- `bench.mjs`：求解与解析的耗时基准（`node samples/bench.mjs <baseUrl> <次数>`）
- `load.mjs`：并发压测，输出 QPS / p50 / p95 / p99（`node samples/load.mjs <baseUrl> <并发> <秒>`）
- `load-write.mjs`：写路径压测，分「提交延迟 / 排队时延 / 端到端」三段统计（`node samples/load-write.mjs <baseUrl> <并发> <任务数>`）

---

## 目录

```
course-planner/
├── src/main/java/com/courseplanner/
│   ├── auth/        手写 JWT、参数解析器、BCrypt
│   ├── program/     培养方案、课程、先修、模块
│   ├── transcript/  成绩单解析（同步）
│   ├── job/         任务状态机、抢占、补偿、SSE、线程池派发
│   ├── extract/     PDF/文本解析、LLM 抽取、规则抽取、校验责任链
│   ├── solve/       回溯求解器、方案、看板
│   ├── qa/          RAG 检索 + 流式回答
│   ├── demo/        内置示例数据播种
│   └── common/     统一响应、全局异常、健康检查
├── src/main/resources/static/   三个页面（index.html / style.css / app.js）
├── src/test/java/               22 个单元测试
├── eval/                        抽取评测集（生成 12 份文档 / 跑 / 打分）
└── samples/                     示例培养方案、成绩单、冒烟与压测脚本
```

---

## 没做完 / 已知边界（诚实清单）

1. **RocketMQ 适配器**只有接口与配置位（`MQ_MODE=rocketmq`），实现未完成，默认走进程内线程池派发。
2. **没有注册流程**：账号来自播种（`demo/demo1234`），登录接口可用但无注册接口。
3. **只支持 PDF / TXT**，不解析 Word 与扫描件 OCR。
4. **抽取评测集是合成语料**：12 份文档由 `eval/gen.mjs` 生成，只覆盖 4 种排版，不是真实学校的 30 页 PDF；数字见上面那节，别当成线上准确率。
5. **并发只压了本机**：读接口 2477 QPS、写路径 20 并发排队 7.5 s 都是笔记本上的数字，不是线上容量；SSE 长连接没压（每次连接一个轮询线程 + 一个 DB 查询，长连接数上去会先打爆这个）。
6. 求解器是启发式回溯，不是 CP-SAT 那种最优解保证；对 40 门课的培养方案够用。
7. **对照实验的输入是自带的演示数据**，不是真实学校的方案；贪心在宽松约束下也能排完，回溯的收益只在约束收紧时出现（数字见上面那张表）。

## 下一步（按性价比排）

- 抓 10 份真实学校的培养方案 PDF 补进评测集，看合成语料上的 100% 掉到多少
- 压 SSE 长连接：同时挂 200 个 `/api/jobs/{id}/events`，看轮询线程和 DB 查询的放大比
- Prometheus + Grafana 三面板（任务成功率、求解耗时、LLM 成本）
- 死信队列 + 人工校正界面
