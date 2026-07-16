# 测试策略

anatomist 自身运行在 **JDK 21+**（`maven.compiler.release=21`），但被索引的目标项目以 **JDK 8** 为基线。本文定义验收策略、fixture 设计与 CI 流程。

## 一、测试金字塔

| 层 | 验证什么 | 形式 | 占比 |
|----|---------|------|------|
| L1 单元 | 单个 Extractor 解析单个 .java 片段 | JUnit 5 + JavaParser 内存 AST + SymbolSolver | 60% |
| L2 集成 | 完整 index → SQLite → SQL 查询 | JUnit 5 + 临时 .db | 30% |
| L3 端到端 | CLI 命令 → JSON 输出 | Picocli + golden file | 10% |

- L1 用最小代码片段精确锁定单个边/节点的生成（断言精确到 Node ID 字符串）
- L2 验证 ID 一致性、外键、FTS5 触发器、增量 diff
- L3 锁定 Agent 看到的契约（命令输出 JSON Schema）

**不 mock 解析器**：JavaParser + SymbolSolver 的解析与绑定行为本身就是被测核心，mock 等于不测。所有层都用真 JavaParser + 真 JavaSymbolSolver；测试 helper 见 `src/test/java/com/anatomist/core/JavaParserTestSupport.java`。

## 二、Fixture 分层

### Fixture A — `fixtures/micro/`（L1 用）
单文件 .java 片段，每个针对一种语法结构：

```
fixtures/micro/
├── LambdaInStream.java       # Lambda → LAMBDA 节点 + 跨越遍历
├── AnonymousRunnable.java    # 匿名类 → ANONYMOUS_CLASS ID 含行号
├── OverloadedMethods.java    # 方法重载 → 签名擦除消歧
├── StaticVsInstance.java     # call_kind 分支
├── GenericRepository.java    # 泛型参数 → REFERENCES context=generic_arg
├── FieldReadWrite.java       # READS/WRITES
├── EnumWithMethods.java      # ENUM + ENUM_CONSTANT
└── InterfaceDefaultMethod.java  # JDK 8 default method 归属
```

不需要 build，直接喂给 `JavaParser.parse(...)`。每个片段 < 30 行。

**实现状态**：8 个 fixture 文件已落地。`src/test/java/com/anatomist/cli/MicroFixtureIT` 走完整 IndexCommand 管道索引整个 `fixtures/micro/`，对每个 fixture 一条 `@Test` 断言（Node ID 字符串、边数、call_kind 分支等），外加两条 JDK 8 negative 断言（无 RECORD 节点、无 JRE 21 `Sequenced*` 类型泄漏）。`mvn test -Dtest=MicroFixtureIT` 触发。

### Fixture B — `fixtures/mini-spring-shop/`（L2/L3 主战场）

**自建多模块 Spring Boot 2.7.18 项目**，JDK 8 编译，覆盖端到端流程验证的结构。

| 模块 | 内容 | 覆盖测试点 |
|------|------|----------|
| `domain` | Order/OrderItem/OrderStatus(enum)/CreateOrderRequest/OrderResult/OrderCreatedEvent | nodes.module 字段、ENUM、JavaBean 字段 |
| `service` | BaseService(abstract)/OrderService/OrderValidator/PriceCalculator/OrderRepository(接口)/InMemoryOrderRepository(实现)/OrderEventPublisher | INHERITS、OVERRIDES、IMPLEMENTS、CALLS、READS/WRITES、Lambda、匿名类、@Service、@Transactional |
| `api` | ShopApplication/OrderController + JUnit 5 自测 | @RestController、@PostMapping、跨模块依赖、Spring Boot 启动 |

`OrderService.createOrder` 故意复刻 DESIGN §Step 4 调用链：`Validator.validate → Calculator.calculate → applyDiscount → Repository.save → publisher.publish`,作为 D3/D4 测试的**金标准**。

**fixture 自身用 JUnit 5 自测**（验证业务逻辑正确），同时它是 anatomist 的索引目标。

### Fixture C — 真实开源项目（L3 烟雾测试）

| 项目 | 用途 | 状态 |
|------|------|------|
| **Apache Commons Lang 3.12.0** | 规模性能基线 + dropped-edges 退化监控 | **已接入** → `CommonsLangSmokeIT` |
| Spring PetClinic | 业务场景验证 | 未接入（历史 1.5.x 分支已被上游删除；如需复活，挑一个真实存在的 tag 走相同 submodule 模式） |

git submodule 锁版本，vendored 到 `fixtures/external/`，**不联网即跑测试**。具体接入命令、跳过语义、为什么选这个 fixture 见 [`fixtures/external/README.md`](../fixtures/external/README.md)。

`CommonsLangSmokeIT` 三条断言：

