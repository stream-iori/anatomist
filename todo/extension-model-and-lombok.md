# 扩展能力与 Lombok 支持方案

> 状态：扩展 SPI、Spring 迁移、`producer_id`、record 回归已实施；Lombok 规则仍是待办。

| 能力 | 状态 | 代码入口 |
|---|---|---|
| 统一扩展注册与指纹 | ✅ | `BuiltInExtensions` / `PreparedExtensions` |
| 解析后、提取前 AST 钩子 | ✅ | `AstModelExtension` / `JavaParserFactory` |
| Java 单元事实扩展 | ✅ | `JavaUnitAnalyzer` |
| 项目资源共享与二阶段分析 | ✅ | `ProjectResourceAnalyzer` / `ProjectAnalysisRunner` |
| Spring 注解、MVC、XML 迁移 | ✅ | `framework/spring` |
| 结构事实 `producer_id` | ✅ | schema v14；flow 表不变 |
| record | ✅ 核心能力 | 不做扩展；由 JavaParser + 核心 Extractor 处理 |
| Lombok AST/bytecode/delombok 规则 | ⏳ | 仅预留 SPI，不默认增加运行时成本 |

## 结论

扩展体系需要增加一个“解析后、核心提取前”的模型扩展阶段。现有
`JavaUnitAnalyzer` 继续负责补充框架事实，不承担修改类型模型的职责。

Lombok 首版采用 **AST 签名补全**：只在内存中补充生成成员的声明，不运行
Lombok、不读取 class、不生成方法体。

```text
项目发现与配置
      │
      ▼
PreparedExtensions.prepare
      │
      ▼
JavaParser 解析源码
      │
      ▼
AstModelExtension.augment     ← Lombok 首版放这里
      │
      ▼
核心 Extractor
      │
      ▼
JavaUnitAnalyzer             ← Spring Component/MVC 等
      │
      ▼
ProjectResourceAnalyzer      ← Spring XML 等；共享同一资源清单
```

Lombok 后端按以下顺序演进：

| 优先级 | 模式 | 定位 |
|---:|---|---|
| P0 | `ast` | 默认推荐；性能最低；补常用生成签名 |
| P1 | `bytecode` | 显式启用；使用新鲜编译产物提高准确率 |
| P2 | `delombok` | 未来可选；追求完整变换语义 |

不要直接把 Lombok 实现成 `JavaUnitAnalyzer`。它执行得太晚，无法帮助
前面的 `CallGraphExtractor` 和 `FieldAccessExtractor` 完成符号解析。

## 已落地的事实所有权

```text
同一资源 ─┬─ producer-a ─→ 自己的 nodes / edges / annotations
          └─ producer-b ─→ 自己的 nodes / edges / annotations

增量替换 = 按 producer 删除旧事实 + 保留稳定 node id + 写入新事实
```

节点 ID 只能有一个所有者。不同扩展写入同一节点 ID 时直接报
`EXTENSION_NODE_OWNERSHIP_CONFLICT`；不同生产者可以引用对方节点。内置 ID 为
`java-core`、`spring-components`、`spring-mvc`、`spring-xml`、
`semantic-javadoc`、`derived-wiring`、`manual-annotation`。

record 不属于框架推断。它是 Java 语言声明，必须继续由核心 extractor 生成
record 类型、component field、accessor、constructor 和调用关系；否则关闭扩展
就会丢失标准 Java 语义。

## 第一性原理

| 问题 | 原则 |
|---|---|
| 为什么需要新扩展阶段 | Lombok 改变编译器看到的类型模型，不只是增加业务语义 |
| 为什么首选 AST 签名补全 | 调用解析需要成员签名，不需要生成方法体 |
| 为什么不默认读字节码 | class 可能不存在或过期，且会增加文件扫描和缓存一致性成本 |
| 为什么不默认 delombok | 需要外部进程、临时目录、classpath 和源码位置映射 |
| 为什么不直接忽略 Lombok | Getter、Builder、日志字段等调用会持续产生错误或不完整调用图 |
| 为什么必须披露覆盖率 | AST 模式不是完整 Lombok 编译器，不能把推断包装成精确事实 |

目标不是复制 Lombok 的全部实现，而是以最低成本恢复 Agent 最常使用的结构事实：

```text
类型
├── 生成字段
├── 生成构造器
├── 生成方法签名
├── 生成内部类型
└── 调用目标可解析
```

首版明确不恢复生成方法体、运行时行为和完整数据流。

