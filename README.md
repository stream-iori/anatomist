# anatomist

Java 代码结构索引与查询工具。源码只解析一次，事实写入 SQLite；1.0 的公开查询接口只有 `semantic-stream/v1` NDJSON 管道。

```text
Java source ── index ──> SQLite snapshot ── semantic pipeline ──> evidence
                             │
                             ├─ nodes / declarations / edges（非调用关系）
                             └─ call_site_owners / call_sites / targets（唯一调用事实）
```

## 快速开始

```bash
just jar                 # target/anatomist.jar
# just native            # target/anatomist

java -jar target/anatomist.jar index fixtures/mini-spring-shop \
  --project-source api/src/main/java:domain/src/main/java:service/src/main/java \
  --no-classpath --output /tmp/shop.db

java -jar target/anatomist.jar search OrderService --kind type --index /tmp/shop.db |
  java -jar target/anatomist.jar resolve --unique --index /tmp/shop.db |
  java -jar target/anatomist.jar members --recursive --index /tmp/shop.db
```

调用与源码证据：

```bash
java -jar target/anatomist.jar \
  resolve 'com.example.shop.service.OrderService#createOrder(com.example.shop.domain.dto.CreateOrderRequest)' \
  --kind callable --exact --unique --index /tmp/shop.db |
java -jar target/anatomist.jar calls --index /tmp/shop.db |
java -jar target/anatomist.jar dispatch --index /tmp/shop.db |
java -jar target/anatomist.jar source --index /tmp/shop.db
```

每一段都校验 `index_revision_id`、源码快照和语义配置。最终 `evidence` 不是 `complete` 时，不能据此断言“没有结果”。

## 1.0 查询模型

| 需求 | 管道 |
|---|---|
| 搜索并消歧 | `search | resolve --unique` |
| 类型成员 | `resolve | members [--recursive]` |
| 类型关系/实现 | `resolve | type-relations` / `runtime-implementations` |
| 调用点/派发候选 | `resolve | calls | dispatch` |
| 引用/字段访问 | `resolve | references` / `accesses` |
| 分支内事实 | `resolve | regions | sites-in` |
| 路径 | `resolve | trace --to ... --dispatch resolved|possible` |
| Spring/配置 | `search --kind artifact | resolve | members`，或 `bindings` |
| 精确源码 | `resolve --exact | source` |
| 文件声明 | `declarations-of --file ...` |
| 项目基线 | `overview` |

0.1x 的聚合查询命令已删除，没有别名。升级时旧索引也必须显式重建：

```bash
anatomist index . --recreate --output /path/to/index.db
```

详见 [1.0 迁移指南](docs/migration-1.0.md)。

## 配置

项目配置放在 `.anatomist/config.toml`。项目配置、用户配置和内置默认值三选一，不合并；CLI 参数优先。

```toml
[scan]
scopes = ["MAIN", "GENERATED"]
include = ["src/**"]
exclude = ["**/*IT.java"]

[extensions.lombok]
mode = "ast"
strict = false
```

完整模板见 [docs/config.toml](docs/config.toml)。Lombok 默认关闭；`ast` 只提供源码结构证据，不运行 Lombok，也不证明运行时行为。

## 能力边界

| 能做 | 不能证明 |
|---|---|
| Java 8–25 声明、类型、引用、调用点、字段访问 | 动态代理、AOP、运行时 profile 的实际选择 |
| Spring 注解与 XML 的静态配置事实 | 精确堆别名、路径可行性 |
| 有快照校验和分页的源码证据 | 跨语言分析、向量语义相似度 |
| 增量索引、健康诊断、GraalVM native | 把静态 dispatch candidate 当成运行观察 |

## 文档

| 文档 | 内容 |
|---|---|
| [入门](docs/getting-started.md) | 安装、索引、第一条管道 |
| [命令](docs/commands.md) | 1.0 CLI 与常用 recipe |
| [迁移](docs/migration-1.0.md) | 0.1x → 1.0，旧命令映射与重建规则 |
| [流协议](docs/semantic-stream-v1.md) | NDJSON framing、evidence、退出码 |
| [数据模型](docs/data-model.md) | schema 21 与调用点唯一存储 |
| [架构](docs/architecture.md) | 索引流水线和不变量 |
| [测试](docs/testing.md) | JUnit、pipeline golden、E2E、benchmark |
| [协作指南](AGENTS.md) / [Agent Skill](SKILL.md) | 开发和 Agent 使用约束 |

## 开发

```bash
just test
just golden-update
just smoke
just native-smoke
just bench-query-refactor
just bench-semantic-pipeline f5a9ef2
```

运行时依赖保持精简，JSON 手写，支持 native-image。禁止把本地数据库、机器路径或 smoke 产物提交到仓库。
