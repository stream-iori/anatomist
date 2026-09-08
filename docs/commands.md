# Anatomist 1.2 命令参考

单版本查询使用 `semantic-stream/v1`。多版本比较使用独立的 `anatomist-diff/v2`，选择输出锚点后固定快照继续查询。

## Agent 信息入口

| 需要的信息 | 入口 |
|---|---|
| 最小查询与证据规则 | `anatomist skill core`，首次使用时读取 |
| 常见问题的直接查询 | `anatomist skill topics`，选路不清楚时读取 |
| 具体任务的继续条件与边界 | `skill explore/source/trace/relations/spring/versions/maintenance`，选择其中一个 |
| 参数、默认值、输入输出 | `<command> --help` |
| 机器可读契约与支持状态 | `operations <command>` |
| 组合是否合法 | `pipeline --explain` |
| 索引能力是否可用 | `pipeline --check`；健康/新鲜度问题使用 `doctor` |

已知完整方法签名时直接 `resolve --kind callable --exact --unique → source`。
`search` 用于发现候选；选择目标后再查询，不把歧义候选强行接入 `resolve --unique`。
方法逻辑、分支和值传播统一使用 `skill source`；`skill branch` 和 `skill flow` 已删除。

| 版本接口 | 用途 |
|---|---|
| `index . --ref HEAD` | 保存提交快照，默认尝试增量 |
| `index . --ref WORKTREE` | 冻结当前磁盘内容 |
| 查询／pipeline 的 `--ref`、`--snapshot` | 选择已建立的版本；与 `--index` 互斥 |
| `diff --base main --target feature [--merge-base] [--impact]` | 定位文本修改的双端声明；自动补建缺失快照，`--no-build` 禁止构建 |
| `snapshots list/show/pin/unpin/gc` | 查询、固定和显式清理历史 |

具体默认值、输出和生命周期见 [Git snapshots](git-snapshots.md)。

```text
execute ──异常/不确定──> help | operations <operation> | doctor | explain/check
```

## 机器可读操作目录

```bash
anatomist operations calls --index <db>
anatomist operations calls --language python
```

| 字段 | 含义 |
|---|---|
| `support` | 已安装 provider 是否实现 operation |
| `availability` | 当前 index 是否具备所需事实；无 `--index` 时为 `unchecked` |
| `coverage` | 不在目录中；只由实际查询 evidence 报告 |

默认 JSON 契约为 `anatomist-operation-catalog/v1`。目录公开输入输出 record、参数、硬限制和语言边界，但不提供 recipe 或自动选路。

## 基本规则

```text
生产 seed       扩展/变换 seed                 取证
search ───────> resolve ─┬─ members ─────────> source
                         ├─ calls ────────────> source
                         └─ calls ─ dispatch ─> source  (显式虚分派)
                         ├─ references
                         ├─ regions ─ sites-in
                         └─ type-relations / runtime-implementations
```

| 规则 | 含义 |
|---|---|
| 所有管道段使用同一个 `--index` | 防止读错数据库；流还会校验 revision/snapshot/profile |
| 默认 `--scope MAIN` | 测试或生成源码必须显式选 `TEST`、`GENERATED` 或 `ALL` |
| `--format ndjson` | 可流式组合；`json`/`table` 是终端展示 |
| `--accept-unframed` | transform 接收有 header、无 evidence 的数据，强制降低 coverage |
| `--on-unsupported fail` | 缺能力默认退出 3；`continue` 会保留不完整 evidence |
| final evidence 不 complete | 继续分页/补证，不得把空结果当成不存在 |

## 单进程 `pipeline`

```bash
anatomist pipeline --index <db> -- \
  resolve 'p.A#run()' --kind callable --exact --unique \
  --then calls --direction outgoing \
  --then source

anatomist pipeline --index <db> --file pipeline.json
anatomist pipeline --explain -- resolve p.A --kind type --unique --then describe
anatomist pipeline --check --index <db> -- resolve p.A --kind type --unique --then describe
```

```json
{"stages":[["resolve","p.A#run()","--kind","callable","--exact","--unique"],["calls"],["source"]]}
```

| 规则 | 结果 |
|---|---|
| `--index/--ref/--snapshot/--project/--module/--scope/--language/--provider/--format` | 只放在 `pipeline` 全局，stage 内会拒绝 |
| stage | 只允许 20 个只读 semantic-stream 查询命令 |
| 资源上限 | 16 stages、每段 256 argv、spec 1 MiB；typed 中间流沿用协议上限 |
| 执行 | 一个进程、一个只读 SQLite 连接；中间传 typed seed frame |
| `--explain` | 只做静态参数和组合检查，不打开 DB、不读 stdin |
| `--check` | 只读检查 index 和 operation availability，不执行 selector/query |
| 成功 | stdout 与等价 Shell 管道原始字节一致 |
| 失败 | exit 5；stderr 为一行结构化 JSON；不会写最终 stream evidence |

