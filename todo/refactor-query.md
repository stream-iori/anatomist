# 查询体系原子化与 1.0 实施记录

> 当前目标版本：`1.0.0-SNAPSHOT`
> 公开查询契约：仅 `semantic-stream/v1`
> 索引契约：schema 21 / graph semantics 4，0.1x 必须显式 `--recreate`

## 1.0 决策

| 决策 | 结果 |
|---|---|
| 发布路线 | 不发布过渡 0.17，直接进入 1.0 |
| 兼容面 | 覆盖 0.1x 使用场景，不保留旧命令、别名或旧 JSON 输出 |
| 调用事实 | `call_site_owners` 字典化 caller/file；`call_sites` + targets 保存事实，`edges(CALLS)=0` |
| 重建策略 | schema/semantics 不兼容时 fail closed；仅显式 `--recreate` 删除旧库 |
| Spring DI | 只产生配置/绑定事实；可缩小 dispatch candidate，不制造 CALLS |
| E2E | golden 必须执行 `pipeline.json` 的真实 stdin/stdout 管道 |
| 性能 | 与 `dd2e575` 比较同一用户任务；DB 体积至少降低 30% |

## 实施状态

| 批次 | 状态 | 交付/门禁 |
|---|:---:|---|
| P0 | ✅ | 版本 1.0.0、schema 21、semantics 4、公开命令面冻结 |
| P1 | ✅ | CALLS 单一存储；full/incremental 直写 call-site 表；影响闭包读取新表 |
| P2 | ✅ | `search`/`declarations-of`/`overview` 全部语义流；显式 recreate 与丢弃量提示 |
| P3 | ✅ | 删除 12 个旧命令及其聚合实现测试；canonical help/doctor/skill |
| P4 | ✅ | `calls/dispatch/trace/regions/sites-in` 使用新事实；DI 边界锁定 |
| P5 | ✅ | 文档、pipeline golden、shell/native E2E、旧版 benchmark 和最终全量验收 |

### F0–F7：单进程管道融合

| 阶段 | 状态 | 实施结果 |
|---|:---:|---|
| F0 基线 | ✅ | `02f0eb9` 冻结可复现 benchmark；融合前基线为 `f5a9ef2` |
| F1 共享上下文 | ✅ | 一条 pipeline 共用一个 `SemanticExecutionContext`、读锁、`QueryService` 和 SQLite 连接 |
| F2 Stage 协议 | ✅ | 20 个只读命令统一接入 typed `SemanticFrameSource` / `SemanticStreamWriter` adapter |
| F3 CLI | ✅ | 支持 `pipeline -- ... --then ...` 和 `pipeline --file pipeline.json`；全局参数、白名单、类型和资源上限执行前校验 |
| F4 参考执行器 | ⏭️ | 直接落地 F7 typed executor；未引入最终必删的 NDJSON spool 和临时文件分支 |
| F5 正确性 | ✅ | 734 tests 全过；19 个 Golden 中 14 个成功场景执行 Shell/fused 原始字节对拍，5 个失败场景验证原命令；fused 错误、stdin、limit、Broken Pipe 有独立测试 |
| F6 性能 | ✅ | 30 轮 round-robin AB/BA；同一只读 DB；保留原始样本并记录 p50/p95、RSS、binary/DB SHA；全部 gate 通过 |
| F7 typed 有界流 | ✅ | 中间只传 `SemanticRecord` / `SeedFrame`；按 seed 有界；无中间 JSON、无 spool；末段才写 stream evidence |

```text
CLI / pipeline.json
        │
        ▼
参数解析 + record 类型预检
        │
        ▼
一个进程 / 一个 QueryService / 一个只读 SQLite snapshot
        │
        ▼
Stage ── typed SeedFrame ──> Stage ── typed SeedFrame ──> Final Writer
```

| Pipeline benchmark（30 runs） | Shell p50 | Fused p50 | 变化 | 输出 |
|---|---:|---:|---:|:---:|
| `resolve_one` | 185.47 ms | 183.64 ms | -1.0% | same |
| `type_pipeline` | 291.06 ms | 191.48 ms | **-34.2%** | same |
| `calls_pipeline` | 400.16 ms | 200.70 ms | **-49.9%** | same |
| `calls_high_fanout` | 421.94 ms | 225.64 ms | **-46.5%** | same |

专项报告：`target/benchmarks/semantic-pipeline/report.md`。固定 DB 为 38,125,568 B，
查询前后 SHA-256 一致；两组 calls p50 均超过 25% 改善门禁。Shell 原子管道继续兼容，
多段日常查询优先使用：

```bash
anatomist pipeline --index index.db -- \
  resolve 'p.Service#run()' --kind callable --exact --unique \
  --then calls --then dispatch
```

### 1.0 最终验收

| 门禁 | 要求 |
|---|---|
| `mvn clean test` | 全部 JUnit/IT 通过 |
| Golden | 每个 scenario 只有 `pipeline.json`，无旧命令执行 |
| `just smoke` / `just native-smoke` | 真实新管道通过，JVM/native 输出一致 |
| extension / Agent fixture | canonical pipeline 覆盖 Lombok、Spring XML、增量、分页 |
| benchmark | baseline detached worktree；报告 search、同任务 workflow、index、DB、binary、RSS |
| 文档扫描 | 除迁移/历史说明外，不把旧命令写成可执行入口 |

### 最终验收结果

| 项目 | 结果 | 证据 |
|---|:---:|---|
| JUnit / IT | ✅ | `mvn -q test`：734 tests，0 failure/error/skip |
| Golden | ✅ | 19 个 scenario 全部使用真实 `pipeline.json`；14 个成功场景做 Shell/fused 原始字节对拍 |
| JVM / native | ✅ | `just native-smoke` 覆盖 Shell/fused/JVM/native 对拍；`just extension-e2e-native` 通过 |
| Agent contract | ✅ | adapter 9/9；Jury smoke 9、complex 6；fixture contract 通过 |
| Benchmark | ✅ | 已安装 0.14.0 与当前 1.0 Shell/fused 直接对比；所有 gate 通过 |

| Benchmark 核心指标 | 0.1x baseline | 1.0 candidate | 变化 |
|---|---:|---:|---:|
| SQLite DB | 57,581,568 B | 38,137,856 B | **-33.8%** |
| `edges(CALLS)` | 19,144 | 0 | -100% |
| owners / sites / targets | 0 / 0 / 0 | 2,767 / 18,977 / 19,135 | 字典化单一事实源 |
| 全量索引 p50 | 7,923.37 ms | 7,682.26 ms | -3.0% |
| no-op 增量 p50 | 1,084.05 ms | 1,079.13 ms | -0.5% |
| 启动 p50 | 12.91 ms | 11.45 ms | -11.3% |
| 搜索 p50 | 227.03 ms | 195.85 ms | -13.7% |
| 峰值 RSS | 33,587,200 B | 28,213,248 B | -16.0% |
| native binary | 57,732,368 B | 57,813,656 B | +0.1% |
| type workflow Shell p50 | 200.75 ms | 332.45 ms | +65.6% |
| calls workflow Shell p50 | 207.71 ms | 424.84 ms | +104.5% |
| type workflow fused p50 | 194.74 ms | 188.57 ms | **-3.2%** |
| calls workflow fused p50 | 201.48 ms | 211.99 ms | +5.2% |
| type workflow fused p95 | 335.93 ms | 322.56 ms | **-4.0%** |
| calls workflow fused p95 | 444.61 ms | 436.32 ms | **-1.9%** |

schema 21 通过 owner 字典、部分索引和重叠索引裁剪，使 DB 降幅超过 30% 硬门禁。
与 0.14 聚合命令直接对比，fused type/calls 的 p50 分别为 -3.2% / +5.2%，
p95 分别为 -4.0% / -1.9%，通过 +15%/+25% 门禁。多进程性能债已由 fused pipeline 解决；
Shell 管道保留为兼容/跨 scope/外部工具入口。产品回归原始数据见
`target/benchmarks/query-refactor/results.json`，融合专项见
`target/benchmarks/semantic-pipeline/results.json`。

### 后续 benchmark 基础设施

| 对比 | 基线 | 约束 |
|---|---|---|
| 产品回归 | 默认 `~/.local/bin/anatomist` 0.14.x，也可指定旧 git ref | 同一用户任务同时比较 1.0 Shell/fused；允许输出协议不同；fused 使用 +15%/+25% p50/p95 门禁 |
| pipeline 引擎 | 融合前 `f5a9ef2` 或冻结 binary | 同一个只读 DB、原始 NDJSON 字节相等、交替采样 |

`scripts/benchmark-semantic-pipeline.py` 固定覆盖单段 resolve、两段 type pipeline、
三段 calls pipeline 和自动选出的最高扇出 callable。融合实现验收时，除 p50/p95
回归门禁外，calls 两项 p50 至少改善 25%。

以下内容是 1.0 决策前的设计推导，保留用于解释来源；其中“保留 legacy”与
`0.16/0.17` 发布节奏已被上表取代。

## 历史方案（只作设计背景）

## 结论

当前 CLI 的主要问题不是能力不足，而是查询边界不稳定：同一类事实可以由多个命令和参数组合得到，命令名同时混合了搜索、图遍历、Java 语义、框架增强、源码投影和结果编排。人可以结合经验选择路径，Agent 则需要反复试探命令。

本次重构采用以下原则：