1. **规模基线** — `types ≥ 100 && methods ≥ 1000 && edges ≥ 1000`
2. **关键类存在** — `org.apache.commons.lang3.StringUtils` / `ObjectUtils` / `ArrayUtils` 都能在 nodes 表精确找到
3. **查询层联通** — `QueryService.search(...)` 在 `StringUtils` / `ObjectUtils` / `ArrayUtils` / `Validate` 任一上返回非空

**Dropped-edges 基线**：commons-lang 3.12.0 当前会触发 `Pruned dangling = 188`。这个数字应**单调下降**——任何 extractor 修复都会带它一起降低；如果它涨了，说明回归了或上游 fixture 升了。

**跳过语义**：每个 @Test 顶部调 `requireSubmodule()` → `assumeTrue(...)`，submodule 未 checkout 时 Surefire 报 `Tests run: 3, Skipped: 3`（不是误导性的 `Tests run: 0`），并在 stderr 打一行接入提示。

## 三、JDK 8 语义边界验证

由于 anatomist 跑在 JDK 21、索引 JDK 8 源码，必须显式断言以下不被"提升解析"：

1. `ParserConfiguration.setLanguageLevel(JAVA_8)` 生效——Record / sealed / switch pattern 不应识别
2. Lambda 按 JDK 8 语义（不是 var capture）
3. interface `default` 方法正确归到 INTERFACE 节点
4. anatomist 自身 JRE 21 的类（`java.util.SequencedCollection` 等）不污染外部 FQN（通过 `--vm-classpath false` 关闭 ReflectionTypeSolver 验证）

L1 fixture 各加一条 negative 断言覆盖。

## 四、Golden File 模式

每个场景一个目录：

```
tests/scenarios/<scenario-id>/
├── input.cmd               # 一行 CLI 命令（args 用空格分隔，支持 # 注释与 "..." 引号段）
└── expected.json           # 期望输出（结构对比；规范化时绝对路径替换为 ${PROJECT}）
```

**Driver**：`src/test/java/com/anatomist/cli/GoldenFileIT` — `@TestFactory` 自动遍历 `tests/scenarios/*/input.cmd`，对每个目录跑一条 `DynamicTest`，命令通过 `AnatomistCli` 一次性执行后比对。规范化策略：

- Jackson `ORDER_MAP_ENTRIES_BY_KEYS` 让 map 输出顺序稳定（不依赖 JsonUnit / AssertJ JSON 这种额外依赖，保持 4 dep 预算）
- 项目根绝对路径替换为 `${PROJECT}`，跨机器/CI 稳定
- 自动注入 `--index <built-db>`，input.cmd 不用写 `--index`

**刷新机制**：`mvn test -Dtest=GoldenFileIT -Dgolden.update=true` 重新生成所有 `expected.json`。CI 默认不带这个开关，diff 不为空即 fail。**这套用例同时作为对外的命令使用手册**。

**当前 11 个种子场景**：`B1-search-by-label` / `B3-context-by-fqn` / `C1-context-members` / `C2-hierarchy-extends` / `C4-deps-of` / `D1-callees-single-hop` / `D2-callers-single-hop` / `D3-callees-multi-hop` / `D4-call-path` / `E1-overview` / `F1-callers-deep-impact`，覆盖搜索、上下文、层次、依赖、调用链、overview、影响面代表路径。

## 五、本地 E2E / Smoke 命令

统一使用 SDKMAN JDK25：

```bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk use java 25.0.3-graal
```

| 命令 | 验证什么 | 说明 |
|------|----------|------|
| `just smoke` | native binary 对 mini-spring-shop 的 index + 核心查询 | 包含 `context --enrich`；recipe 使用 fail-fast，命令失败不会被 `head` 掩盖。 |
| `just native-smoke` | JVM jar 与 native binary 输出一致性 | 失败时保留 `/tmp/anatomist-native-smoke-*.log` 并打印 native 诊断。 |
| `just external-cli PROJECT=/path/to/project` | 大型外部项目复杂 CLI | opt-in，本地手动跑；默认目标是 `/Users/stream/codes/antcodes/ipay/imerchantsettle`。 |

`external-cli` 会重建临时 DB，并固定验证 Facade API、Handler 入口、DAO 正反查、字段访问和调用链，不进入默认 CI。

## 六、增量测试拆分

为消除文件系统事件层的不稳定性,把测试切两段：

| 测试 | 走什么路径 | 目的 |
|------|----------|------|
| 增量 diff 正确性 | `anatomist index --incremental`（合成 diff,无 WatchService） | 主路径,覆盖率高 |
| Agent 查询门禁 | 无变更增量 + `--strict-health`，随后才允许查询 | 确保无变更不触发 Maven/JavaParser/图重建，失败时 Agent 不应使用旧索引结论 |
| WatchService 集成 | 真启 watch 改文件 | 仅 1-2 个 happy-path,Linux 跑 |