需要虚调用候选时显式追加 `dispatch`。跨 module/scope、外部程序或分支编排时使用 Shell。

## 索引和诊断

### `index`

```bash
anatomist index <project> --output <db>
anatomist index <project> --incremental --health-policy integrity --output <db>
anatomist index <project> --recreate --output <db>
```

常用参数：

| 参数 | 用途 |
|---|---|
| `--provider <id>` | 选择已安装的语言 provider；当前内置 `java-core` |
| `--project-source <path-list>` | 覆盖自动探测的源码根 |
| `--source-root module@SCOPE=path` | 精确指定模块、scope 与根；可重复 |
| `--scan-scope/--scan-include/--scan-exclude` | 控制扫描范围 |
| `--include-tests` | 加入 TEST scope |
| `--no-classpath` / `--classpath` | 关闭或覆盖依赖解析 |
| `--java-version` / `--jdk-home` | 指定目标语言级别和 JDK 类型目录 |
| `--spring-xml` | 索引 Spring `<beans>` 配置 |
| `--lombok off\|ast` | 开启源码级 Lombok 结构模型 |
| `--verify-content` | 增量时对每个文件重新算 hash |
| `--changed-files-from <file\|->` | 权威变更清单，逐行项目相对路径；空行和 `#` 注释忽略，`-` 读 stdin |
| `--timings` | 输出各阶段耗时 |
| `--format json` | 机器可读构建结果 |

当前 schema / graph semantics 版本由 `doctor` 报告；不兼容的旧索引需要显式重建。已有数据库不兼容时命令会失败并报告将丢弃的数据量；只有显式 `--recreate` 才会删除并重建，不会静默覆盖。

### `doctor`

```bash
anatomist doctor --agent-preflight --format json --index <db>
anatomist doctor --health-policy complete --format json --index <db>
```

| 字段 | 含义 |
|---|---|
| `index_identity` | 当前已提交索引的 revision、构建时源码快照、语义 profile；查询可以固定它 |
| `checkout` / `git` | worktree 路径、名字和 Git 辅助信息；只用于说明 |

版本比较优先使用 `diff --base ... --target ...`。版本服务会管理临时 detached worktree，
不切换用户当前分支；历史源码来自捕获内容。普通索引的 Doctor Git/快速脏检查仍只是提示，
需要当前磁盘证据时先增量同步。

`diff` 的声明命中包含注释和格式修改，不代表行为变化。每端锚点提供 snapshot ID、实体 ID、
module、scope、文件和可用声明范围；先选锚点，再运行：

```bash
anatomist pipeline --snapshot <anchor.snapshot_id> --scope <anchor.scope> -- resolve '<anchor.id>' --unique --then source
anatomist diff --base HEAD --target WORKTREE --module api --impact --impact-scope TEST
```