## 原扩展机制的缺口（已处理）

### `JavaAstAnalyzer` 执行太晚

[`JavaAstAnalyzer`](../src/main/java/com/anatomist/framework/JavaAstAnalyzer.java)
只有：

```java
String id();
void analyze(CompilationUnit unit, ExtractionResult result);
```

[`ExtractorPipeline`](../src/main/java/com/anatomist/extract/ExtractorPipeline.java)
原来先执行全部核心 Extractor，最后才执行 `JavaAstAnalyzer`：

```text
TypeExtractor
FieldExtractor
MethodExtractor
DeclarationExtractor
AnnotationExtractor
HierarchyExtractor
ReferenceExtractor
CallGraphExtractor
ReflectionExtractor
FieldAccessExtractor
JavaAstAnalyzer
```

因此在 Analyzer 中补一个 `getName()` 节点，无法修复此前已经失败的
`user.getName()` 调用解析。

### 注册方式原来硬编码

原来的全量索引和增量索引分别直接调用
[`SpringAnalyzers.registry`](../src/main/java/com/anatomist/framework/spring/SpringAnalyzers.java)：

| 入口 | 当前代码 |
|---|---|
| 全量索引 | [`IndexOrchestrator`](../src/main/java/com/anatomist/application/IndexOrchestrator.java) |
| 增量索引 | [`IncrementalIndexer`](../src/main/java/com/anatomist/incremental/IncrementalIndexer.java) |

这会造成：

- 新扩展需要修改多个编排入口；
- 全量和增量容易出现行为差异；
- `AnalyzerRegistry.projectAnalyzers` 已定义但没有统一执行；
- Spring XML 仍通过静态方法单独调用。

### 仍然不是外部插件系统

现有 `AnalyzerRegistry` 是内部组合对象，不是动态插件系统。考虑 native-image
约束，当前阶段不引入 `ServiceLoader`、外部 jar 热加载或反射扫描。

首版目标是“内置扩展、统一生命周期、编译期注册”。

## 目标扩展架构

### 扩展阶段

| 阶段 | 接口 | 输入 | 允许的输出 |
|---|---|---|---|
| 注册准备 | `PreparedExtensions.prepare` | 编译期注册表 | ID 校验、处理器、兼容性指纹 |
| AST 模型增强 | `AstModelExtension.augment` | 单个 `CompilationUnit` | 增加或调整合成声明 |
| AST 事实增强 | `JavaUnitAnalyzer.analyze` | 已增强 AST、已有事实 | framework node/edge/annotation |
| 项目事实增强 | `ProjectResourceAnalyzer.analyze` | 资源清单、只读事实视图 | XML、配置等项目级事实 |

### 已落地接口

```java
public interface ExtensionPoint {
    String id();
    default String version() { return "1"; }
    default String producerId() { return id(); }
}

public interface AstModelExtension extends ExtensionPoint {
    void augment(CompilationUnit unit);
}
```

`PreparedExtensions` 保存：

```text
PreparedExtensions
├── AnalyzerRegistry
├── extension fingerprint
└── JavaParser Processor suppliers
```

增强操作必须满足：

| 约束 | 原因 |
|---|---|
| 幂等 | 同一文件可能被主 Parser 和 `JavaParserTypeSolver` 分别解析 |
| 无跨文件可变全局状态 | Watch 和增量失效更容易控制 |
| 不修改原始文件 | 扩展只操作内存 AST |
| 合成声明带来源标记 | 查询必须区分源码事实与推断事实 |
| 失败按文件软降级 | 单个不支持注解不能阻断整个索引 |

### 注册表

```java
public record AnalyzerRegistry(
        List<AstModelExtension> astModelExtensions,
        List<JavaUnitAnalyzer> javaUnitAnalyzers,
        List<ProjectResourceAnalyzer> projectResourceAnalyzers
) {}
```

统一通过一个入口组装：

```text
BuiltInExtensions.create(context)
├── SpringExtension
└── LombokExtension
```

全量索引、增量索引和 Watch 使用同一个 `AnalyzerRegistry` 注册源。

### JavaParser 接入点

AST 模型扩展必须同时安装到：

1. 主源码 Parser 的 `ParserConfiguration`；
2. 每个 `JavaParserTypeSolver` 使用的 `sourceConfiguration`；
3. Watch Session 使用的持久化 Parser 配置。

可以使用 JavaParser `Processor.postProcess` 做统一适配：

```text
ParserConfiguration
  └── ExtensionProcessor
        └── for each enabled AstModelExtension
              augment(compilationUnit)
```

