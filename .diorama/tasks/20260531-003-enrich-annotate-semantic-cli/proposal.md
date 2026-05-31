# 意图: 20260531-003-enrich-annotate-semantic-cli

## 你想做什么

实现 DESIGN.md §CLI 命令表里 Phase 2 标注但尚未落地的两条命令：

1. **`anatomist enrich --node <fqn> | --package <pkg> [--format markdown|json] [--with-docs] [--depth N]`**
   把指定节点（或整包）的「结构 + javadoc + 关联业务文档片段 + 已有语义注解 + 建议下一步查询」一次性打包输出，默认 markdown，给 Agent / 人都能直接消费。

2. **`anatomist annotate <node-id> --label <text> --category <text> [--context <text>] [--source LLM|DOC|JAVADOC] [--confidence HIGH|MEDIUM|LOW] [--from-json <file>]`**
   把 Agent / 人合成出的业务语义结论写回 `semantic_annotations` 表。重复写同 `(node_id, category, source)` 应**更新**（INSERT OR REPLACE 语义），避免堆积。`--from-json` 支持批量写入。

3. **`anatomist-skill.md` 增加一节 "Writing architecture docs from code"** 工作流模板，把 enrich + annotate + 现有 `callers-of` / `context` / `index-docs` 串成「从代码 + 业务文档 → 业务意图 markdown → 写回索引」的闭环。

## 为什么做

DESIGN.md 场景 2（查询）已经做完，scenario 5（export mermaid/json）实际价值低（hairball 图没人读、JSON 导出给不存在的工具用），而**真正缺的是从「结构查询」到「业务语义沉淀」的桥**。

现有底座已具备：

- `documents` 表 + `index-docs` CLI（业务文档已能入库 + doc_content FTS5）
- `semantic_annotations` 表（schema 已建）
- `SemanticPostProcessor` + naming convention（已自动产 6 条 fixture 上的注解）
- 11 个查询子命令（context / hierarchy / callers-of / callees-of / deps-of / 等）

但 Agent **没法把自己推理出的领域知识写回索引**——下次另一个 Agent 来问"OrderService 是什么聚合根"还要从头推理。`enrich` + `annotate` 这两条 CLI 是闭环的最后一块。

加上 skill 引导，Agent 才会主动想到这个用法，否则纯 CLI 没人调。

## 需求类型

feature

## 约束条件

- **不内嵌 LLM**：anatomist 设计原则之一。`enrich` 只是给 Agent / 人喂结构化料，`annotate` 只是写回。语义合成由调用方做。
- **不引入新依赖**：守 4 条 prod dep 预算（javaparser-symbol-solver-core / sqlite-jdbc / picocli / jackson-databind）。
- **不破现有测试**：96 单元 + 62 IT 全绿是底线。
- **scenario-2 CLI JSON 契约不动**：golden file 8 用例的 expected.json 不应需要刷新。
- **enrich 默认 markdown 输出**应控制在 ~200 行内（mini-spring-shop OrderService 量级），过长则 Agent 上下文窗口浪费、人看也累。
- **annotate 写入语义**：`source` 字段必须严格走 schema CHECK（`CONVENTION|JAVADOC|DOC|LLM`），重复写同 (node_id, category, source) 走 upsert，不堆历史行。
- **不实现 scenario 5 的 export 系列**（mermaid/json/subgraph 导出）——明确放弃，未来按需再做。