第二条选择 api 的修改，返回整个项目的 TEST 调用者；快照需先索引 TEST。
`--impact-module` 只筛选返回的调用者，不切断中间路径。`--impact-scope` 默认继承 `--scope`。
文件变化始终覆盖项目快照清单；`--scope/--module` 筛选声明和关系。
读取 `evidence.capabilities` 判断各项覆盖；影响仅为静态调用图中的可能影响，路径是每个修改点的最短代表路径。
完整字段和边界见 [diff v2](git-snapshots.md#diff-navigation-v2)。

| exit | 含义 |
|---:|---|
| 0 | 满足所选健康策略 |
| 3 | schema、语义版本、完整性或能力门禁失败 |

## Seed 入口

### `search`

```bash
anatomist search OrderService --kind type --limit 20 --index <db>
anatomist search --name '*Repository' --kind type --index <db>
anatomist search Deprecated --by-annotation --index <db>
anatomist search Component --by-annotation --include-meta --index <db>
anatomist search OrderService --count --index <db>
```

默认 NDJSON。`--count` 发出 `result_count`，不受 `--limit` 截断。`--name` 的 `*`、`?` 是 glob；SQL 通配字符 `%`、`_` 按普通字符处理。`--by-annotation` 默认只匹配直接注解；`--include-meta` 才展开最多 16 层、带环检测的 meta 注解。

### `resolve`

```bash
anatomist resolve 'p.A#run(java.lang.String)' --kind callable --exact --unique --index <db>
```

| `--kind` | 目标 |
|---|---|
| `entity` | 通用实体 |
| `type` | 类、接口、枚举、record |
| `callable` | 方法或构造器 |
| `value` | 字段等值实体 |

`--unique` 让歧义失败；`--exact` 要求完整 callable 签名。无 stdin 时可直接给 selector，有 stdin 时用于收敛 `search` 候选。

## 原子查询

| 命令 | 输入 | 输出 | 关键参数/边界 |
|---|---|---|---|
| `describe` | entity | declaration | 单实体结构摘要 |
| `members` | container entity | entity | `--recursive --kind --max-depth --limit` |
| `annotations` | entity | annotation | 默认直接注解；`--include-meta` 输出 `direct/meta_depth/via` |
| `related-docs` | entity | document_relation | `--limit`，启发式关联 |
| `type-relations` | type entity | type_relation | `--direction --semantic --transitive` |
| `runtime-implementations` | type entity | entity + proof | `--instantiability --world`；开放世界可能不完整 |
| `callable-relations` | callable entity | callable_relation | override/contract，不是调用 |
| `calls` | callable entity | call_site | `--direction outgoing\|incoming --limit`；直接源码调用点 |
| `dispatch` | call_site | dispatch_target | `--algorithm --world`；静态候选，不是运行观察 |
| `references` | entity | reference_site | `--direction incoming\|outgoing --limit` |
| `accesses` | value entity | access_site | `--mode reads\|writes\|all --limit` |
| `bindings` | entity | binding_relation | `--semantic member` 查询 XML factory/constructor/setter/init/destroy；保留 exact/ambiguous/unresolved |
| `regions` | callable entity | control_region | `--kind branch --limit` |
| `sites-in` | control_region | site records | `--record all\|... --limit` |
| `trace` | callable entity | trace | `--to --max-depth --dispatch resolved\|possible` |
| `source` | entity/declaration/site/control_region/dispatch_target | source_slice | `--limit 1..1000 --offset`，校验源码快照 |

## 独立查询

### `declarations-of`

```bash
anatomist declarations-of --file src/main/java/p/A.java \
  --kind type,method --visibility public,protected --limit 100 --index <db>
```

它从 AST declaration 表输出 `entity` 和 `evidence`，支持 `--module`、`--scope`、`--top-level-types`、`--direct-members`、`--include-synthetic`、`--offset`。

### `overview`

```bash
anatomist overview --index <db>
anatomist overview --deps-only --depth 2 --limit 50 --index <db>
```

输出 `project_summary`、`package_summary`、`package_dependency` 和 evidence。包依赖会同时计入普通关系与 `call_site_targets` 中的调用事实。

### `index-docs` / `annotate`

`index-docs` 建立可再生文档索引；`annotate` 写人工语义注记。旧库 `--recreate` 会丢弃它们，因此重建前必须按命令提示确认可再生性或备份来源。

## 常见组合

以下箭头对应 `--then`，以查询范围内的实际 evidence 判断是否足够。

```bash
# 精确读方法
anatomist pipeline --index <db> -- \
  resolve 'p.A#run()' --kind callable --exact --unique --then source

# 直接调用、可能派发目标及调用现场源码
anatomist pipeline --index <db> -- \
  resolve 'p.A#run()' --kind callable --exact --unique \
  --then calls --then dispatch --then source

# 直接调用者
anatomist pipeline --index <db> -- \
  resolve 'p.Target#go()' --kind callable --exact --unique \
  --then calls --direction incoming

# 类型成员的引用
anatomist pipeline --index <db> -- \
  resolve p.A --kind type --unique --then members --recursive \
  --then references --direction outgoing

# 已索引分支上下文中的调用/访问点
anatomist pipeline --index <db> -- \
  resolve 'p.A#run()' --kind callable --exact --unique \
  --then regions --kind branch --then sites-in --record all
```

## 已删除的 0.1x 命令

`context`、`callees-of`、`callers-of`、`branches-of`、`bean-config`、`hierarchy`、`implementors-of`、`deps-of`、`used-by`、`field-access`、`call-path`、`survey-baseline`。

没有 alias。等价组合见 [迁移指南](migration-1.0.md)。

## 查询结果的阅读边界

| 结果 | 含义 |
|---|---|
| `resolve → source` / `describe → source` | 读取所选声明；按需要分页 |
| `calls → source` / `dispatch → source` | 读取记录携带的调用现场；目标方法体需单独 resolve |
| `trace` | 深度范围内的一条调用路径；不能直接接 source |
| `regions` | 已索引调用/读写记录上的上下文，不是完整语法分支清单 |
| `sites-in` | 精确匹配记录中的上下文，嵌套上下文可能单列 |
| `runtime-implementations` | 静态可实例化候选，不是运行中的对象 |

源码的 `--offset` 为所选声明或现场范围内从零开始的行偏移；默认页长 200，最大 1000。
整个声明的结论需要在同一身份下覆盖全部页面；局部问题只读取足够回答的范围。

帮助的 Accepts/Emits 与 operations 使用同一操作契约。参数说明来自命令模型；
完整示例同时接受组合检查和 JVM/native 实际查询验证。

## Spring 解析参考

此处为实现边界参考；常见任务只需 `skill spring`。Meta 注解展开防环并限制为 16 层，
Spring 识别以解析后的框架 FQN 及同一闭包为依据，`@AliasFor` 不做属性重写。
XML 成员解析优先采用配置所属 module/scope，结合参数个数、显式 type/ref 提示、
静态/实例要求和继承成员寻找候选；普通字符串值不用于凭空推断参数类型。
成员输出保留 role、mechanism、symbol_ref、候选数量/序号和 resolution_status。