1. 核心命令面向跨编程语言共有的语义概念，不面向 SQLite 的 `node/edge`，也不固化 Java 的 `interface/abstract/implements`。
2. Java、Python、TypeScript、Rust 通过语言前端和语义适配器解释各自的类型、调用和动态分派规则。
3. Spring XML、注解 Bean 等不属于 Java 语言，作为配置制品扩展产生通用事实，不进入语言核心命令。
4. 原子命令通过 stdin/stdout 组合；Unix pipe 是第一组合接口，不先增加 JSON 工作流 DSL。
5. 管道默认使用 NDJSON，一行一条结构化记录。JSON 是传输格式，不是编排方式。
6. 当前命令先作为兼容入口保留，逐步映射到新的规范语义，不做一次性破坏性替换。
7. 索引事实先于查询外观升级：稳定 call-site、索引 revision、资源身份和顺序事实没有落库前，不发布依赖它们的上层契约。
8. `source snapshot`、`semantic profile` 和 `index revision` 分开建模，不能用一个 hash 同时代表源码、分析环境和已提交数据库版本。

目标架构：

```text
写入侧
Java frontend ─────────┐
未来语言 frontend ─────┼─▶ versioned indexed facts ─▶ SQLite committed revision
配置/文档 producer ────┘        │
                                │ stable entity/call-site/resource identity
                                ▼
读取侧
SQLite backend ─▶ semantic operations ─▶ CLI / NDJSON pipe
                         │
                         └─▶ source-backed evidence
```

`Semantic IR` 在本方案中指公共查询契约，不直接充当所有语言前端的写入模型。未来新增语言时，可以复用公共语义词汇，但仍需分别实现前端事实模型、持久化映射和查询适配器；手工 fixture 只能证明协议没有明显的 Java 字段假设，不能证明跨语言索引能力已经成立。

## 一、为什么要重构

### 1.1 E2E 暴露出的真实问题

复杂 E2E 已覆盖搜索、消歧、多文件调用链、callback、类型影响、Spring 装配和代码修改闭环。测试过程中发现，同一个业务结论通常存在多条查询路径：

| 目标         | 当前可能使用的命令                                          |
| ------------ | ----------------------------------------------------------- |
| 查询下游调用 | `context --with-callees`、`callees-of`、`call-path`         |
| 查询条件分支 | `branches-of`、`callees-of --in-branch`、`context --source` |
| 查询字段影响 | `field-access`、`used-by`                                   |
| 查询类型实现 | `hierarchy`、`implementors-of`                              |
| 查询类型依赖 | `deps-of`、`used-by`、`context --enrich`                    |
| 建立项目基线 | `overview`、`survey-baseline`、`search`                     |

结果是 E2E Oracle 不能稳定约束命令序列，只能退回检查最终证据。允许多个入口不是错误，但缺少唯一的规范语义会导致：

```text
Agent 猜命令
   ↓
Agent 猜哪些参数隐含了额外行为
   ↓
结果重复、过宽或缺少证据
   ↓
切换另一个命令重新查询
```

理想过程应当是：

```text
确定实体
   ↓
选择语义操作
   ↓
选择方向、范围和证据预算
   ↓
用管道组合
```

### 1.2 当前 `context` 职责过多

当前 `context` 同时承担：

- 节点详情；
- 成员枚举；
- 注解和框架事实；
- 源码读取和分页；
- outgoing calls；
- 文档关联；
- semantic enrichment；
- package 查询；
- suggested queries。

这些能力分别属于实体描述、包含关系、源码证据、调用语义、文档检索和组合编排。继续增加参数会形成不可预测的“万能命令”。

### 1.3 图原语不足以表达语言语义

`select/edges/path` 适合作为存储查询原语，但不适合作为 Agent API。

例如 Java：

```text
PaymentGateway                 interface
       ▲ IMPLEMENTS
AbstractPaymentGateway         abstract class
       ▲ INHERITS
StripePaymentGateway           concrete class
```

普通 edge 遍历无法直接回答：

- 哪个节点只是抽象中间层；
- 哪些类型可以成为运行时对象；
- 一个调用指向的是编译期声明还是可能的运行时目标；
- Spring 配置是否进一步缩小了候选；
- 索引范围外是否可能还有其他实现。

因此对外必须返回语义结果，不能让 Agent 自己拼接 `IMPLEMENTS + INHERITS + OVERRIDES + CALLS`。

### 1.4 Java 命令不能成为未来语言的约束

| 概念       | Java                        | Python                  | TypeScript                   | Rust                   |
| ---------- | --------------------------- | ----------------------- | ---------------------------- | ---------------------- |
| 接口或协议 | `interface`                 | ABC、Protocol、鸭子类型 | `interface`、结构化类型      | `trait`                |
| 继承       | class/interface inheritance | class + MRO             | class/interface/type         | 不等同于 Java 继承     |
| 实现       | `implements`                | 显式或结构化符合        | 显式或结构化符合             | `impl Trait for Type`  |
| 抽象       | `abstract`                  | ABC，部分规则运行时确定 | abstract class               | 无直接等价物           |
| 多态分派   | virtual/interface           | 动态属性和 MRO          | 类型擦除后的 JavaScript 行为 | 静态泛型或 `dyn Trait` |

如果核心字段直接定义为 `is_interface`、`is_abstract`、`implements`，未来语言只能被迫伪装成 Java。正确做法是定义通用语义，再保留语言机制。

### 1.5 多次 Agent 调用带来的成本

多轮命令调用不仅增加进程启动和 SQLite 打开成本，还增加：

- Agent 与工具之间的往返；
- 查询期间索引发生变化导致的快照混用风险；
- 每个命令重复消歧；
- 每次重新解释分页、覆盖度和诊断；
- 大量中间 JSON 被重复送回模型。

Unix pipeline 可以让 Agent 用一次 shell 调用表达一个线性查询流程，同时保持每个命令职责单一。

## 二、设计原则与边界

### 2.1 第一性原则

| 原则                     | 约束                                                                                |
| ------------------------ | ----------------------------------------------------------------------------------- |
| 一个命令只做一种语义转换 | 不在 `describe` 中隐式遍历调用图                                                    |
| 输入输出类型明确         | `dispatch` 只消费调用点，不能消费任意字符串                                         |
| 源码事实和推断事实分开   | 编译期目标、候选运行时目标不得压成一条普通调用边                                    |
| 不支持不是空结果         | 返回 `UNSUPPORTED_CAPABILITY`，不能返回 `[]`                                        |
| 不完整不是不存在         | seed/stream evidence 保留 coverage 与 truncation；事实另报 origin/resolution status |
| 框架不污染语言核心       | Spring 事实属于配置制品扩展                                                         |
| 快照不能静默切换         | 管道记录携带 `index_revision_id`，下游必须校验；源码与分析环境使用独立身份          |
| 多 seed 不能丢失局部结论 | 每个 seed 单独结束并报告 evidence，最后再给 stream 汇总                             |
| 底层事实先于外部契约     | 没有稳定持久化身份和来源范围时，不在公共 IR 中承诺对应字段                          |
| 兼容入口不等于第二套语义 | 旧命令必须映射到 canonical operation                                                |

### 2.2 本轮范围

- 定义跨语言 Semantic IR 和 Artifact IR。
- 定义 NDJSON 管道协议。
- 先提供 Java 的最小垂直切片，再逐步增加语言无关的原子查询命令。
- 以 Java 作为第一个语义适配器，但“支持某操作”与“本次索引覆盖完整”分开声明。
- 升级当前 Spring XML/Bean producer 后，再映射为配置制品记录。
- 为 Python、TypeScript、Rust 建立契约 fixture 和 capability matrix；本轮不要求实现完整索引器。
- 保留现有查询命令兼容性。
- 更新 CLI help、SKILL.md、AGENTS.md、README 和命令文档。

### 2.3 非目标

- 不在第一阶段实现 JSON Plan DSL。
- 不把索引写操作接入查询管道。
- 不声称静态候选就是实际运行时目标。
- 不为每种框架增加核心命令。
- 不一次性删除现有命令。
- 不为了跨语言统一而丢弃语言特有语义。
- 不在本轮引入新的图数据库或搜索后端。

### 2.4 当前代码给出的硬约束

| 代码证据                                                                                                                         | 对方案的约束                                                     |
| -------------------------------------------------------------------------------------------------------------------------------- | ---------------------------------------------------------------- |
| `ProjectMetadata.sourceSnapshotFingerprint()` 只基于逻辑源码身份和内容；分析环境另由 `IndexEnvironmentFingerprint` 写入 metadata | 必须拆分 source snapshot、semantic profile 和 committed revision |
| `schema.sql` 中 `edges.id` 为自增，调用点主要依赖 `source_location`                                                              | 稳定 call-site 必须先升级 schema/producer                        |
| `QueryInfra.runNodeQuery/runEdgeQuery` 返回 `List`，`DependencyService` 先全量读取再分页                                         | 仅改 CLI writer 不是真流式，backend 必须改 cursor                |
| `DtoCodecs.fromTree()` 尚未实现 typed decode                                                                                     | stdin record dispatch、字段校验和资源上限是阶段 2 阻塞项         |
| `CallGraphService.callsFromTraversal()` 同时消费 `CALLS` 与 `OVERRIDES`                                                          | legacy `callees-of` 等价 recipe 必须包含显式 dispatch/projection |
| `SpringBeanParser` 对 abstract/parent/factory/nested bean 的覆盖有限，`BeanConfigService` 依赖现有扁平事实                       | Artifact 阶段必须先补 producer，不能只包一层 IR                  |
| `QueryService` 已在查询生命周期获取 `IndexLock` 读锁                                                                             | 新 cursor 必须继承并测试锁生命周期；revision 用于跨进程/重放校验 |

这些事实是实施门禁，不是推荐优化项。对应事实未落地时，上层字段只能标记实验性，不能进入稳定 contract。