## 七、性能基线

| 指标 | 目标 | 验证方式 |
|------|-----|---------|
| index 速度 | Commons Lang 70k 行 < 30s 冷启 | `assertTimeout` |
| Watch body-only 增量 | p50 ≤ 500ms，p95 ≤ 750ms | 同一进程连续修改，`--timings` |
| 16 文件增量 | ≤ 7.5s | 固定源码快照和 binary |
| 小闭包增量 | ≤ 2.5s | 契约变化但不超过 realign 上限 |
| 查询 P99 | 任意单跳查询 < 50ms | mini-shop 上 1000 次 |
| SQLite 大小 | 70k 行项目 < 30MB | 看 .db 体积 |
| 内存峰值 | < 1GB heap | `-Xmx1g` 跑通 |

**不卡硬阈值,CI 记录 trend,回归超 20% 才 fail**。

增量正确性还要覆盖：size/mtime 快路径、`--verify-content`、恢复时间戳的
Watch 候选、契约指纹对 body/签名的区分、impact SQL 索引计划、Spring XML
入边保留，以及 Watch staging/known-ID 会话复用与退出清理。构建文件测试要
区分“环境未变化继续增量”和“classpath/source-layout 变化触发一次 full”；
后台 full 还要覆盖：构建期间继续收集事件、单飞合并、回放后与 fresh full
一致、临时 DB 失败保留旧库、以及重启后的 stale 对账；
成本模型固定覆盖 70% full 预算、20% 冷启动回退、1000 文件硬上限和 128 文件批次。

大型项目诊断应使用同一源码快照和 native binary，向 `target/perf/` 写入
三个独立的 `--recreate --timings --format=json` 结果，报告中位数、范围和
`/usr/bin/time -l` 峰值内存。首轮不宣称冷缓存；三次离散度超过 10% 时追加
两次并改用五次中位数。`--no-classpath` 和关闭 `--spring-xml` 只能作为归因
对照，不能替代完整索引的正确性基线。

流式 staging 的性能门禁还必须比较：最终 Node 全列、Edge/Annotation 的
业务列及重复次数、峰值 RSS、最终 DB 大小。`index.db.stage-*` 是瞬时磁盘
开销，不得计入最终 DB 大小；成功、失败和 parse retry 用例都要断言无残留。

依赖类型缓存的性能验证使用隔离的 `anatomist.typeCache.dir`：清空目录后跑
一次 cold，再复用目录跑 warm。`--timings` 应包含 `classpath_index_build`、
`type_cache_load`、`type_cache_write`。缓存 key 覆盖有序 classpath、JAR 大小/
mtime 和目标 Java 版本；单测还要覆盖 CRC 损坏后的自动删除与冷启动回退。

## 八、CI 流程

```yaml
jobs:
  test:
    steps:
      - setup-java: temurin 21   # 跑 anatomist
      - setup-java: temurin 8    # 编译 fixture B/C,验 JDK 8 可 build
      - mvn -f fixtures/mini-spring-shop test   # 预热 .m2 + fixture 自测健康
      - mvn -f anatomist test                   # L1 + L2
      - mvn -f anatomist verify                 # L3 端到端 + 性能基线
```

第 3 步同时充当 `ClasspathDetector` 的真实测试——如果 anatomist 跑 `mvn dependency:build-classpath` 失败,说明 detector 有 bug。

## 九、Phase 对齐

| Phase | 引入的测试资产 | 状态 |
|-------|--------------|------|
| Phase 1 | Fixture A 全集 + Fixture B 编译通过 + L1 全部 Extractor 单测 | ✅ Fixture A 8 文件 + `MicroFixtureIT` 10 用例；Fixture B 全程；8 Extractor 单测共 30+ 条 |
| Phase 2 | L2 集成（SQLite + 查询） + L3 golden file 主场景（B/C/D/F） | ✅ `QueryServiceIT` 17 条覆盖 B1–F3；`GoldenFileIT` 8 种子场景 |
| Phase 3 | Skill 文件与 CLI 契约 e2e（脚本驱动 CLI） | golden-file 套件已部分承担（CLI → JSON 契约锁定） |
| Phase 4 | Fixture C 接入 + 性能基线 trend + 增量回归 | ✅ Fixture C = commons-lang 3.12.0；增量见 `IncrementalIndexerIT` / `WatchCommandIT`；性能 trend 未接入 |

**触发约定**：所有 `*IT` 走 Surefire 默认 include 模式之外（与 unit `*Test` 区分），必须显式 `mvn test -Dtest=<ClassName>` 触发。
