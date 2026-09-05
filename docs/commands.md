# Anatomist 1.0 命令参考

结论：查询接口只有 `semantic-stream/v1`。除 `index`、`doctor`、`annotate` 外，查询默认输出 NDJSON；精确参数以同版本二进制的 `anatomist <command> --help` 为准。

## 基本规则

```text
生产 seed       扩展/变换 seed                 取证
search ───────> resolve ─┬─ members ─────────> source
                         ├─ calls ─ dispatch ─> source
                         ├─ references
                         ├─ regions ─ sites-in
                         └─ type-relations / runtime-implementations
```

| 规则 | 含义 |
|---|---|
| 所有管道段使用同一个 `--index` | 防止读错数据库；流还会校验 revision/snapshot/profile |
| 默认 `--scope MAIN` | 测试或生成源码必须显式选 `TEST`、`GENERATED` 或 `ALL` |
| `--format ndjson` | 可流式组合；`json`/`table` 是终端展示 |
| `--accept-unframed` | 仅接收外部 data-only 输入，强制降低 coverage，不能做否定结论 |
| `--on-unsupported fail` | 缺能力默认退出 3；`continue` 会保留不完整 evidence |
| final evidence 不 complete | 继续分页/补证，不得把空结果当成不存在 |

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
| `--project-source <path-list>` | 覆盖自动探测的源码根 |
| `--source-root module@SCOPE=path` | 精确指定模块、scope 与根；可重复 |
| `--scan-scope/--scan-include/--scan-exclude` | 控制扫描范围 |
| `--include-tests` | 加入 TEST scope |
| `--no-classpath` / `--classpath` | 关闭或覆盖依赖解析 |
| `--java-version` / `--jdk-home` | 指定目标语言级别和 JDK 类型目录 |
| `--spring-xml` | 索引 Spring `<beans>` 配置 |
| `--lombok off\|ast` | 开启源码级 Lombok 结构模型 |
| `--verify-content` | 增量时对每个文件重新算 hash |
| `--timings` | 输出各阶段耗时 |
| `--format json` | 机器可读构建结果 |

1.0 schema 21 / graph semantics 4 不兼容 0.1x。已有数据库不兼容时命令会失败并报告将丢弃的文档/语义数据量；只有显式 `--recreate` 才会删除并重建，不会静默覆盖。

### `doctor`

```bash
anatomist doctor --agent-preflight --format json --index <db>
anatomist doctor --health-policy complete --format json --index <db>
```

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
anatomist search OrderService --count --index <db>
```

默认 NDJSON。`--count` 发出 `result_count`，不受 `--limit` 截断。`--name` 的 `*`、`?` 是 glob；SQL 通配字符 `%`、`_` 按普通字符处理。

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
| `describe` | entity | entity description | 单实体结构摘要 |
| `members` | container entity | entity | `--recursive --kind --max-depth --limit` |
| `annotations` | entity | annotation | 源码注解事实 |
| `related-docs` | entity | document_relation | `--limit`，启发式关联 |
| `type-relations` | type entity | type_relation | `--direction --semantic --transitive` |
| `runtime-implementations` | type entity | entity + proof | `--instantiability --world`；开放世界可能不完整 |
| `callable-relations` | callable entity | callable_relation | override/contract，不是调用 |
| `calls` | callable entity | call_site | `--direction outgoing\|incoming --limit`；直接源码调用点 |
| `dispatch` | call_site | dispatch_target | `--algorithm --world`；静态候选，不是运行观察 |
| `references` | entity | reference_relation | `--direction incoming\|outgoing --limit` |
| `accesses` | value/entity | access_site | `--mode reads\|writes\|all --limit` |
| `bindings` | entity | binding_relation | `--direction --semantic`；Spring/配置跨域关系 |
| `regions` | callable entity | control_region | `--kind branch --limit` |
| `sites-in` | control_region | site records | `--record all\|... --limit` |
| `trace` | entity | trace_step | `--to --max-depth --dispatch resolved\|possible` |
| `source` | entity/site/dispatch | source_slice | `--limit 1..1000 --offset`，校验源码快照 |

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

## 常用 recipe

```bash
# 精确读方法
anatomist resolve 'p.A#run()' --kind callable --exact --unique --index <db> |
  anatomist source --index <db>

# 直接调用 + 可能派发目标 + 对应源码
anatomist resolve 'p.A#run()' --kind callable --exact --unique --index <db> |
  anatomist calls --index <db> |
  anatomist dispatch --index <db> |
  anatomist source --index <db>

# 反向调用影响
anatomist resolve 'p.Target#go()' --kind callable --exact --unique --index <db> |
  anatomist calls --direction incoming --index <db>

# 类型的所有成员引用
anatomist resolve p.A --kind type --unique --index <db> |
  anatomist members --recursive --index <db> |
  anatomist references --direction outgoing --index <db>

# 分支中的调用/访问点
anatomist resolve 'p.A#run()' --kind callable --exact --unique --index <db> |
  anatomist regions --kind branch --index <db> |
  anatomist sites-in --record all --index <db>
```

## 已删除的 0.1x 命令

`context`、`callees-of`、`callers-of`、`branches-of`、`bean-config`、`hierarchy`、`implementors-of`、`deps-of`、`used-by`、`field-access`、`call-path`、`survey-baseline`。

没有 alias。等价组合见 [迁移指南](migration-1.0.md)。