## 三、分层架构

```text
┌──────────────────────────────────────────────────────────────┐
│ CLI / Pipe                                                   │
│ search | resolve | declarations-of | describe | calls | ... │
└──────────────────────────────┬───────────────────────────────┘
                               │ typed NDJSON records
┌──────────────────────────────▼───────────────────────────────┐
│ Semantic Query Layer                                         │
│ identity / containment / type / callable / call / dispatch   │
│ reference / access / control / trace / source                │
└──────────────────────────────┬───────────────────────────────┘
                               │ common contracts
┌──────────────────────────────▼───────────────────────────────┐
│ Capability Ports / Adapters                                  │
│ identity │ type │ call-site │ dispatch │ artifact │ source   │
└──────────────────────────────┬───────────────────────────────┘
                               │ storage primitives
┌──────────────────────────────▼───────────────────────────────┐
│ Query Backend                                                │
│ node lookup / relation scan / BFS / source snapshot / FTS    │
└──────────────────────────────┬───────────────────────────────┘
                               │
┌──────────────────────────────▼───────────────────────────────┐
│ SQLite immutable committed snapshot                          │
└──────────────────────────────────────────────────────────────┘
```

`node/edge/path` 只存在于 Query Backend。Agent 和公共 CLI 不直接依赖存储关系名。

## 四、跨语言 Semantic IR

### 4.1 通用实体引用

除 `search → resolve` 的候选边界外，实体型 operation 都从统一的 `entity` 记录开始：

```json
{
  "record": "entity",
  "contract": "semantic-stream/v1",
  "index_revision_id": "rev:...",
  "source_snapshot_id": "sha256:...",
  "semantic_profile_id": "sha256:...",
  "id": "type:...",
  "domain": "language",
  "language": "java",
  "kind": "type",
  "name": "PaymentGateway",
  "qualified_name": "com.example.PaymentGateway"
}
```

通用字段：

| 字段                  | 含义                                                                 |
| --------------------- | -------------------------------------------------------------------- |
| `record`              | 记录类型                                                             |
| `contract`            | 流协议版本                                                           |
| `index_revision_id`   | 一次已提交索引版本；管道一致性以此为准                               |
| `source_snapshot_id`  | 逻辑源码/资源身份与内容 hash；用于 source freshness                  |
| `semantic_profile_id` | Java 版本、classpath、scan policy、扩展及 graph semantics 等分析环境 |
| `id`                  | 稳定实体 ID                                                          |
| `domain`              | `language/configuration/documentation/build`                         |
| `language`            | `java/python/typescript/rust`；非语言制品可为空                      |
| `kind`                | 通用实体类别                                                         |
| `producer_id`         | 事实生产者                                                           |
| `origin`              | `extracted/configured/derived/observed`，说明事实从哪里来            |
| `resolution_status`   | `exact/heuristic/ambiguous/unresolved`，说明解析确定性               |
| `confidence`          | 可选的 `high/medium/low`；只有 producer 能解释其含义时才使用         |
| `source`              | 文件、范围和快照证据                                                 |
| `facets`              | 语言或扩展特有属性                                                   |
| `derived_from`        | 上游记录 ID 和产生本记录的 operation                                 |

`coverage` 不再与每条正向事实重复绑定。实现是否支持某操作由 capability registry 报告；某次查询是否完整、哪些维度受影响以及能否安全下否定结论，由 seed/stream evidence 报告。这样不会继续沿用当前 `EXTRACTED/CONFIGURED/INFERRED/AMBIGUOUS` 同时混合来源与确定性的历史问题。

三类身份的生成规则：

| 身份                  | 稳定条件                                                    | 变化条件                                       |
| --------------------- | ----------------------------------------------------------- | ---------------------------------------------- |
| `source_snapshot_id`  | 逻辑 source/resource identity 与内容相同                    | 源文件或资源内容/归属变化                      |
| `semantic_profile_id` | 语言版本、classpath、scope、extension、graph semantics 相同 | 任一分析输入变化                               |
| `index_revision_id`   | 只在同一次已提交数据库 revision 内相同                      | 任何成功提交的事实集合变化；失败构建不发布新值 |

实体/call-site/resource 的稳定 ID 不包含 `index_revision_id`，否则每次重建都会破坏跨 revision 对比。revision 可以是内容寻址 hash 或 opaque commit ID，但必须在原子提交完成后才对 reader 可见。

### 4.2 通用实体种类

第一版只定义稳定的大类，不试图统一所有语法节点：

```text
workspace
module
namespace
type
callable
value
parameter
artifact
configuration
document
control_region
```

具体形态保存在 facet：

```json
{
  "kind": "type",
  "facets": {
    "java": { "form": "interface" },
    "instantiability": { "state": "no", "reason": "java.interface" }
  }
}
```

Rust trait：

```json
{
  "kind": "type",
  "facets": {
    "rust": { "form": "trait" },
    "instantiability": { "state": "not_applicable", "reason": "rust.trait" }
  }
}
```

### 4.3 规范记录类型

| 记录                | 用途                                                      |
| ------------------- | --------------------------------------------------------- |
| `entity`            | 可继续传给下游的实体引用                                  |
| `declaration`       | 签名、修饰、泛型、声明来源                                |
| `containment`       | 容器和成员关系                                            |
| `type_relation`     | 子类型、符合、混入、别名等类型语义                        |
| `callable_relation` | override、contract implementation、specialization         |
| `binding_relation`  | 配置、构建制品和语言实体之间的跨域绑定                    |
| `reference_site`    | 某个符号的引用位置                                        |
| `call_site`         | 源码中的调用点及静态解析结果                              |
| `dispatch_target`   | 可能执行的目标和推断依据                                  |
| `access_site`       | value/field 的读取或写入                                  |
| `control_region`    | branch、loop、try、catch、closure 等区域                  |
| `trace`             | 一条调用、引用或控制路径                                  |
| `source_slice`      | 经过快照验证的源码证据                                    |
| `artifact`          | XML、YAML、TOML、JSON、文档等制品                         |
| `config_entity`     | component、property、entry、reference、literal 等配置节点 |
| `evidence`          | coverage、预算、截断和诊断汇总                            |

### 4.4 通用语义与语言机制并存

禁止只保存过于宽泛的 `edge=IMPLEMENTS`。类型关系应同时表达：

```json
{
  "record": "type_relation",
  "semantic": "CONFORMS_TO",
  "mechanism": "java.implements",
  "subject": "type:StripeGateway",
  "object": "type:PaymentGateway",
  "declared": true,
  "origin": "extracted",
  "resolution_status": "exact"
}
```

其他语言使用相同 semantic，不同 mechanism：

| 语言事实                 | `semantic`    | `mechanism`                  |
| ------------------------ | ------------- | ---------------------------- |
| Java `implements`        | `CONFORMS_TO` | `java.implements`            |
| Java class extends       | `SUBTYPE_OF`  | `java.extends_class`         |
| Java interface extends   | `SUBTYPE_OF`  | `java.extends_interface`     |
| Python class inheritance | `SUBTYPE_OF`  | `python.inherits`            |
| Python Protocol 推断     | `CONFORMS_TO` | `python.protocol_structural` |
| TypeScript `implements`  | `CONFORMS_TO` | `typescript.implements`      |
| TypeScript 结构化兼容    | `CONFORMS_TO` | `typescript.structural`      |
| Rust trait impl          | `CONFORMS_TO` | `rust.trait_impl`            |

### 4.5 实例化能力

不能使用一个跨语言的 `abstract` boolean。使用：

```text
instantiability.state = yes | no | unknown | not_applicable
```

并强制返回 `reason`：

```ndjson
{"state":"no","reason":"java.abstract_class"}
{"state":"unknown","reason":"python.dynamic_abstractness"}
{"state":"not_applicable","reason":"rust.trait"}
```

## 五、调用与多态模型

### 5.1 四类事实必须分开

```text
源码调用点
   │
   ├─ syntax target            语法上写了什么
   ├─ resolved target          编译期/静态分析解析到什么
   ├─ possible dispatch        可能执行哪些目标
   └─ observed runtime target  运行时证据确认了什么
```

Anatomist 静态索引只能直接提供前三类。第四类必须来自 trace、日志或外部运行时扩展。

### 5.2 `call_site`

```json
{
  "record": "call_site",
  "id": "callsite:sha256:...",
  "language": "java",
  "caller": "method:OrderService#placeOrder(...)",
  "syntax_target": "paymentGateway.pay",
  "receiver_static_type": "type:PaymentGateway",
  "resolved_target": "method:PaymentGateway#pay(...)",
  "dispatch_kind": "java.interface",
  "source": {
    "file": "OrderService.java",
    "start_line": 42,
    "start_column": 9,
    "end_line": 42,
    "end_column": 32,
    "ordinal": 2
  },
  "origin": "extracted",
  "resolution_status": "exact"
}
```

#### 索引事实前置条件

| 事实                 | 最低要求                                                           |
| -------------------- | ------------------------------------------------------------------ |
| call-site ID         | 由 source identity、AST range、同范围 ordinal 确定；重建索引后稳定 |
| source range         | 起止行列齐全，不能只保存 `L42`                                     |
| syntax target        | 保留源码写法，不能用 resolved target 反推                          |
| receiver static type | producer 提取；无法确定时明确 unresolved                           |
| resolution           | 静态目标、状态、来源分字段保存                                     |

当前 `edges.id` 是数据库自增 ID，调用位置主要是行号；同一 caller、同一行可以存在多个 `CALLS`。因此稳定 call-site 不是 DTO 映射问题，必须新增版本化 call-site 事实并提升 schema/graph semantics，不能塞进可选 metadata 后直接对外承诺。

