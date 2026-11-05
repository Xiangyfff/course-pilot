# CoursePilot

![Java](https://img.shields.io/badge/Java-17-ED8B00?style=flat-square&logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring_Boot-3.5.16-6DB33F?style=flat-square&logo=springboot&logoColor=white)
![LLM](https://img.shields.io/badge/LLM-可插拔_OpenAI_兼容-10A37F?style=flat-square)
![DeepSeek](https://img.shields.io/badge/DeepSeek-deepseek--chat-4D6BFE?style=flat-square)
![MySQL](https://img.shields.io/badge/MySQL-8-4479A1?style=flat-square&logo=mysql&logoColor=white)
![H2](https://img.shields.io/badge/H2-dev_内存库-0B4F6C?style=flat-square)
![Redis](https://img.shields.io/badge/Redis-可选-DC382D?style=flat-square&logo=redis&logoColor=white)
![Caffeine](https://img.shields.io/badge/Caffeine-默认缓存-7952B3?style=flat-square)
![Flyway](https://img.shields.io/badge/Flyway-迁移-CC0200?style=flat-square&logo=flyway&logoColor=white)
![PDFBox](https://img.shields.io/badge/PDFBox-3.0.3-2E7D32?style=flat-square)
![SSE](https://img.shields.io/badge/SSE-流式-4F46E5?style=flat-square)
![Docker](https://img.shields.io/badge/Docker_Compose-部署-2496ED?style=flat-square&logo=docker&logoColor=white)
![Vanilla JS](https://img.shields.io/badge/原生_JS-零构建-F7DF1E?style=flat-square&logo=javascript&logoColor=black)

**代码**：<https://github.com/Xiangyfff/course-pilot>　|　**演示怎么讲**：[docs/INTERVIEW.md](docs/INTERVIEW.md)　|　**简历条目**：[docs/RESUME.md](docs/RESUME.md)

把培养方案 PDF 变成一张能照着排的课表。

大三开始，每学期选课都要翻学院那份 30 页的培养方案：先修关系写在课程简介的句子里，模块学分要求散在几张表里，想知道「毕业还差什么」只能一页页对数。我们班有人到大四才发现漏了一门先修，只能延毕一学期。

所以做了这个：上传培养方案和成绩单，后台把文本解析成课程、学分、先修关系，再用约束求解器排出后面每学期该修什么。

![概览](docs/screenshots/overview-deepseek.png)

## 能做什么

- 上传培养方案（PDF / TXT），后台异步解析成结构化课程表 —— 30 页 PDF 手敲要半小时，这里几秒
- 上传成绩单，算每个模块已修多少、还差多少、还剩几门必修
- 一键排课：在先修关系、每学期学分上限、课程开课学期三个约束下，一次出 3 套可行方案
- 问「我还差多少学分能毕业」，回答带培养方案原文的引用片段
- 没有 API Key 也能跑：默认的 `mock` 引擎用正则抽取，全链路和单测都不依赖外网

## 跑起来

最短路径，只要 JDK 17：

```bash
cd course-planner
./mvnw spring-boot:run        # Windows: mvnw.cmd spring-boot:run
```

打开 `http://127.0.0.1:18080`，账号 `demo / demo1234`（页面会自动登录）。默认 `dev` profile 用 H2 内存库，Flyway 建表 + 播种一套示例数据，启动大概 20 秒，不需要 MySQL、Redis。

想换成真模型，改 `.env` 四行再重启，代码一行不用动：

```properties
LLM_PROVIDER=openai-compatible
LLM_BASE_URL=https://api.deepseek.com/v1
LLM_API_KEY=sk-你的key
LLM_MODEL=deepseek-chat
```

`application.yml` 里配了 `spring.config.import: optional:file:.env[.properties]`，本机读这个文件，Docker 里由 compose 注入同名变量，没有这个文件也不报错。`curl localhost:18080/api/health` 会告诉你当前用的是哪个引擎。

Docker 和 MySQL 的跑法：

```bash
docker compose up --build                                   # 镜像约 858 MB
docker compose --profile mysql up -d mysql                  # MySQL 映射到 3307，避开本机 3306
SPRING_PROFILES_ACTIVE=mysql DB_PORT=3307 ./mvnw spring-boot:run
```

## 为什么是这么做的

**模型只负责翻译，不负责排课。** 模型很擅长把「CS101 程序设计基础，4 学分，先修：无」变成一行 JSON，但它会编出不存在的课程；更麻烦的是当它说「排不出来」时，你没法判断是真无解还是它没想明白。所以抽取交给模型（错了有规则校验兜底），排课交给回溯算法（每套方案都能逐条核对）。真正省时间的是录入环节，不是决策环节。

**没有拆微服务。** 这套东西的数据全在一份培养方案和一张成绩单里，用户是自己，并发是零。拆成解析、求解、问答三个服务，只会得到三个各自跑单机代码的服务，外加跨服务事务和部署成本。真到多用户那天，第一个该拆的是解析（吃 IO、吃模型配额），不是问答。

**JdbcClient + 手写 SQL，没用 MyBatis。** 一共 11 张表，查询模式基本固定。手写 SQL 能直接看出哪条走了索引、哪条在扫全表，`EXPLAIN` 出来的东西就是代码里的东西。这个规模下 ORM 的映射层是纯成本。

**消息先走进程内线程池，但接口留成 `JobDispatcher`。** 想验证「异步 + 幂等 + 补偿」不需要先起一个 broker。RocketMQ 适配器只有配置位、没实现，文末写了。

## 里面怎么跑

解析是一条异步链路：

```
POST /api/programs → 写一行 job(PENDING) → 返回 jobId
                         │
                         └─ 线程池派发 → 一条 CAS 抢占到 RUNNING
                                 │
                  PDFBox 取文本（UTF-8 严格解码失败退 GBK）+ 魔数校验
                                 │
                  模型抽取成 JSON（失败自动降级到规则抽取）
                                 │
          四层校验：结构 → 模块补全 → 先修闭环（DFS 三色删环）→ 学分守恒
                                 │
                      写 course / course_prereq / module_requirement
```

排课是个 CSP：变量是每门课放第几学期，约束是先修严格早于后修、每学期学分不超上限、课程只在开课学期可选、总共不超过 8 学期。搜索按先修深度升序（保证排到某门课时先修已经落位），同层再按「可选学期少的先排」——也就是 MRV。一次跑三种偏好（最早 / 最空 / 后置），去重后得到几套不一样的方案。

答问走 SSE，帧是 `meta`（引用片段）→ `delta`（逐段吐字）→ `done`。内容来自看板统计 + 最新方案 + 培养方案原文检索（中文二字组重合度取前三段），接了模型就由模型组织语言，没接就用模板拼。回答能点回原文——不可溯源的回答在这种工具里只能当聊天看。

## 踩过的坑

写下来是因为它们都不是「看文档就能避免」的那类。

**先修和它的后修被排进了同一学期。** 最初按「先修深度降序」做 MRV，先排后修课，结果先修还没落位时 `prereqsBefore` 查不到学期记录，被当成「无约束」放行了。单测抓出来之后改成拓扑序 + 同层 MRV，并把「先修未落位」明确判为不满足。现在测试里还钉着一个 4 门课的最小反例。

**模型把整行标题当成了模块代码。** 接上 DeepSeek 之后，解析出来的方案凭空多出 4 个 0 学分模块——模型把「一、通识教育（最低 24 学分）」整行抄进了 `module` 字段，而模块表里写的是 `GE`。在 `ModuleValidator` 里做了标签清洗（去序号、去「最低 X 学分」）之后才正常，也补了单测。

**任务超时重排之后，原来那个线程还活着。** 卡在 RUNNING 超过 300 秒的任务会被重排，但老的执行者结束时仍会写状态，把新线程正在跑的任务改成 SUCCEEDED。修法是认领时把 `version` 带下去当围栏令牌，之后所有写状态和进度的 SQL 都带 `AND version = :fencing`，旧执行者命中 0 行、只留一条 warn。`JobFencingTest` 钉住了这个行为。

## 数字

本机 16C/32T 笔记本，Windows + Docker Desktop 跑 MySQL 8，不是线上环境：

- 解析入库（MySQL 8，40 门课 / 60 多条先修边）：p50 **139 ms**。批量写之前是 200 ms。
- 排课一次出 3 套方案：H2 **12 ms**，MySQL **29 ms**。
- 看板接口 50 并发：**2477 QPS**，p99 44.7 ms。
- 20 并发上传（真模型单份 3.4 s）：提交 **72 ms**，排队 p50 **7.5 s**——和 Little 定律估出来的量级对得上。
- 求解器对照：每学期上限收紧到 13 学分时，无回溯的贪心 300 次全部排不出，回溯 300 次全部排完，单次 0.2 ms。

复现脚本在 `samples/`：`bench.mjs`（单次耗时）、`load.mjs`（读接口并发）、`load-write.mjs`（写路径排队）。数字都在 `README` 对应的段落里能对上，别拿它当容量承诺。

## 抽取评测

`eval/` 下是一套能重复跑的评测：12 份文档 = 4 种排版 × 3 种变体，讲同一份知识（27 门课 / 87 学分 / 18 条先修边），但写法各异——分列对齐、中文句子、模块标题加列表、以及模拟 PDF 抽出来的脏文本（页眉页脚、全角空格、断行）。

```bash
node eval/gen.mjs
node eval/run-eval.mjs http://127.0.0.1:18080 eval/pred-llm.json    # 真模型
node eval/run-eval.mjs http://127.0.0.1:18082 eval/pred-rule.json   # 18082 起的是 mock 实例
node eval/score.mjs eval/pred-rule.json eval/pred-llm.json
```

结果是规则通道课程识别 F1 **65.9%**、先修边 F1 **28.4%**，真模型都是 **100%**；耗时分别是 336 ms 和 4.4 s。

有三个发现值得记：规则通道的短板是结构不是知识（表格和分节排版能到 100%，整段散文一条正则都匹配不到，直接判死信）；先修关系最容易被吃掉（`先修EN101` 不带冒号、`（先修 EN101）` 套在括号里，各占一部分），补上「先修标记的四种写法」这条 prompt 规则后从 66.7% 提到 100%；模块名和模块代码不是一回事。

这套评测的边界也写清楚：语料是程序生成的合成文档，不是真实学校官网的 30 页 PDF；12 份共享同一份知识，模型学会一种写法就能迁移，绝对分数偏高。只评抽取，不评排课和问答的质量。

## 测试

```bash
./mvnw -B test              # 22 个单测
bash samples/smoke.sh       # 13 步端到端，需要应用已在 18080 跑着
```

单测主要盯三类东西：求解器的约束（先修顺序、学期上限、无解、多方案去重、已修学分抵扣模块）、校验链的边界（缺课先修、自引用、成环、模块标签清洗）、任务幂等（同一 PENDING 只有一个认领成功、重排后旧执行者写回全部落空）。

`samples/` 里还有能直接上传的示例：一份 23 门课的培养方案、一份 13 门课的成绩单。

## 目录

```
course-planner/
├── src/main/java/com/courseplanner/
│   ├── auth/        手写 JWT、参数解析、BCrypt
│   ├── program/     培养方案、课程、先修、模块
│   ├── transcript/  成绩单解析（同步的，快，不值得异步）
│   ├── job/         任务状态机、CAS 认领、补偿、SSE
│   ├── extract/     PDF/文本解析、模型与规则抽取、校验责任链
│   ├── solve/       回溯求解器、方案、看板
│   ├── qa/          检索 + 流式回答
│   └── demo/        示例数据播种
├── src/main/resources/static/   三个页面（index.html / style.css / app.js）
├── src/test/java/               22 个单测
├── eval/                        抽取评测（造数据 / 跑 / 打分）
└── samples/                     示例文件、冒烟与压测脚本
```

## 已知问题

- RocketMQ 适配器只有接口和配置位，实现没写，现在走进程内线程池
- 没有注册流程，账号来自播种数据
- 只解析 PDF / TXT，不解析 Word，也不做扫描件 OCR
- 评测集是合成语料，数字别当成线上准确率
- SSE 长连接没压过：每条连接一个轮询线程加一次 DB 查询，连接数上去会先打爆这里
- 求解器是启发式回溯，不保证最优解；40 门课这个规模够用

## 接下来

- 抓 10 份真实学校的培养方案 PDF 补进评测集，看合成语料上的 100% 会掉到多少
- 压 SSE 长连接，看轮询线程和 DB 查询的放大比
- Prometheus + Grafana：任务成功率、求解耗时、模型成本
- 死信队列 + 人工校正界面

## License

还没加 LICENSE 文件。想用、想改，直接提 issue 或 PR 就行。