只增强主 Parser 会产生以下错误：

```text
MethodExtractor 看见生成成员
           但
JavaParserTypeSolver 看不见生成成员
           ↓
跨文件调用仍然解析失败
```

### 扩展元数据

所有扩展生成的声明统一写入：

```json
{
  "isSynthetic": true,
  "generator": "lombok",
  "generatorMode": "ast",
  "generatedFrom": "@Data",
  "confidence": "INFERRED",
  "bodyAvailable": false
}
```

建议新增通用 AST Node Data Key，避免每个扩展自行约定：

```java
SyntheticOriginKey
```

`MethodExtractor`、`FieldExtractor`、`TypeExtractor` 和
`DeclarationExtractor` 统一读取该标记并写入 metadata/declaration。

## Lombok AST 签名补全

### 工作方式

```text
CompilationUnit
      │
      ├── 收集 import、lombok 注解、类型和字段
      ├── 计算应生成的成员签名
      ├── 与显式成员签名去重
      ├── 写入签名级合成 AST 节点
      └── 标记 SyntheticOriginKey
```

只生成结构：

- 方法名；
- 参数名和参数类型；
- 返回类型；
- 可见性；
- `static/final` 等必要修饰符；
- 构造器；
- Builder 所需内部类型；
- 日志字段。

不生成方法实现。非抽象方法使用最小 AST 占位表示，并确保核心 Extractor
不会从占位体产生虚假调用、字段读写或 flow 事实。

### 支持范围

| 阶段 | Lombok 能力 | 输出 |
|---:|---|---|
| P0 | `@Getter` | Getter 签名 |
| P0 | `@Setter` | 非 final 字段 Setter 签名 |
| P0 | `@NoArgsConstructor` | 无参构造器 |
| P0 | `@RequiredArgsConstructor` | final/`@NonNull` 字段构造器 |
| P0 | `@AllArgsConstructor` | 全字段构造器 |
| P0 | `@Data` | Getter、Setter、必要构造器、`equals/hashCode/toString` 签名 |
| P0 | `@Value` | Getter、全参构造器、`equals/hashCode/toString` 签名 |
| P0 | `@Slf4j` 等日志注解 | `static final` 日志字段 |
| P1 | `@Builder` | Builder 内部类型和链式方法签名 |
| P1 | `@With` | `withX` 方法签名 |
| P1 | `@Accessors` | fluent、chain、prefix 命名规则 |
| P2 | `@SuperBuilder` | 跨继承层 Builder 模型 |

首版不支持：

| 能力 | 原因 |
|---|---|
| `@Cleanup` | 改写控制流和资源关闭行为 |
| `@SneakyThrows` | 改写异常语义 |
| `@Synchronized` / `@Locked` | 改写同步行为 |
| `@Delegate` | 需要展开目标类型的完整成员集合 |
| `@ExtensionMethod` | 改变方法调用解析规则 |
| `val` / Lombok `var` | 改变局部变量类型推断 |
| 生成方法体的 flow | AST 模式只承诺结构事实 |

遇到上述能力时不静默忽略，记录：

```text
LOMBOK_FEATURE_UNSUPPORTED
LOMBOK_BODY_SEMANTICS_OMITTED
```

### 生成规则

#### 显式声明优先

```text
显式方法/构造器
      │
      ├── 签名相同：不生成
      └── 签名不同：保留生成候选
```

去重键使用稳定 symbol signature，不使用源码字符串：

```text
owner FQN + member name + erased parameter types
```

#### 来源位置

| 生成成员 | `source_location` |
|---|---|
| 字段 Getter/Setter/With | 字段行 |
| 类型级构造器 | 注解行，缺失时用类型声明行 |
| `equals/hashCode/toString` | 类型级注解行 |
| 日志字段 | 日志注解行 |
| Builder 类型和 `builder()` | `@Builder` 行 |
| Builder 字段方法 | 对应源字段或参数行 |

生成成员没有真实源码范围，不能伪造精确 `end_line/end_column`。

#### 名称解析

P0 至少覆盖：

- 普通 `getX/setX`；
- primitive/wrapper boolean 的 `isX` 差异；
- final 字段不生成 Setter；
- class-level 与 field-level 注解覆盖；
- `AccessLevel.NONE`；
- 显式成员抑制生成；
- 静态字段排除；
- `@Data` 和 `@Value` 的组合规则。

无法确认规则时不生成，输出低噪声聚合诊断。