### 5.3 `dispatch_target`

```json
{
  "record": "dispatch_target",
  "call_site": "callsite:...",
  "target": "method:StripePaymentGateway#pay(...)",
  "candidate_kind": "possible",
  "mechanism": "java.virtual_dispatch",
  "reason": ["java.implements", "java.overrides"],
  "instantiability": "yes",
  "origin": "derived",
  "resolution_status": "heuristic",
  "algorithm": "CHA",
  "world": "workspace_open"
}
```

`algorithm` 和 `world` 是候选集合的计算假设；`complete/partial/unknown` 属于本次查询的 evidence，不是 `world` 的别名。第一版至少区分：

| 维度           | 值                                               |
| -------------- | ------------------------------------------------ |
| algorithm      | `exact/CHA/RTA/configured`                       |
| world          | `workspace_closed/workspace_open/classpath_open` |
| candidate kind | `resolved/possible/observed`                     |

### 5.4 类型闭包不是同类边递归

`type-relations` 返回直接或同语义闭包事实。它不能把 `CONFORMS_TO` 和 `SUBTYPE_OF` 用一个 `--transitive` 含糊串起来：

```text
Interface ◀─ CONFORMS_TO ─ AbstractClass ◀─ SUBTYPE_OF ─ ConcreteClass
```

查询可实例化候选需要独立的派生操作 `runtime-implementations`（跨语言名称待 ADR 确认），由 adapter 明确定义关系组合、可实例化过滤和世界假设，并返回每一跳 proof。这样 `type-relations` 保持事实查询，运行时候选保持可解释推导。

### 5.5 不同语言的 dispatch

| 语言       | 适配器职责                                                                     |
| ---------- | ------------------------------------------------------------------------------ |
| Java       | static/special/final/virtual/interface、override closure、receiver static type |
| Python     | MRO、可解析属性、monkey patch 风险、unknown target                             |
| TypeScript | 类型期解析与运行时 JavaScript 目标分离                                         |
| Rust       | static generic dispatch、trait impl、`dyn Trait` candidates                    |

当索引无法证明完整时，必须返回 `partial` 或 `unknown`，不能把当前 workspace 中可见的候选宣称为完整运行时集合。

## 六、配置制品与 Spring Bean 文件

### 6.1 分层原则

Spring 不属于 Java 核心，但 Bean XML 是项目结构证据，应进入配置制品语义层：

```text
artifact(application-context.xml)
└─ config.component(orderService)
   ├─ REALIZES → language.type(OrderServiceImpl)
   └─ config.property(paymentGateway)
      └─ config.reference(stripeGateway)
         └─ RESOLVES_TO → config.component(stripeGateway)
```

### 6.2 通用记录

```ndjson
{"record":"artifact","domain":"configuration","format":"xml","path":"src/main/resources/application-context.xml","producer_id":"spring-xml"}
{"record":"config_entity","kind":"component","name":"orderService","mechanism":"spring.xml.bean","artifact":"artifact:..."}
{"record":"binding_relation","semantic":"REALIZES","mechanism":"spring.xml.class","subject":"config:orderService","object":"type:com.example.OrderServiceImpl"}
{"record":"config_entity","kind":"property","name":"paymentGateway","parent":"config:orderService"}
{"record":"config_entity","kind":"reference","value":"stripeGateway","parent":"config:property:paymentGateway"}
{"record":"reference_site","semantic":"RESOLVES_TO","mechanism":"spring.xml.ref","source":"config:ref:stripeGateway","target":"config:stripeGateway"}
```

### 6.3 查询方式

文件查组件：

```bash
anatomist search '*/application-context.xml' --domain configuration --kind artifact |
anatomist resolve --unique |
anatomist members --kind component
```

组件查完整配置树：

```bash
anatomist search orderService --domain configuration --kind component |
anatomist resolve --unique |
anatomist members --recursive
```

组件查源文件：

```bash
anatomist search orderService --domain configuration --kind component |
anatomist resolve --unique |
anatomist source
```

语言类型查对应配置：

```bash
anatomist search com.example.OrderServiceImpl --kind type |
anatomist resolve --unique |
anatomist bindings --direction incoming --semantic realizes
```

### 6.4 扩展边界

| 能力                                            | 核心是否理解             |
| ----------------------------------------------- | ------------------------ |
| artifact、config entity、containment、reference | 是，通用结构             |
| `spring.xml.bean` 的具体生成规则                | 否，由 producer 负责     |
| profile、conditional、proxy 的运行时选择        | 否，除非有外部运行时证据 |
| XML map/list 顺序与 key                         | 是，作为配置树属性保留   |

### 6.5 当前基线缺口

Stage 4 不是把现有 Bean DTO 改名。当前 producer 与目标 Artifact IR 之间至少缺少：

| 缺口                                    | 必须补齐的索引事实                               |
| --------------------------------------- | ------------------------------------------------ |
| 文件没有 artifact 根实体                | 稳定 `ResourceIdentity`、artifact 节点、资源快照 |
| abstract/parent/factory bean 未完整建模 | 原始声明、继承/factory 机制和明确 coverage       |
| nested bean 语义不完整                  | 父子 containment 和稳定子节点身份                |
| map/list 顺序隐含在字符串 ID/查询排序中 | 数值 `ordinal` 一等字段                          |
| XML 来源粒度不足                        | 元素/属性的精确 source range                     |
| class/ref 解析边界不透明                | resolution status、scope、未解析原因             |

资源文件的 source root 和 freshness 校验必须独立于 Java source identity。Spring 注解可以由 producer 生成 `config_entity/binding_relation`，但来源仍是 Java annotation，不能伪装成 XML artifact。

现有 `bean-config` 作为兼容命令保留，其规范展开形式为：

```text
search --domain configuration --kind component | resolve | members --recursive
```

## 七、原子命令设计

不人为限制命令数量。命令边界由输入记录、输出记录和语义职责决定。

| 命令                      | 输入                             | 输出                                 | 唯一职责                                |
| ------------------------- | -------------------------------- | ------------------------------------ | --------------------------------------- |
| `search`                  | CLI selector 或上游范围          | `entity_candidate`                   | 模糊检索候选，不做唯一性承诺            |
| `resolve`                 | `entity_candidate/entity`        | `entity`                             | 精确解析、消歧和唯一性门禁              |
| `declarations-of`         | file/resource entity             | `entity/declaration`                 | 从变更文件建立稳定声明 seed             |
| `describe`                | `entity`                         | `declaration/artifact/config_entity` | 返回实体自身事实                        |
| `members`                 | 容器 `entity`                    | `entity/containment`                 | 枚举包含成员                            |
| `type-relations`          | type entity                      | `type_relation`                      | 直接类型事实或单一语义闭包              |
| `runtime-implementations` | type entity                      | `entity/type_relation proof`         | 按 adapter 规则组合关系并筛选可运行候选 |
| `callable-relations`      | callable entity                  | `callable_relation`                  | override、contract implementation 等    |
| `bindings`                | 任意 domain entity               | `binding_relation`                   | 配置/构建制品与语言实体的跨域绑定       |
| `annotations`             | language entity                  | annotation records                   | 返回源码注解事实                        |
| `related-docs`            | entity                           | document relation                    | 返回关联文档，不读取正文                |
| `references`              | entity                           | `reference_site`                     | 符号或配置引用                          |
| `calls`                   | callable entity                  | `call_site`                          | 源码调用点和静态目标                    |
| `dispatch`                | `call_site`                      | `dispatch_target`                    | 展开可能执行目标                        |
| `accesses`                | value entity                     | `access_site`                        | 读取和写入位置                          |
| `regions`                 | callable entity                  | `control_region`                     | 条件、循环、异常和 closure 区域         |
| `sites-in`                | `control_region`                 | site records                         | 枚举区域内的调用、引用或访问点          |
| `trace`                   | start entity + 显式 end selector | `trace`                              | 有界路径查询                            |
| `source`                  | entity/site/relation             | `source_slice`                       | 返回快照验证源码                        |
| `overview`                | workspace/module entity          | aggregation records                  | 项目聚合，不参与细粒度推断              |

### 7.1 命令约束

- `search` 只检索候选，不负责消歧；需要唯一实体时必须经过 `resolve --unique`。
- `resolve` 接受精确 ID/签名或候选流，不做模糊召回。
- `declarations-of` 保留当前 changed-file 工作流，不能退化为文件名模糊搜索。
- `describe` 不执行关系遍历。
- `calls` 使用 `--direction outgoing|incoming` 表达方向，不增加同义反向命令。
- `calls` 不隐式加入 override candidate。
- `dispatch` 不伪造源代码中的 `CALLS`。
- `type-relations --transitive` 只允许 adapter 声明可同类组合的关系；混合关系闭包进入 `runtime-implementations`。
- `source` 只做证据投影和新鲜度验证。
- `trace` 必须用 stdin 提供起点，并显式提供终点与 resolved/possible dispatch policy。
- 每个命令的 `--help` 必须列出接受的 input record 和输出 record。

### 7.2 示例

查找具体实现及源码：

```bash
set -o pipefail
anatomist search PaymentGateway --kind type |
anatomist resolve --unique |
anatomist runtime-implementations --instantiability yes |
anatomist source
```

查询调用点、展开多态并取证：

```bash
set -o pipefail
anatomist resolve 'OrderService#placeOrder(...)' --kind callable --exact --unique |
anatomist calls --direction outgoing |
anatomist dispatch --candidates |
anatomist source
```

查询分支内的调用：

