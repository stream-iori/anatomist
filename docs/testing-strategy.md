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

不需要 build，直接喂给 `JavaParser.parse(...)`。每个片段 < 30 行。Phase 1 起逐个补齐。

### Fixture B — `fixtures/mini-spring-shop/`（L2/L3 主战场）

**自建多模块 Spring Boot 2.7.18 项目**，JDK 8 编译，刻意复刻 DESIGN.md §端到端流程验证 的结构。

| 模块 | 内容 | 覆盖测试点 |
|------|------|----------|
| `domain` | Order/OrderItem/OrderStatus(enum)/CreateOrderRequest/OrderResult/OrderCreatedEvent | nodes.module 字段、ENUM、JavaBean 字段 |
| `service` | BaseService(abstract)/OrderService/OrderValidator/PriceCalculator/OrderRepository(接口)/InMemoryOrderRepository(实现)/OrderEventPublisher | INHERITS、OVERRIDES、IMPLEMENTS、CALLS、READS/WRITES、Lambda、匿名类、@Service、@Transactional |
| `api` | ShopApplication/OrderController + JUnit 5 自测 | @RestController、@PostMapping、跨模块依赖、Spring Boot 启动 |

`OrderService.createOrder` 故意复刻 DESIGN §Step 4 调用链：`Validator.validate → Calculator.calculate → applyDiscount → Repository.save → publisher.publish`,作为 D3/D4 测试的**金标准**。

**fixture 自身用 JUnit 5 自测**（验证业务逻辑正确），同时它是 anatomist 的索引目标。

### Fixture C — 真实开源项目（L3 烟雾测试，Phase 2 末期引入）

| 候选 | 用途 |
|------|------|
| Spring PetClinic 1.5.x（JDK 8 分支） | E1/E2/E3 业务场景验证 |
| Apache Commons Lang 3.12.0 | 规模性能基线 |

git submodule 锁版本，vendored 到 `fixtures/external/`,不联网。

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
tests/scenarios/D3-multi-hop-call-chain/
├── input.cmd               # anatomist callees-of ... --depth 5 --format json
├── expected.json           # 期望输出（JSON 结构对比,字段顺序无关）
├── expected.sql.txt        # 实际执行 SQL（验查询计划）
└── README.md               # 用例说明
```

- 用 **JsonUnit / AssertJ JSON** 做结构对比,允许字段乱序但严格匹配值
- `--update-golden` 一键刷新；CI diff 不为空即 fail
- **这套用例同时作为对外的命令使用手册**

## 五、增量测试拆分

为消除文件系统事件层的不稳定性,把测试切两段：

| 测试 | 走什么路径 | 目的 |
|------|----------|------|
| 增量 diff 正确性 | `anatomist index --incremental`（合成 diff,无 WatchService） | 主路径,覆盖率高 |
| WatchService 集成 | 真启 watch 改文件 | 仅 1-2 个 happy-path,Linux 跑 |

## 六、性能基线

| 指标 | 目标 | 验证方式 |
|------|-----|---------|
| index 速度 | Commons Lang 70k 行 < 30s 冷启 | `assertTimeout` |
| 增量 index | 改 1 文件 < 500ms | end-to-end |
| 查询 P99 | 任意单跳查询 < 50ms | mini-shop 上 1000 次 |
| SQLite 大小 | 70k 行项目 < 30MB | 看 .db 体积 |
| 内存峰值 | < 1GB heap | `-Xmx1g` 跑通 |

**不卡硬阈值,CI 记录 trend,回归超 20% 才 fail**。

## 七、CI 流程

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

## 八、Phase 对齐

| Phase | 引入的测试资产 |
|-------|--------------|
| Phase 1 | Fixture A 全集 + Fixture B 编译通过 + L1 全部 Extractor 单测 |
| Phase 2 | L2 集成（SQLite + 查询） + L3 golden file 主场景（B/C/D/F） |
| Phase 3 | Skill 文件与 CLI 契约 e2e（脚本驱动 CLI） |
| Phase 4 | Fixture C 接入 + 性能基线 trend + 增量回归 |