### `lombok.config`

完整兼容 `lombok.config` 会显著扩大实现范围。首版采用分级策略：

| 配置 | 首版策略 |
|---|---|
| 未发现 `lombok.config` | 使用已实现的默认规则 |
| 发现已支持键 | 读取并纳入扩展指纹 |
| 发现未知且可能影响签名的键 | 生成 `LOMBOK_CONFIG_PARTIAL` |
| `config.stopBubbling` | P1 支持 |
| `import` 外部配置 | P2 支持；首版明确披露未跟踪 |

不能只扫描包含 Lombok 注解的文件。部分配置可能影响没有 Lombok 注解的文件。
首版对这种配置返回 partial coverage，不猜测完整行为。

## 性能设计

### AST 模式成本

```text
时间复杂度：O(本次解析文件中的注解数 + 字段数 + 显式成员数)
空间复杂度：O(本次生成的签名节点数)
额外文件 IO：0
额外外部进程：0
额外 class 扫描：0
```

无变更增量索引必须在扩展准备前快速返回，因此成本为 0。

性能门槛：

| 场景 | 验收目标 |
|---|---:|
| 245 文件全量索引 | 总耗时增幅 `< 3%` |
| 单个 Lombok 文件增量 | 扩展阶段 `< 10ms` |
| 无变更增量 | 不执行 AST 增强 |
| 非 Lombok 项目 | 扩展阶段接近 0 |
| 内存 | 不建立项目级成员镜像 |

必须新增 timings：

```text
extension_prepare
extension_ast_augment
lombok_files_seen
lombok_types_augmented
lombok_members_generated
lombok_unsupported_features
```

计数指标可以进入 JSON stats，耗时进入 `timings_ms`。

### 避免的性能陷阱

| 陷阱 | 约束 |
|---|---|
| 每个扩展重复遍历 AST | P1 可共享 `CompilationUnitFacts` |
| 合成完整方法体 | 首版禁止 |
| 每个字段重复做 SymbolSolver | 优先使用 AST 类型和已有 import 信息 |
| 全项目预建 Lombok 类型表 | 只处理本次解析的文件 |
| 无 Lombok 项目仍扫描全部文件两次 | 在单次 AST 遍历中快速确认是否命中 |
| Watch 中重复增强同一缓存 AST | 幂等标记和签名去重 |

## Bytecode Overlay 可选后端

Bytecode Overlay 不进入默认路径，仅在用户明确要求准确使用编译结果时启用。

```text
--lombok bytecode
```

### 能力

| 优点 | 缺点 |
|---|---|
| 使用实际编译结果 | 需要先编译 |
| Builder、日志字段等更准确 | class 可能过期 |
| 不需要重写复杂 Lombok 规则 | 需要读取 class 和合并成员 |
| 可复用现有 ASM TypeSolver | Watch 未必及时看到新的 class |

Overlay 必须按源码 FQN 定向查询，禁止遍历整个依赖 classpath 的
`knownClasses()`。

现有 ASM 已经跳过方法体、调试信息和栈帧：

```text
ClassReader.SKIP_CODE
ClassReader.SKIP_DEBUG
ClassReader.SKIP_FRAMES
```

### 启用前置修复

[`ClasspathClassFileSource`](../src/main/java/com/anatomist/core/asmsolver/ClasspathClassFileSource.java)
当前 classpath 指纹只记录 jar 的大小和修改时间，目录型 `target/classes`
只记录路径。持久化 metadata cache 可能复用旧的项目 class 元数据。

启用 Overlay 前必须选择一种修复：

| 方案 | 性能 | 正确性 | 建议 |
|---|---:|---:|---|
| 项目输出目录禁用持久化 metadata cache | 最低额外成本 | 高 | 首选 |
| 每个 class 记录 size/mtime | 低 | 中 | 可选 |
| 对全部 class 求内容 hash | 高 | 高 | 不适合作为默认 |
| 对整个目录只看目录 mtime | 最低 | 低 | 不采用 |

项目输出 class 按需读取，本次进程内仍可使用有界内存缓存。

### 新鲜度

字节码新鲜度无法仅靠 class 文件存在来证明。

```text
源码比 class 新
      └── 跳过对应类型 Overlay

无法判断
      └── 标记 LOMBOK_COMPILED_MODEL_FRESHNESS_UNKNOWN

用户显式信任
      └── metadata.confidence = COMPILED
```

Anatomist 不主动执行 `mvn compile` 或 `gradle classes`。索引命令不应隐式运行
可能执行项目插件和代码生成器的构建生命周期。