```bash
set -o pipefail
anatomist resolve 'OrderService#placeOrder(...)' --kind callable --exact --unique |
anatomist regions --kind branch |
anatomist sites-in --record call_site |
anatomist source
```

## 八、NDJSON 管道协议

### 8.1 为什么不是完整 JSON Envelope

完整 JSON 文档需要等待所有结果生成后才能关闭对象，不利于：

- 流式输出；
- backpressure；
- 大结果集；
- 与 `jq`、`awk`、`head` 等 Unix 工具配合；
- 下游尽早开始处理。

默认使用 NDJSON：

```text
一行 = 一条完整 JSON record
```

### 8.2 stdout、stderr 和退出码

| 通道   | 内容                               |
| ------ | ---------------------------------- |
| stdout | 仅 NDJSON 数据与最终 evidence 记录 |
| stderr | 人类可读诊断、进度、弃用提示       |
| exit 0 | 操作成功，包括经过证明的空结果     |
| exit 2 | 参数、管道输入类型或 selector 错误 |
| exit 3 | 索引过期、能力不支持、证据门禁失败 |
| exit 4 | 管道快照冲突或上游不完整           |

具体退出码应与当前 CLI 约定统一后写入公共常量，以上数值在 ADR 阶段确认。

### 8.3 数据记录

每条数据记录必须包含：

```json
{
  "record": "entity",
  "contract": "semantic-stream/v1",
  "seed_id": "seed:1",
  "index_revision_id": "rev:...",
  "source_snapshot_id": "sha256:...",
  "semantic_profile_id": "sha256:...",
  "producer_id": "java-core",
  "origin": "extracted",
  "resolution_status": "exact"
}
```

`seed_id` 让下游可以把数据、诊断和 evidence 归回输入实体。将 `index_revision_id` 放在每条记录上，使记录经过标准 Unix 工具过滤后仍可校验数据库版本；只有依赖源码新鲜度的记录才必须携带 `source_snapshot_id`，但同一流的 semantic profile 仍必须一致。

### 8.4 结束记录

每个输入 seed 都必须有一条 `scope=seed` evidence，即使没有数据；整条流最后必须且只能有一条 `scope=stream` evidence。下游只有读到最终 stream evidence 后，才可把此前数据当作完整查询结果。

seed 边界固定如下，具体编码在阶段 0 ADR 冻结：

| operation 形态                             | seed 规则                                                     |
| ------------------------------------------ | ------------------------------------------------------------- |
| CLI selector source                        | 每个 selector 创建一个 root seed                              |
| group operation（`resolve`）               | 消费同一 seed 的候选组并保留 seed ID                          |
| map/expand operation（`calls/source/...`） | 每条输入数据创建 child seed，并携带 `parent_seed_id`          |
| 多父输入                                   | v1 禁止；`trace` 的另一 endpoint 用显式参数，不从第二条流合并 |

因此两个候选、两个 callable 或两种语言不会共用一条局部结论；stream evidence 只做汇总，不能覆盖更具体的 seed evidence。

```json
{
  "record": "evidence",
  "contract": "semantic-stream/v1",
  "scope": "seed",
  "seed_id": "seed:1",
  "index_revision_id": "rev:...",
  "status": "positive",
  "coverage": "complete",
  "emitted": 12,
  "truncated": false,
  "next": null
}
```

空结果：

```json
{
  "record": "evidence",
  "scope": "seed",
  "seed_id": "seed:2",
  "index_revision_id": "rev:...",
  "status": "empty",
  "coverage": "complete",
  "negative_conclusion_safe": true,
  "emitted": 0,
  "truncated": false
}
```

不支持：

```json
{
  "record": "evidence",
  "scope": "seed",
  "seed_id": "seed:3",
  "index_revision_id": "rev:...",
  "status": "unsupported",
  "coverage": "unknown",
  "code": "UNSUPPORTED_CAPABILITY",
  "negative_conclusion_safe": false
}
```

流结束：

```json
{
  "record": "evidence",
  "scope": "stream",
  "index_revision_id": "rev:...",
  "status": "partial",
  "coverage": "partial",
  "seeds": { "total": 3, "positive": 1, "empty": 1, "unsupported": 1 },
  "emitted": 12,
  "negative_conclusion_safe": false
}
```

默认 `--on-unsupported fail`；只有显式 `--on-unsupported continue` 才允许处理其他 seed，并把 stream evidence 降为 partial。命令中途失败时已输出的数据不得被调用者当作可信终态。

### 8.5 下游校验

Anatomist 下游命令必须：

1. 校验输入记录类型；
2. 校验所有输入 `index_revision_id` 一致且等于当前已提交 revision；
3. 在需要源码时校验 `source_snapshot_id`，在执行语义操作时校验 `semantic_profile_id`；
4. 按 `seed_id` 跟踪数据和 seed evidence；
5. 检查是否收到且只收到一条上游最终 stream evidence；
6. 上游 truncated/partial/unsupported 时，逐 seed 传播限制并汇总为最保守 stream evidence；
7. 不因某个 seed 空结果而报 selector not found；
8. 不接受不同项目、revision 或 semantic profile 的记录混合。

若索引在管道阶段之间发生更新：

```text
INDEX_CHANGED_DURING_PIPELINE
```

必须失败，不能组合两个快照的事实。

每个 semantic CLI 进程必须在读索引期间持有共享 `IndexLock`，直到 cursor 关闭或管道结束；集成测试要证明 writer 在该期间被阻塞。revision 校验仍然必要，因为它保护重放文件、`--accept-unframed` 和跨时段消费，不由进程锁替代。

### 8.6 流式、资源和缓存

- Query Backend 必须返回 `AutoCloseable` pull cursor；不能先构造 `List`，也不能把裸 Java `Stream` 作为 SPI 资源所有权契约。
- `search/describe/members/source` 应逐条消费和输出，ResultSet 在 EOF、异常和 SIGPIPE 时关闭。
- `type-relations/dispatch/trace/runtime-implementations` 可以为闭包和去重进行有界缓存。
- 所有递归操作必须具有 depth、row 和 compute budget。
- reader 必须限制单行字节数、JSON nesting depth 和单 seed 记录数。
- `head` 导致 broken pipe 时应安静退出，不打印 Java stack trace。
- stdout 禁止颜色和日志。
- CLI 参数和 stdin 同时存在时，必须定义清楚是 seed、filter 还是非法组合。

### 8.7 证据血缘

管道转换不能让最终结果失去上游语义。每条派生记录必须包含有界血缘：

```json
{
  "record": "source_slice",
  "subject": {
    "id": "method:StripePaymentGateway#pay(...)",
    "kind": "callable",
    "qualified_name": "com.example.StripePaymentGateway#pay(...)"
  },
  "derived_from": [
    { "record": "dispatch_target", "id": "dispatch:..." },
    { "record": "call_site", "id": "callsite:..." }
  ],
  "source": {
    "file": "StripePaymentGateway.java",
    "start_line": 20,
    "end_line": 35
  }
}
```

约束：

- `derived_from` 保存稳定 ID 和必要标签，不复制完整上游大对象；
- `source_slice` 必须保留被取证 subject；
- `dispatch_target` 必须保留 call-site ID、caller 和 resolved target；
- `trace` 必须保留每一跳的 source、target、mechanism 和证据位置；
- 血缘超过预算时返回 continuation，不允许静默截断。

### 8.8 与标准 Unix 工具组合

允许：

```bash
anatomist search Payment --kind type |
jq -c 'select(.record == "entity_candidate" and .language == "java")' |
anatomist resolve --unique --accept-unframed |
anatomist describe
```

`--accept-unframed` 必须显式使用，因为 `jq` 可能删除 evidence 结束记录，导致下游无法证明覆盖度。默认模式只接受完整 Anatomist stream。

### 8.9 一次请求的边界

本方案中的“一次请求”指 Agent 只发起一次 shell 执行：

```text
一次 Agent tool call
       │
       └─ shell pipeline
            ├─ anatomist process 1
            ├─ anatomist process 2
            └─ anatomist process 3
```

它不等于单进程，也不天然保证多个并行分支共享一个 SQLite transaction。第一版只支持线性 stream：

- 每条记录携带 revision；
- 下游发现 revision 变化立即失败；
- 线性分析优先使用单条 pipeline；
- `tee` 产生的多个终止 evidence 不能直接重新拼接成合法 stream；
- 需要分支时分别执行并分别判断，不在 v1 中承诺 fan-out/fan-in；
- 不为复杂 DAG 立即发明 JSON Plan DSL。

如果 benchmark 或 E2E 证明多进程和分支处理成为主要问题，再设计带正式 `merge` 语义的 fused executor。它必须定义 seed ID 冲突、evidence 合并、预算和失败传播，并复用相同 operation/record contract，不能形成第二套查询语义。

## 九、语言能力声明

每个语言适配器必须公开 capability matrix：

```json
{
  "language": "python",
  "capabilities": {
    "declarations": { "support": "supported" },
    "type_relations": {
      "support": "supported",
      "limitations": ["dynamic-base"]
    },
    "call_resolution": {
      "support": "supported",
      "limitations": ["dynamic-attribute"]
    },
    "dispatch_candidates": {
      "support": "supported",
      "limitations": ["monkey-patch", "runtime-import"]
    },
    "access_sites": { "support": "supported" },
    "control_regions": { "support": "supported" }
  }
}
```

静态能力状态只有：

```text
supported | unsupported
```

`complete/partial/unknown` 是某个 index revision 上、某次 operation 的 evidence coverage。安装了 adapter 只说明“会做”，不说明当前 classpath/source scope 足以得到完整答案。

