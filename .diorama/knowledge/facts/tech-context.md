# Tech Context

`.diorama/knowledge/facts/tech-context.md` — 项目的技术栈、版本与构建约定。慢变,每次 survey 增量刷新。

## 项目坐标

- groupId: `com.antcodes.anatomist`
- artifactId: `anatomist`
- version: `0.1.0-SNAPSHOT`
- packaging: jar

## 构建

| 项 | 值 |
|----|----|
| 构建工具 | Maven 3.9+(用户本地 mvn 二进制) |
| JDK release | Java 21(maven.compiler.release=21) |
| 实际运行 JDK | 25-graal(`.sdkmanrc`,影响 anatomist 自身运行,不影响被解析项目的 `--java-version`) |
| 编译插件 | maven-compiler-plugin 3.13.0 |
| 测试插件 | maven-surefire-plugin 3.2.5 |
| 源文件编码 | UTF-8 |

> `release=21` 而非 25 的原因:Maven `release` 编译目标稳定支持到 21,JDK 25 作为运行时 JDK 直接可用;若 toolchain 升级,可平滑切到 25。

## 生产依赖(4 个直接依赖,严格控制)

| 依赖 | 版本 | 用途 |
|------|------|------|
| `com.github.javaparser:javaparser-symbol-solver-core` | 3.28.1 | AST 解析 + SymbolSolver 绑定。传递依赖 `javassist`，由 `JarTypeSolver` 用来读取 .m2 中的 jar 字节码。 |
| `org.xerial:sqlite-jdbc` | 3.47.0.0 | 索引存储 + FTS5 |
| `info.picocli:picocli` | 4.7.6 | CLI 解析 |
| `com.fasterxml.jackson.core:jackson-databind` | 2.17.0 | metadata JSON 序列化 |

**约束(继承自 DESIGN.md)**: 不引入除以上之外的生产依赖。若需新增,必须在 task 的 proposal/design 中明确说明理由。

## 测试依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| `org.junit.jupiter:junit-jupiter` | 5.10.2 | 测试框架(JUnit 5) |

## 源码布局

```
anatomist/
├── pom.xml
├── DESIGN.md                              # 总设计
├── docs/                                  # 5 个 scenario doc + testing-strategy
├── fixtures/mini-spring-shop/             # 端到端 fixture(三模块)
├── src/main/java/com/anatomist/
│   ├── cli/        # AnatomistCli, IndexCommand
│   ├── core/       # ProjectScanner, ClasspathDetector, JavaParserFactory,
│   │               # NodeIdGenerator, ExtractionContext
│   ├── extract/    # Extractor 接口 + 8 个实现（Phase 1.5 全部真实化）
│   ├── model/      # Node, Edge, Annotation, ExtractionResult
│   └── store/      # SqliteStore
├── src/main/resources/
│   └── schema.sql                         # SQLite DDL(由 SqliteStore.initSchema 加载)
└── src/test/java/com/anatomist/           # 单元测试 + IndexCommandIT(端到端)
```

## CI / 本地运行

- 完整测试: `mvn test`
- 编译: `mvn -q compile`
- 端到端索引: `mvn -q package && java -jar target/anatomist.jar index <path> --no-classpath`

## Diorama 集成

| 路径 | 用途 |
|------|------|
| `.claude/skills/diorama/` | Diorama SDD skill 定义与脚本 |
| `.claude/commands/` | 触发用 slash command |
| `.diorama/knowledge/facts/` | survey 沉淀的项目知识(本目录) |
| `.diorama/knowledge/rules/` | 经验规则(experience.md) |
| `.diorama/tasks/<id>/` | 每个 task 的 proposal/design/tasks/task.json |
| `.diorama/session/` | 当前 session 指针 |

## 关键约定

- **Node ID 生成规则**: 见 [DESIGN.md §Node ID 生成规则](../../../DESIGN.md)
- **SQLite schema**: 见 [docs/scenario-1-index.md §完整 DDL](../../../docs/scenario-1-index.md);DDL 唯一权威源是 `src/main/resources/schema.sql`
- **测试策略**: 见 [docs/testing-strategy.md](../../../docs/testing-strategy.md)
- **不嵌入 LLM**: anatomist 自身不调用任何 LLM API,语义推理一律外包给 Agent

## 衰减检查结果

本次 survey 重新对照代码:

- glossary.json 所有 16 个 term 在 DESIGN.md 或源码中均能找到对应 → 无 stale
- domain-model.md 中 8 个 Extractor 与源码包结构一致(2 实现 + 5 骨架,1 个标准 Annotation 类 + 1 接口 + 7 个实现)→ 无 stale
- 已知漂移点: ANONYMOUS_CLASS / LAMBDA Node 类型已在 schema 与文档中保留位置,但代码尚未实现 — 这是计划内的 Phase 1 余量,不算 stale