## Delombok 可选后端

Delombok 只作为未来高精度后端：

```text
--lombok delombok
```

需要解决：

- 项目实际 Lombok jar 的发现和版本匹配；
- Maven/Gradle 多模块 classpath 和 module-path；
- 分层及 import 的 `lombok.config`；
- 临时目录和缓存清理；
- 原始源码与 delombok 源码位置映射；
- Watch 增量成本；
- 外部进程失败和 strict health；
- native binary 环境下 Java 启动器的发现。

```text
原始源码 ───────────────→ 源码位置、原始注解
    │
    └── delombok 影子源码 ─→ 完整类型模型
```

不要把 delombok 输出作为普通 `GENERATED` scope 重复索引，否则会产生重复类型和
错误 source identity。

## 配置和 CLI

建议项目配置：

```toml
[extensions.lombok]
mode = "off" # off | ast | bytecode | delombok
strict = false
```

首版默认 `off`，避免静默改变现有输出契约。项目显式启用：

```toml
[extensions.lombok]
mode = "ast"
```

CLI 覆盖：

```text
--lombok off
--lombok ast
--lombok bytecode
--lombok delombok
```

模式语义：

| 模式 | 失败处理 |
|---|---|
| `off` | 不启用，不输出 Lombok coverage |
| `ast` | 不支持的能力输出 partial diagnostics，索引继续 |
| `bytecode` | class 缺失或过期时按类型降级为 AST/当前行为 |
| `delombok` | 外部处理失败时降级；`strict=true` 时 health gate 失败 |

索引 metadata 必须保存：

```text
lombok_mode
lombok_extension_version
lombok_fingerprint
lombok_coverage
lombok_generated_members
```

扩展模式、扩展版本或影响签名的配置发生变化时，增量索引必须安全降级为全量重建。

## 全量、增量和 Watch

### 全量索引

```text
加载扩展配置
  ↓
准备扩展并生成 fingerprint
  ↓
创建带 ExtensionProcessor 的 JavaParserFactory
  ↓
解析、增强、提取
  ↓
统一执行 ProjectResourceAnalyzer
  ↓
写入扩展 metadata 和 coverage
```

### 增量索引

扩展指纹纳入兼容性判断：

| 变化 | 行为 |
|---|---|
| 普通方法体变化 | 只解析现有影响集 |
| Lombok 字段或注解变化 | 合同 hash 变化并 realign dependents |
| `lombok.config` 变化 | 重建受影响目录；首版可安全降级全量 |
| Lombok 模式变化 | 全量重建 |
| 扩展实现版本变化 | 全量重建 |

`JavaContractFingerprint` 必须在 AST 增强后计算，否则新增 Getter/Builder 不会触发
依赖文件重新解析。

### Watch

Watch Session 中的 AST 增强器必须：

- 与全量索引使用相同配置；
- 支持 parser cache invalidate；
- 处理新增、删除字段造成的生成成员变化；
- 不在每次事件重新扫描整个项目；
- 配置变化时重建 ExtensionProcessor 和 Parser Session。

## 查询与证据披露

查询默认显示生成成员，但必须明确来源：

```text
METHOD User#getName()
  synthetic: true
  generator: lombok
  mode: ast
  confidence: INFERRED
  generated_from: field name @L8
  body_available: false
```

建议查询过滤项：

```text
--include-synthetic       默认 true
--exclude-synthetic
```

调用路径可以经过生成成员，但不能把缺失的方法体描述为真实执行逻辑：

```text
caller → Lombok getter       可报告
getter → field read          AST 首版不报告
getter 的具体 return 语句    AST 首版不存在
```

Flow 查询遇到生成成员时返回：

```text
coverage = partial
reason = LOMBOK_BODY_SEMANTICS_OMITTED
```

后续可以为 Getter/Setter/构造器增加结构化 flow summary，不需要生成完整方法体。

## 测试计划

### 单元测试

| 测试类 | 覆盖内容 |
|---|---|
| `ExtensionLifecycleTest` | ID/版本指纹、主 Parser/source solver、资源共享、producer 标记 |
| `LombokAstModelExtensionTest` | 各注解的签名生成 |
| `LombokMemberDeduplicationTest` | 显式成员优先、重载不误删 |
| `LombokMetadataTest` | synthetic 来源和 confidence |
| `LombokConfigTest` | 已支持配置、partial diagnostics |

### 集成测试

新增 fixture：