约束：

- 命令在执行前检查 capability。
- `unsupported` 必须非零退出，除非调用者显式允许。
- limitation 必须在相关 seed evidence 中落成实际 coverage 与原因，不能只停留在静态声明。
- 混合语言结果按 seed 分别报告 coverage，stream 汇总取最保守值。
- `doctor` 暴露已安装语言、版本和能力。

## 十、当前命令迁移映射

| 当前命令                 | 新规范操作                                                                           | 兼容策略                                                           |
| ------------------------ | ------------------------------------------------------------------------------------ | ------------------------------------------------------------------ |
| `search`                 | `search`                                                                             | 保留为唯一模糊检索入口                                             |
| `declarations-of`        | `declarations-of`                                                                    | 保留稳定 changed-file seed 能力，不用模糊搜索模拟                  |
| `context`                | `describe` + `members` + `annotations` + `related-docs`                              | 按旧参数投影，旧输出保持 v2                                        |
| `context --source`       | `source`                                                                             | 旧入口保留                                                         |
| `context --with-callees` | `calls`                                                                              | 不再推荐                                                           |
| `context --enrich`       | shell pipeline recipe                                                                | 不在新核心中复制万能命令                                           |
| `callees-of`             | 固定 recipe：`calls` + `dispatch` + legacy projection                                | 当前实现混合 CALLS/OVERRIDES，不能把 dispatch 变成可选后仍声称等价 |
| `callers-of`             | `calls --direction incoming`                                                         | 只保留旧反向入口                                                   |
| `branches-of`            | `regions --kind branch` + `sites-in`                                                 | 保留旧聚合输出                                                     |
| `hierarchy`              | `type-relations --direction outgoing`                                                | 保留别名                                                           |
| `implementors-of`        | `runtime-implementations`                                                            | 显式组合 conforms/subtype proof 并区分 abstract/concrete           |
| `deps-of`                | `references/calls`                                                                   | 不再混合多种关系                                                   |
| `used-by`                | `references` 或 `calls --direction incoming`                                         | 由输入类型决定可用操作                                             |
| `field-access`           | `accesses`                                                                           | read/write 作为过滤维度                                            |
| `call-path`              | `trace --via calls`                                                                  | 显式 dispatch policy                                               |
| `bean-config`            | `search --domain configuration --kind component` + `resolve` + `members --recursive` | 框架兼容入口                                                       |
| `overview`               | `overview`                                                                           | 保留但统一 stream contract                                         |
| `survey-baseline`        | 文档化 shell recipe                                                                  | 暂不增加 plan DSL                                                  |

以下命令不属于查询原子化范围：

```text
index / index-docs / annotate / doctor / skill / help / export
```

阶段 0 必须冻结“旧参数组合 → canonical recipe → v2 projection”的逐项矩阵。等价比较以当前真实行为为基线，不能只凭相似命令名映射。

## 十一、代码实施方案

### 11.1 包结构建议

```text
com.anatomist.query.semantic
├─ model             通用记录和枚举
├─ capability        语言/扩展能力声明
├─ port              按能力拆分的查询 SPI
├─ java              Java 语义映射
├─ artifact          配置、文档、构建制品语义
└─ evidence          覆盖度和快照传播

com.anatomist.query
├─ operation         原子语义操作
├─ stream            NDJSON reader/writer 和管道校验
└─ legacy            旧命令适配

com.anatomist.cli
├─ SearchCommand
├─ ResolveCommand
├─ DescribeCommand
├─ MembersCommand
├─ TypeRelationsCommand
├─ RuntimeImplementationsCommand
├─ CallableRelationsCommand
├─ BindingsCommand
├─ CallsCommand
├─ DispatchCommand
├─ AccessesCommand
├─ RegionsCommand
├─ SitesInCommand
├─ TraceCommand
└─ SourceCommand
```

现有 `com.anatomist.semantic` 已用于索引期 semantic post-processing。查询公共契约先放在 `com.anatomist.query.semantic`，避免把写入期与读取期生命周期混成一个包；若后续确需共享，只提升无存储依赖的 value types。

是否采用该物理包名可在第一阶段调整，但依赖方向必须保持：

```text
CLI → semantic operation → adapter/backend

禁止：backend → CLI
禁止：Java adapter → Spring producer
禁止：核心 model 引用具体框架类
```

### 11.2 按能力拆分查询 SPI

单个 `SemanticAdapter` 会同时承担 identity、类型、调用、控制流、配置绑定和 source，很快变成新的 god interface。按 capability 拆端口，adapter 只实现自己支持的组合：

| Port                | 职责                                                |
| ------------------- | --------------------------------------------------- |
| `EntityLookupPort`  | search、resolve、declarations-of、describe、members |
| `TypeSemanticsPort` | type/callable relations、runtime implementations    |
| `CallSitePort`      | calls、references、accesses、regions、sites-in      |
| `DispatchPort`      | 按 algorithm/world 展开候选和 proof                 |
| `ArtifactPort`      | artifact/config tree 与跨域 binding                 |
| `SourcePort`        | source identity、freshness 和 slice                 |

统一返回 `SemanticCursor<T> extends AutoCloseable`，显式拥有 ResultSet/连接释放责任；operation 负责预算、evidence 和 record 转换。SPI 不直接暴露 JDBC、SQL 表名、当前 `EdgeRow`，也不使用难以表达资源所有权的裸 `Stream<T>`。

### 11.3 Java 适配器第一阶段

复用现有事实：

- nodes 的 kind、qualified name、module、scope；
- declarations 的 modifiers、declared modifiers、implicit modifiers；
- `IMPLEMENTS/INHERITS/OVERRIDES`；
- `CALLS` 的 call kind、confidence、source location 和 context；
- `READS/WRITES/REFERENCES/CONTAINS`；
- source snapshot verification；
- Lombok synthetic origin 和 coverage。

需要补齐：

| 缺口                                       | 处理                                                    |
| ------------------------------------------ | ------------------------------------------------------- |
| implementor 结果不区分抽象和具体           | join declaration modifiers，计算 instantiability        |
| 接口方法覆盖接口方法被跳过                 | 补齐 callable relation extraction                       |
| CALLS 与 override candidate 混在 traversal | 将 dispatch expansion 移入 `DispatchService`            |
| 调用点没有稳定身份且仅有粗粒度位置         | 新增 call-site 事实、精确 AST range 和 deterministic ID |
| 调用点缺少清晰 receiver static type        | producer 落库；查询时不得靠目标反推                     |
| default interface method 不明确            | 输出 callable implementation status                     |
| external subtype 可能性不透明              | evidence 报告 coverage；dispatch 单列 algorithm/world   |
| bridge/generic 关系可能重复                | canonical callable identity + specialization facet      |
| Lombok 合成声明                            | 保留 synthetic origin，不伪装成 source declaration      |
| 一个 hash 混合多类身份                     | 分离 index revision、source snapshot、semantic profile  |

若索引事实发生变化：

- 提升 `graph_semantics_version`；
- 要求重新索引；
- call-site、resource identity、ordinal/source range 作为一等事实时显式提升 schema 版本；
- 不为减少迁移成本而混用新旧分派语义。

### 11.4 Artifact Adapter

Artifact Adapter 将现有 Spring XML 结构映射成：

```text
artifact
config_entity
containment
reference_site
binding_relation(REALIZES)
```

映射前必须先升级 producer：创建 artifact 根、保留数值 ordinal 和精确范围，并对 abstract/parent/factory/nested bean 的未建模部分给出 coverage。否则 adapter 只能美化输出，不能满足目标契约。

核心不注册 `spring` 命令，只注册 producer capability：

```json
{
  "producer_id": "spring-xml",
  "domain": "configuration",
  "capabilities": [
    "components",
    "nested-values",
    "ordered-collections",
    "references"
  ]
}
```

未来 MyBatis XML、Kubernetes YAML、Cargo.toml 可复用同一 Artifact IR，但各自保留 mechanism。

### 11.5 旧命令适配

旧命令在兼容期有两种实现方式：

1. 调用新的 semantic operation，再转换成 v2 envelope；
2. 暂时调用旧服务，但在测试中验证其结果与 canonical operation 等价。

优先选择第一种。禁止长期维护两套独立 SQL 和遍历算法。

旧 JSON stdout 不应加入弃用文本。提示只能写 stderr 或 help。

## 十二、分阶段实施

### 阶段 0：建立基线与 ADR

交付物：

- 当前命令能力和重叠矩阵；
- `semantic-stream/v1` ADR；
- Semantic IR 与 Artifact IR JSON Schema；
- 三类身份、逐 seed evidence、线性 stream 和失败传播规则；
- 类型关系组合、dispatch algorithm/world 规则；
- Java/Python/TypeScript/Rust capability matrix；
- 旧命令参数组合到 canonical recipe/v2 projection 的完整矩阵；
- 当前 E2E、性能和 native-image 基线。

验收：

- 每个现有查询能力都有去向；
- 没有核心字段以 Spring 命名；
- 没有核心字段假设所有语言都有 interface/abstract；
- 每个规范 operation 有唯一输入输出类型。
- `search/resolve/declarations-of`、`regions/sites-in` 边界无歧义。

建议 commit：

```text
docs(query): define language-neutral query semantics
```

### 阶段 1：版本化索引事实

交付物：

- committed `index_revision_id`、`source_snapshot_id`、`semantic_profile_id`；
- 稳定 call-site ID、精确 AST range、ordinal、syntax target、receiver static type；
- artifact/resource identity、containment ordinal 和精确 source range；
- schema/graph semantics migration 与强制重建诊断。

验收：

