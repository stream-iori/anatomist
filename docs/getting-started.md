# Getting Started

## 安装

macOS Apple Silicon 一键安装：

```bash
curl -fsSL http://6.12.3.250:8100/dist-bin/install.sh | sh -s -- --components anatomist
anatomist --version
```

默认安装到 `~/.local/bin/anatomist`，并为支持的 Agent 客户端安装同版本 `SKILL.md`。可用 `ANATOMIST_INSTALL_DIR` 改目录，用 `DIORAMA_CLIENTS=codex` 只安装指定客户端，用 `ANATOMIST_INSTALL_SKILL=0` 跳过 skill。

GitHub Release 资产：

| 平台 | 资产 |
|---|---|
| macOS Apple Silicon | `anatomist-darwin-aarch64` |
| Linux amd64 | `anatomist-linux-amd64` |
| JVM | `anatomist.jar` |

源码构建需要 JDK/GraalVM 25、Maven 3.9+ 和 `just`：

```bash
just jar       # target/anatomist.jar
just native    # target/anatomist
```

## 建立索引

```bash
anatomist index fixtures/mini-spring-shop \
  --project-source api/src/main/java:domain/src/main/java:service/src/main/java \
  --no-classpath --output /tmp/shop.db

anatomist doctor --agent-preflight --format json --index /tmp/shop.db
```

常用参数：

| 参数 | 说明 |
|---|---|
| `--output` | SQLite 路径 |
| `--incremental` | 只处理变化文件和受影响闭包 |
| `--verify-content` | 不信任 mtime/size，重新计算文件 hash |
| `--spring-xml` | 加入 Spring XML 静态配置 |
| `--lombok ast` | 加入源码级 Lombok 签名证据 |
| `--health-policy integrity` | parse/graph 不完整则拒绝提交 |
| `--scan-scope/--scan-include/--scan-exclude` | 控制扫描范围 |
| `--java-version/--jdk-home` | 目标语言版本和本地 JDK 类型目录 |

`.anatomist/config.toml`、用户配置、内置默认值三选一，不合并。完整配置见 [config.toml](config.toml)。

```toml
[scan]
scopes = ["MAIN", "GENERATED"]
include = ["src/**"]
exclude = ["**/*IT.java"]
```

每次源码修改后，先通过索引门禁再查询：

```bash
anatomist index . --incremental --health-policy integrity --format json --output /tmp/project.db &&
anatomist doctor --agent-preflight --format json --index /tmp/project.db
```

## 第一条查询管道

多段查询优先用单进程入口，公共参数只写一次：

```bash
anatomist pipeline --index /tmp/shop.db -- \
  search OrderService --kind type \
  --then resolve --unique \
  --then members --recursive
```

精确方法源码：

```bash
anatomist pipeline --index /tmp/shop.db -- \
  resolve 'com.example.shop.service.OrderService#createOrder(com.example.shop.domain.dto.CreateOrderRequest)' \
    --kind callable --exact --unique \
  --then source --limit 200
```

调用点、静态派发候选和源码：

```bash
anatomist pipeline --index /tmp/project.db -- \
  resolve 'p.A#run()' --kind callable --exact --unique \
  --then calls --direction outgoing \
  --then dispatch --algorithm auto \
  --then source
```

原 Unix 管道继续支持。需要连接外部程序或让不同段使用不同 scope/module 时，使用 `set -o pipefail` 并给每段传同一个索引。

## 如何读输出

查询默认是 NDJSON。典型流：

```text
entity/call_site/... records
evidence(scope=seed)
evidence(scope=stream)   ← 最终完整性结论
```

| 看到什么 | 怎么做 |
|---|---|
| `coverage=complete` 且 `negative_conclusion_safe=true` | 空结果可以作为否定证据 |
| `truncated=true` | 增大 `--limit` 或继续 `--offset` |
| `partial/unknown/indeterminate` | 补 classpath、能力或上游证据 |
| revision/snapshot/profile 冲突，exit 4 | 不要拼接不同索引快照的流 |

`calls` 是源码调用点和静态 target；`dispatch` 是可能目标。两者都不证明运行时执行。

## 从 0.1x 升级

1.0 不读取旧 schema，也不保留旧查询命令。显式重建：

```bash
anatomist index /path/to/project --recreate --output /path/to/index.db
```

未传 `--recreate` 时不会静默删除数据库。完整命令映射见 [migration-1.0.md](migration-1.0.md)。

## 下一步

| 文档 | 用途 |
|---|---|
| [commands.md](commands.md) | 完整命令和 recipe |
| [semantic-stream-v1.md](semantic-stream-v1.md) | framing、evidence、退出码 |
| [data-model.md](data-model.md) | schema 21 存储模型 |
| [testing.md](testing.md) | 测试与性能门禁 |
| [troubleshooting.md](troubleshooting.md) | 索引和环境排查 |