```text
fixtures/lombok-sample/
├── pom.xml
└── src/main/java/
    ├── User.java
    ├── UserService.java
    ├── BuilderService.java
    └── LogService.java
```

验证：

- `context User` 能看到生成成员；
- `UserService -> User#getName()` 是 internal CALLS；
- Builder 链能够解析；
- `log.info()` 的 receiver 类型正确；
- 删除字段后，增量索引删除旧 Getter/Setter；
- 修改 `@Data` 为 `@Value` 后，Setter 消失；
- `declarations-of` 返回 synthetic 标记；
- unsupported feature 产生 coverage diagnostic；
- golden JSON 明确披露生成来源。

### 性能测试

准备三组 fixture：

| Fixture | 用途 |
|---|---|
| 无 Lombok | 验证接近零开销 |
| 30% 类型使用 Lombok | 常规项目 |
| 100% DTO + Builder | 压力上界 |

对比：

```text
full index: off vs ast
incremental one-file: off vs ast
watch repeated edit: off vs ast
peak RSS: off vs ast
generated fact count
```

性能回归超过门槛时，首选优化共享 AST facts 和幂等检查，不转向全量预扫描或持久化
项目级 Lombok 模型。

## 实施顺序

```text
阶段 1：统一扩展编排
  ├── AnalyzerRegistry
  ├── 全量/增量/Watch 共用注册入口
  └── 真正执行 ProjectResourceAnalyzer

阶段 2：增加 AST 模型扩展阶段
  ├── ModelExtension / AstModelExtension
  ├── ExtensionProcessor
  ├── 主 Parser + JavaParserTypeSolver 同时安装
  ├── 幂等和失败隔离
  └── 扩展 fingerprint

阶段 3：Lombok P0
  ├── Getter/Setter
  ├── 构造器
  ├── Data/Value
  ├── 日志字段
  ├── synthetic metadata
  └── coverage diagnostics

阶段 4：增量和 Watch 正确性
  ├── 增强后 contract fingerprint
  ├── 字段删除和成员清理
  ├── 配置变更失效
  └── golden scenarios

阶段 5：性能验收
  ├── timings
  ├── fixture benchmark
  └── `< 3%` 全量索引门槛

阶段 6：可选增强
  ├── Builder/Accessors
  ├── Bytecode Overlay
  ├── Getter/Setter flow summary
  └── Delombok backend
```

## 验收标准

### 扩展体系

- [x] 全量、增量、Watch 使用同一个扩展注册入口；
- [x] 模型扩展在核心 Extractor 前执行；
- [x] 主 Parser 和 `JavaParserTypeSolver` 的 AST 视图一致；
- [ ] 扩展失败能够按文件或扩展隔离；
- [x] `ProjectResourceAnalyzer` 由统一 runner 调用；
- [x] 扩展 ID、版本、producer 进入索引兼容性指纹；
- [x] 多个扩展可选择同一资源，增量按 producer 替换；
- [x] 节点跨 producer 冲突 fail-fast；
- [x] 不引入反射扫描，native-image 构建继续通过。

### Lombok AST 模式

- [ ] 常用生成方法和字段能够被 `context/search/declarations-of` 查询；
- [ ] 跨文件调用能够解析到 internal synthetic member；
- [ ] 显式成员不会被重复生成；
- [ ] 生成成员带来源、模式、confidence 和 `bodyAvailable=false`；
- [ ] 不生成虚假 CALLS/READS/WRITES/flow；
- [ ] unsupported feature 和配置明确披露；
- [ ] 字段/注解变化后的增量清理正确；
- [ ] 非 Lombok 项目全量性能增幅接近 0；
- [ ] Lombok fixture 全量性能增幅 `< 3%`。

### Bytecode 模式

- [ ] 默认关闭；
- [ ] 不遍历整个依赖 classpath；
- [ ] 不读取方法体；
- [ ] 项目输出目录不复用可能过期的持久化 metadata；
- [ ] class 缺失、过期或新鲜度未知时明确降级；
- [ ] 不隐式执行 Maven/Gradle 编译。

## 不做什么

- 不在首版实现完整 Lombok 编译器；
- 不默认运行项目构建；
- 不默认启动 delombok 外部进程；
- 不把 delombok 输出作为普通源码重复索引；
- 不因为生成签名而伪造生成方法体；
- 不把 inferred synthetic member 标成精确源码事实；
- 不在当前阶段实现外部 jar 动态插件系统；
- 不为了 Lombok 破坏现有 native-image 约束。
