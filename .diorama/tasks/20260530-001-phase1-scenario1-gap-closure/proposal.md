# 意图: Phase 1 场景 1 缺口全量补齐

## 你想做什么

对照 `docs/scenario-1-index.md`(Phase 1 索引场景的权威规范)与当前 `src/main/java/com/anatomist/` 实现，把 design 中明确列出但代码尚未落地的缺口全部补上：

1. **LAMBDA Node 提取** — design §TypeExtractor/MethodExtractor 与 §Node ID 生成规则均明确 `kind=LAMBDA`，ID 形如 `<parentMethodId>$lambda@L<line>C<col>`；当前 `IndexCommand.pruneDanglingInternalEdges` 是兜底丢弃，正确做法是把 LAMBDA 作为真实 Node 入库，并让 CallGraphExtractor / ReferenceExtractor / FieldAccessExtractor 在 lambda body 内以 lambda 节点为 source 产出边。
2. **METHOD_REF Node 提取** — `MethodReferenceExpr`，kind=METHOD_REF，指向目标方法。同样消除 pruneDanglingInternalEdges 的兜底分支。需要从 lambda/method-ref 节点出发产出 1 条 CALLS 边（call_kind 按目标方法判定）。
3. **Java 版本自动检测** — 当前 `--java-version` 默认 8 写死在 IndexCommand:99；未读 pom.xml。需按 design §11 实现：当前 `<maven.compiler.source>` > 当前 `<java.version>` > 默认 8；`--java-version` 显式参数仍然权威覆盖。
4. **isAccessor metadata 标记** — getter (`get*`/`is*` + 0 参 + 非 void) / setter (`set*` + 1 参 + void) 在 MethodExtractor 中识别后写入 metadata。
5. **RECORD 支持** — `RecordDeclaration` 当 `--java-version 16+` 时按 CLASS 规则提取，kind=`RECORD`（与 schema 兼容：kind 是 TEXT，无 CHECK 约束）。Record 组件按 FIELD 处理。

**已确认在范围外**：

- ~~Javadoc 字段~~：已确认 TypeExtractor / MethodExtractor / FieldExtractor 均已通过 `getJavadocComment().getContent()` 写入 `n.javadoc`，FTS5 索引同步生效，无缺口。
- 增量索引（Phase 4）
- Phase 2 的 documents / semantic_annotations 表与 enrich/annotate 命令
- 文档约定推导写入 semantic_annotations
- LOCAL_CLASS Node（CLAUDE.md 明确警告不在本任务范围扩展）

## 为什么做

- **现状**：上一 task `20260529-002-phase1-rest-extractors` 让 8 个 Extractor 全部"真实化"，但 LAMBDA/METHOD_REF 仍是空缺，靠 `IndexCommand.pruneDanglingInternalEdges` 兜底（fixture 上 Pruned dangling=3 条），这与 design 文档不一致。Java 版本默认 8 也限制了对现代项目的解析准确性。
- **价值**：消除 design 与 code 的语义漂移；让 fixture 验证基线中的 Pruned dangling 从 3 → 0；为 Phase 2 的语义查询（lambda body 内的 CALLS / REFERENCES / READS / WRITES 边能从 lambda 节点出发被正确归因）打好结构基础。
- **风险控制**：LAMBDA 作为 Node 一旦入库，会改变下游 `callees-of` 遍历的视图（design §CallGraphExtractor 提到 "GraphTraversal 在递归 CTE 遍历 CALLS 时，遇到 LAMBDA 节点透明跨越"），但 Phase 1 不涉及 query 命令实现，这部分是 Phase 2 责任；本 task 只确保 LAMBDA / METHOD_REF Node + 出入边正确入库，不引入 query 层的"跨越"逻辑。

## 需求类型

feature（功能补齐 + 少量 tech-debt 清理）

## 约束条件

- **不引入新生产依赖**（DESIGN.md 的 4 直接依赖预算继续生效）
- **schema 不变更**：`nodes.javadoc` 已存在；kind 列无 CHECK 约束，可直接新增 `LAMBDA`/`METHOD_REF`/`RECORD` 取值
- **Fixture 验证基线单调增长**：types/methods/fields/edges 数字只允许增加；`Pruned dangling` 在 LAMBDA + METHOD_REF 落地后应 = 0
- **`MethodExtractor.skipDeclaringType` 不变更其对 true LOCAL_CLASS 的跳过逻辑**（CLAUDE.md 明确警告）；本 task 不引入 LOCAL_CLASS Node
- **保持 Index/Query 两阶段隔离**：所有变更只触及 `core/` + `extract/` + `cli/IndexCommand`，不引入 query 层代码
- **单元测试驱动**：每个新增 Node kind 至少 1 个 Extractor 级单元测试 + IndexCommandIT 中 fixture 数字校验