- 同一源码、相同 profile 的完整重建保持实体/call-site/resource ID 稳定；
- 同一行两个调用点可区分；
- source 不变但 profile 改变时，source snapshot 不变、semantic profile 和 index revision 改变；
- incremental 与 full rebuild 产生等价事实；
- 旧 schema 被明确拒绝，不静默降级。

建议 commit：

```text
feat(index): add versioned semantic fact identities
```

### 阶段 2：流协议、通用模型与真实流式后端

交付物：

- semantic record sealed model；
- NDJSON streaming reader/writer；
- record-dispatched typed decoder；
- `AutoCloseable` cursor backend；
- revision 和逐 seed/stream evidence propagation；
- input type validation；
- capability registry；
- 统一错误码；
- `--format ndjson|json|table` 的格式边界。

验收：

- 100k 条 fixture 可以有界内存流式通过；
- 空结果仍有 evidence；
- 缺少结束记录能够被识别；
- 混合 revision/profile 立即失败；
- 单行大小、嵌套深度和 seed 预算受限；
- 100k 条测试证明底层没有先物化 `List`；
- broken pipe 不打印异常栈；
- native-image 可运行。

建议 commit：

```text
feat(query): add typed semantic stream protocol
```

### 阶段 3：Java 最小垂直切片与语义扩展

交付物：

- 第一条切片：`resolve → calls → source`；
- 仅支撑该切片的实验性 CLI 与 stdin/stdout contract；
- entity/declaration/containment 和精确 call-site 映射；
- 第二条切片：type relation、runtime implementations、instantiability；
- callable relation 与 dispatch；
- reference/access/control/source 映射；
- coverage 和 external-world 边界。

验收：

- 接口、抽象类、具体类分组正确；
- default interface method、override、final/static/super 正确；
- `calls` 不再混入 inferred override hop；
- `dispatch` 能解释每个候选的推导路径；
- 不完整解析不会产生安全的否定结论。

建议拆分 commit：

```text
refactor(query): add Java type semantic adapter
refactor(query): separate call sites from dispatch targets
```

### 阶段 4：配置制品和兼容层

交付物：

- Spring XML producer 事实升级并映射到 Artifact IR；
- XML map/list/key/order/source range 保真；
- 其余原子 CLI、help contract 和 pipeline examples；
- 旧 `bean-config` 适配；
- 其他旧命令到 canonical operation 的适配；
- help 中的兼容和替代说明。

验收：

- 核心 CLI 不出现新的 Spring 专用命令；
- Bean 文件、Bean、property、ref、Java type 可以双向查询；
- `search → resolve → calls → dispatch → source` 可由一条线性 pipeline 完成；
- 旧命令 v2 JSON 保持兼容；
- canonical pipeline 与旧命令的有效数据等价。

建议 commit：

```text
refactor(query): map configuration artifacts to semantic records
refactor(cli): route legacy queries through semantic operations
```

### 阶段 5：Agent 文档、E2E、benchmark 与发布

交付物：

- SKILL.md 使用新命令决策树；
- `--help` 明确管道类型；
- README、AGENTS.md、commands、data-model、testing 文档；
- Jury Agent E2E；
- native binary benchmark；
- 兼容和弃用时间表。

验收：

- Agent 在索引 gate 之后，用一次 shell pipeline 完成复杂查询；
- E2E 断言语义记录和证据，不断言某个旧命令名；
- 新命令在 JVM 和 native binary 下结果一致；
- 性能达到基线阶段确定的门槛。

`0.16.x` 承诺 stream contract、索引身份、Java semantic operation 与 Spring XML
Artifact IR。Legacy v2 入口继续兼容；未实现的跨语言 frontend 不得仅凭 contract
fixture 宣称已支持。

建议 commit：

```text
test(e2e): cover agent semantic query pipelines
docs(query): document canonical pipeline workflow
```

## 十三、测试方案

### 13.1 单元测试

#### Stream codec

| 用例                             | 预期                                         |
| -------------------------------- | -------------------------------------------- |
| 单条/多条 NDJSON                 | 顺序保持，逐条解码                           |
| UTF-8、换行、泛型签名            | 不破坏记录边界                               |
| 未知字段                         | 向前兼容或明确拒绝，由契约决定               |
| 未知 record                      | 返回 `UNSUPPORTED_RECORD_TYPE`               |
| 缺少 revision/profile            | 默认拒绝                                     |
| 混合 revision/profile            | `INDEX_CHANGED_DURING_PIPELINE`              |
| 每 seed 有独立 evidence          | 数据、空、partial、unsupported 不串 seed     |
| 缺少/重复/非末尾 stream evidence | `UPSTREAM_INCOMPLETE`                        |
| 上游 truncated                   | 下游 evidence 传播 truncated                 |
| 空数据 + complete evidence       | 安全空结果                                   |
| unsupported fail/continue        | 默认失败；显式 continue 后 stream 为 partial |
| malformed JSON、超长行、过深嵌套 | 有界失败，不继续消费不可信数据               |
| calls → dispatch → source        | 最终 source 保留 call-site 和 target 血缘    |
| 血缘超过预算                     | 明确 truncated/continuation，不无限嵌套      |
| broken pipe                      | 无 stack trace                               |

#### Capability

| 用例                                     | 预期                                   |
| ---------------------------------------- | -------------------------------------- |
| supported capability + complete evidence | 正常执行，可在条件满足时安全下否定结论 |
| supported capability + partial evidence  | 返回结果并披露实际缺失边界             |
| unsupported capability                   | 非零退出，不伪装为空                   |
| mixed-language stream                    | 分语言执行并合并最保守 coverage        |

#### Java type semantics

至少覆盖：

- interface → concrete direct implementation；
- interface → abstract implementation → concrete subclass；
- interface extends interface；
- abstract class extends abstract class；
- concrete class without explicit constructor；
- enum、record、anonymous class；
- sealed class/interface 和 permitted subclasses；
- external superclass/interface；
- 泛型父类型和 erased identity；
- 同名类型跨 module/scope 消歧。

#### Callable semantics

至少覆盖：

- class override；
- interface method implementation；
- interface default method；
- interface extends interface 后的方法关系；
- abstract method → concrete method；
- static/final/private 不进入 virtual candidates；
- `super.method()`；
- overload 与 override 区分；
- covariant return；
- generic specialization/bridge；
- lambda、anonymous class、callback；
- reflection 的 inferred/ambiguous target。

#### Call-site identity

至少覆盖：

- 同一 caller、同一行两个调用具有不同 ID；
- full rebuild 与 incremental rebuild 后 ID 稳定；
- range 或调用顺序改变后 ID 按契约变化；
- syntax target、receiver static type、resolved target 可独立为空或有值；
- call-site ID 能贯穿 calls → dispatch → source。

#### Lombok

至少覆盖：

- 合成 getter/setter/builder 的 `synthetic_origin`；
- fluent/chain accessor 的真实名称；
- modeled capability 产生可查询声明；
- partial/unmodeled capability 不形成安全否定；
- 合成 callable 参与 calls/dispatch 时保留 provenance。

#### Artifact semantics

至少覆盖：

- 单个 XML bean；
- constructor arg；
- property；
- nested bean；
- list 顺序；
- map entry key/value；
- ref、idref、null、literal；
- 同名 bean 跨 module；
- XML bean → Java type；
- Java type → XML bean；
- source range；
- artifact/resource identity；
- 数值 ordinal 在查询和重建后稳定；
- abstract/parent/factory bean 的事实或明确 coverage；
- profile/conditional 未建模时 coverage 不完整。

### 13.2 契约测试

每个命令建立输入输出契约表：

| 命令             | 合法输入                      | 非法输入示例             |
| ---------------- | ----------------------------- | ------------------------ |
| `members`        | container entity              | call site                |
| `type-relations` | type entity                   | field/value              |
| `dispatch`       | call site                     | callable entity          |
| `accesses`       | value entity                  | artifact                 |
| `sites-in`       | control region                | callable entity          |
| `source`         | 有 source identity 的任意记录 | 无来源的 external entity |

契约测试必须验证：

- 错误码稳定；
- stdout 没有日志；
- 每行是合法 JSON；
- 最后一行是 evidence；
- help 声明和真实 reader/writer 类型一致；
- JVM/native 输出字段一致；
- JSON Schema 校验通过。

### 13.3 旧命令等价测试

对每个旧命令建立 canonical equivalence：

```text
legacy command normalized facts
                 ==
canonical pipeline normalized facts
```

比较时允许 envelope 和显示顺序不同，但不允许：

- 有效节点、关系或源码证据缺失；
- resolution status/confidence 被提升；
- coverage 被提升；
- external/internal 身份改变；
- module/scope 混合。

### 13.4 多语言契约 fixture

第一阶段不要求实现三个新索引器，但必须用手工 Semantic IR fixture 证明核心命令没有 Java 假设：

| Fixture    | 核心场景                                         |
| ---------- | ------------------------------------------------ |
| Python     | MRO、Protocol、动态 dispatch unknown             |
| TypeScript | interface、structural conformance、type erasure  |
| Rust       | trait impl、generic static dispatch、`dyn Trait` |

验收标准：新增 fixture 不修改核心 operation/codec，只增加 adapter 或测试数据。这只证明公共词汇和协议没有 Java 假设；只有真实 frontend → storage → query E2E 才能宣称支持该语言。

### 13.5 CLI 集成测试

覆盖：

