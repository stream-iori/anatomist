# 1.1.0 多版本索引验收

## 三阶段交付

| 阶段 | 已实现 | 成本与边界 |
|---|---|---|
| 1：不可变版本 | Git ref／SHA／WORKTREE、独立 catalog、显式版本查询、历史源码、文件和声明 diff | 每个版本保留完整 SQLite；不改当前 checkout |
| 2：增量和影响分析 | 兼容祖先选择、SQLite backup、跨提交增量、关系多重集 diff、分版本反向调用影响 | 复制成本 O(数据库大小)；不兼容或成本过高时回退全量 |
| 3：缓存和维护 | SHA-256 内容去重、命中复用、pin、GC 预览／执行、并发保护、JAR／native 验证 | 源码按不同内容占空间；图数据库空间随保留版本数增长 |

## 功能验收

| 检查 | 结果 |
|---|---|
| `mvn -q test`、最终 `mvn -q package` | 807 项，0 失败，0 错误，5 跳过 |
| `GitSnapshotsIT` | 18 项：历史源码、暂存／磁盘隔离、linked worktree、空提交、增删／重命名、merge-base、字段 diff、全量一致性、并发、失败隔离、环境标注、GC、外部源码拒绝发布 |
| `just golden-update` | 通过；已有场景输出无变化 |
| GraalVM native 构建 | macOS arm64，`anatomist 1.1.0` |
| `just smoke`、`just --no-deps native-smoke` | 通过；JAR／native 查询输出一致 |
| 多版本 CLI 端到端 | JAR／native 均通过，包括自动 diff、冻结源码、WORKTREE、pin、GC、doctor |

5 项跳过来自新 worktree 中未初始化的外部 fixture。性能测试另从已有
commons-lang checkout 克隆到临时目录运行，不修改原 fixture。

## 性能测量

2026-09-08，Apple M3 Pro／36 GiB／macOS arm64，GraalVM 25.0.3。
使用 `--no-classpath --java-version 25`；每个用例连续运行三轮，以下为
中位数，包含 CLI 启动、Git 工作树准备、复制和校验。不同用例不并行测量。
初次全量单独记录，不纳入表中的三轮全量重建中位数。

| 用例 | 全量重建 | 增量 | 缓存命中 | 增量提速 |
|---|---:|---:|---:|---:|
| 1,000 个简单 Java 文件／native | 1.397 s | 1.121 s | 0.219 s | 1.25× |
| 1,000 个简单 Java 文件／JAR | 2.527 s | 1.685 s | 0.478 s | 1.50× |
| commons-lang／native | 4.552 s | 1.641 s | 0.220 s | 2.77× |
| commons-lang／JAR | 7.862 s | 2.751 s | 0.466 s | 2.86× |

commons-lang 固定提交 `105a350b154eac7c3f3a6bac94ec2cfb0fbe232b`，
共有 401 个 Java 文件，其中 main 目录 215 个。
生成项目每轮修改一个方法返回值；commons-lang 每轮只改 StringUtils 的
注释，因此这些数据不是大规模签名变化或完整依赖解析的性能承诺。

| 成本观察（native） | 1,000 文件 | commons-lang |
|---|---:|---:|
| 每轮候选／重解析文件 | 1／1 | 1／1 |
| SQLite backup 中位数 | 9 ms | 56 ms |
| 每轮新写内容块 | 1 | 1 |
| 每轮复用文件内容 | 1,000 | 474 |
| GC 后总占用（3 个受保护快照及缓存） | 8,651,184 B | 94,733,401 B |

结论：方案已能复用解析结果、保留可验证历史证据；复杂文件上的收益更明显。
增量并非 O(改动文件数)：物化源码、文件校验和完整图复制仍有固定成本，
缓存命中路径则跳过构建。独立数据库是 1.1.0 的明确取舍，不宣称 MVCC 或
共享图存储已实现。

## 复现

```bash
mvn -q package
just native
just smoke
just --no-deps native-smoke
python3 scripts/snapshots-e2e.py --native target/anatomist --files 1000 --repeats 3 --report target/native-1000.json
python3 scripts/snapshots-e2e.py --jar target/anatomist.jar --files 1000 --repeats 3 --report target/jvm-1000.json
python3 scripts/snapshots-e2e.py --native target/anatomist --fixture fixtures/external/commons-lang --repeats 3 --report target/native-commons.json
```

`java` 和 Maven 需使用 `.sdkmanrc` 指定的 JDK。原始本地报告保存于
`release-dist/snapshots-benchmark-*.json`，不提交临时数据库或机器路径。
smoke 比较 JSON 数据，不把对象字段顺序视为语义差异；数组／记录顺序和
字段值仍须一致。