- 参数 seed；
- stdin seed；
- 多个上游实体；
- 模块和 scope 过滤；
- 精确签名；
- 歧义输入；
- 空结果；
- 分页和预算；
- source stale；
- index schema/graph semantics mismatch；
- revision/profile mismatch；
- query 持有读锁期间 writer 被阻塞，EOF/SIGPIPE 后锁被释放；
- 管道中途失败；
- Unix `head`、`jq -c`、重定向文件；
- zsh/bash 下 `set -o pipefail`。

### 13.6 真实 Agent E2E

继续使用复杂多模块 fixture 和 Jury，覆盖以下闭环：

```text
项目基线
  ↓
模糊搜索
  ↓
模块/作用域消歧
  ↓
类型关系和具体候选
  ↓
调用点
  ↓
多态分派
  ↓
控制分支
  ↓
配置制品引用
  ↓
源码证据
  ↓
最终业务结论
```

建议场景：

| 场景                            | 关键断言                                                      |
| ------------------------------- | ------------------------------------------------------------- |
| 多模块重名 Service              | Agent 不静默选择错误模块                                      |
| interface + abstract + 两个实现 | 区分 hierarchy intermediate 和 runtime candidate              |
| callback/template method        | 调用点和 callback provenance 保留                             |
| XML map/list 驱动策略顺序       | 配置树 key/order 不丢失                                       |
| test scope 有额外实现           | MAIN/TEST coverage 分开                                       |
| 外部依赖可能有实现              | 不宣称候选集合完整                                            |
| stale source                    | pipeline 明确失败，不返回旧源码                               |
| 修改代码闭环                    | changed file declarations → impact → edit → re-index → verify |

Agent Oracle 不检查“必须调用 `callees-of`”，改为检查：

- 是否使用 canonical record；
- 是否正确处理 ambiguity；
- 是否区分 resolved target 和 dispatch candidate；
- 是否引用 source-backed evidence；
- 是否读取并尊重 coverage/truncation；
- 最终业务结论是否由返回事实支持。

### 13.7 性能测试

先建立基线，不预设没有测量依据的提升百分比。

比较组：

```text
A：当前多次独立 CLI 调用
B：新命令多进程 shell pipeline
C：单个原子命令对应旧命令
```

指标：

| 指标                         | 含义                           |
| ---------------------------- | ------------------------------ |
| wall time p50/p95            | Agent 等待时间                 |
| time to first record         | 流式收益                       |
| JVM/native startup           | 多进程管道成本                 |
| SQLite open count/time       | 每阶段连接成本                 |
| peak RSS                     | 大结果集内存                   |
| total CPU time               | 多进程启动、解码和重复计算成本 |
| emitted bytes/records        | Agent 上下文体积               |
| estimated output tokens      | 实际模型输入成本               |
| records/sec                  | 流处理吞吐                     |
| source verification time     | 源码证据成本                   |
| read-lock hold / writer wait | 长管道对增量索引的阻塞         |

数据集：

- bundled mini fixture；
- 大型多模块 E2E fixture；
- 当前 anatomist 自身；
- 至少一个真实大型 Java 项目。

阶段 0 根据基线设定门槛，至少满足：

- 对等单命令无明显回退；
- 第一条结果无需等待完整集合；
- 内存随 bounded buffer 增长，不随全部输入线性增长；
- 管道没有额外中间临时文件；
- Agent 工具调用次数明显减少。

如果多进程启动和重复 SQLite 打开成为主要瓶颈，再单独评估 fused pipeline executor。不得在没有 benchmark 证据前引入自定义编排 DSL。

### 13.8 Native Image

每个阶段必须运行：

```bash
just test
just native
just smoke
```

Native 测试重点：

- sealed record model；
- JSON codec 无反射依赖；
- record-dispatched decoder 覆盖全部公开 record；
- `AutoCloseable` cursor 在成功、异常、SIGPIPE 下释放；
- stdin 持续读取；
- stdout flush；
- SIGPIPE/broken pipe；
- 大流内存；
- 非 ASCII 路径和内容。

## 十四、文档与 Agent 体验

### 14.1 `--help`

每个原子命令必须说明：

```text
Accepts: entity(type) records from stdin
Emits: type_relation records + final evidence record
```

并提供一条直接可执行的 pipeline。

### 14.2 SKILL.md

Skill 不再让 Agent 在重叠命令间做选择，而是给出数据变换路径：

```text
模糊找候选      search
精确拿实体      resolve
从文件找声明    declarations-of
看实体自身      describe
看成员          members
看类型语义      type-relations
看运行时实现    runtime-implementations
看跨域绑定      bindings
看调用点        calls --direction
看多态候选      dispatch
看控制区域      regions
看区域内位置    sites-in
看源码证据      source
```

必须明确：

- `calls` 不是运行时路径；
- `dispatch` 只返回静态候选；
- `coverage=partial` 时不能下否定结论；
- 框架事实来自 artifact producer，不属于语言本身；
- 管道末尾 evidence 必须检查。

### 14.3 结构化下一步

旧 `next_queries` 字符串兼容保留。新 evidence 使用：

```json
{
  "next": {
    "accepts": "entity",
    "operation": "source",
    "reason": "result_truncated",
    "arguments": { "offset": 200 }
  }
}
```

它是建议的下一原子操作，不是一个完整 JSON workflow。

## 十五、兼容与发布

### 15.1 版本节奏

| 阶段       | 行为                                                                  |
| ---------- | --------------------------------------------------------------------- |
| `0.16.x`   | 三类身份、完整 Java/Artifact semantic operation；旧命令默认不变       |
| `0.17.x+`  | 可在文档/help 标注重叠旧命令为 alias；stdout 继续兼容                   |
| `1.0` 前   | 至少提前两个 minor 并发布等价报告，才评估隐藏重叠入口                   |

### 15.2 兼容约束

- 旧 JSON contract v2 在兼容期保持字段和退出码。
- 新命令使用 `semantic-stream/v1`，不冒充 v2 envelope。
- 弃用提示不得写入 JSON/NDJSON stdout。
- `doctor` 同时报告 legacy command 和 semantic stream capability。
- graph semantics 变化必须显式要求 re-index。

### 15.3 回滚

每个阶段保持旧命令可用。若新查询出现问题：

```text
关闭 semantic CLI 注册
    ↓
旧命令继续访问当前 QueryService
    ↓
不需要回滚索引，除非 graph semantics 已升级
```

涉及 graph semantics 的改动必须独立 commit，避免与大规模 CLI 改名混在一起。

## 十六、风险与控制

| 风险                   | 控制措施                                                             |
| ---------------------- | -------------------------------------------------------------------- |
| 通用 IR 变成最低公分母 | common semantic + language mechanism/facets 双层表达                 |
| 管道丢失 evidence      | 每条记录带 revision/seed，逐 seed evidence，末尾唯一 stream evidence |
| 标准工具删除 framing   | 默认拒绝 unframed，显式 `--accept-unframed`                          |
| 多进程管道重复打开 DB  | benchmark 后决定是否 fused execution                                 |
| dispatch 候选爆炸      | depth、rows、compute budget 和 instantiability filter                |
| Agent 把候选当真实调用 | 分离 resolved/possible/observed，明确 origin、resolution 和 world    |
| 框架重新污染核心       | producer SPI，核心禁止框架类型依赖                                   |
| 旧命令和新命令结果漂移 | canonical equivalence tests                                          |
| 跨语言能力不完整       | capability matrix，unsupported 不得返回空                            |
| schema/graph 迁移复杂  | 语义变更独立版本、独立 commit、强制重建提示                          |
| “流式”只发生在 CLI 层  | backend 强制 closeable cursor，100k fixture 验证有界内存             |
| shell 分支拼坏 framing | v1 只支持线性流，正式 merge 前不承诺 tee 合并                        |

## 十七、完成定义

满足以下条件后，本重构才算完成：

- [ ] 核心原子命令名和参数不包含 Java/Spring 专属概念。
- [ ] `search`、`resolve`、`declarations-of` 各自只有一个职责。
- [ ] Java 适配器能区分类型层次、可实例化实现和多态候选。
- [ ] `calls`、`dispatch`、runtime observation 三类事实不会混淆。
- [ ] call-site 有精确范围和跨重建稳定 ID，同一行多调用不冲突。
- [ ] Spring Bean 文件通过 Artifact IR 查询，不需要核心 Spring 命令。
- [ ] 所有原子命令支持 NDJSON stdin/stdout。
- [ ] index revision、source snapshot、semantic profile 三类身份不混用。
- [ ] 每个 seed 有 evidence，整条流只有一个最终 stream evidence。
- [ ] backend 使用可关闭 cursor，100k 记录不会先物化完整列表。
- [ ] 一条 pipeline 能完成复杂 E2E 的主要查询链。
- [ ] 旧命令在兼容期行为稳定，并有等价测试。
- [ ] Java/Python/TypeScript/Rust fixture 证明核心协议没有 Java 假设。
- [ ] JVM、native、golden、Jury E2E 和 benchmark 全部通过。
- [ ] README、AGENTS.md、SKILL.md、commands、data-model、testing 已更新。
- [ ] 未执行或引入未经证明必要的 JSON Plan DSL。

## 十八、推荐执行顺序

```text
ADR + identity/evidence/recipe contract
        ↓
versioned indexed facts + schema migration
        ↓
Semantic IR + typed NDJSON + cursor backend
        ↓
Java resolve → calls → source 垂直切片
        ↓
Java type/runtime implementation/dispatch
        ↓
Spring producer 升级 + Artifact IR
        ↓
原子 CLI + 旧命令精确适配
        ↓
E2E + benchmark + native + 文档切换
```

开始编码前，优先完成阶段 0。若跨语言 fixture 无法自然表达某个核心操作，应先修改 IR，而不是在 CLI 中增加语言特例。
