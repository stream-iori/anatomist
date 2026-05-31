# 意图: Phase 2 语义层 + 文档索引层(切片 A+B 合并)

## 你想做什么

按 `docs/scenario-1-index.md` §Phase 2 推进,本 task 一次性落地 **语义层后处理** 和 **文档索引层**,把"结构 → 语义"和"代码 ↔ 文档"两条链路都打通,但**不**做 LLM 工作流(`enrich` / `annotate` 留到后续 task)。

### 范围内

1. **建 `semantic_annotations` 表**(docs §981, §1142-1158)
   - 列: id / node_id / doc_id / category / business_label / business_description / domain_context / source / confidence / created_at
   - source ∈ {CONVENTION, JAVADOC, DOC, LLM};confidence ∈ {HIGH, MEDIUM, LOW}
   - FK: node_id → nodes.id(可空), doc_id → documents.id(可空)

2. **建 `documents` + `doc_content` (FTS5) 表**(docs §1065-1086)
   - documents 列: id / path / title / content / doc_type / module / indexed_at
   - doc_type ∈ {README, DOC, ADR, CHANGELOG, API_SPEC}(API_SPEC 列保留,本期不写入)
   - doc_content FTS5 镜像 documents.title/content/doc_type,触发器同步

3. **约定推导写入 semantic_annotations(source=CONVENTION, confidence=MEDIUM)** —— 11 条规则全实现(docs §1043-1055):

   | 来源 | 规则 | category |
   |------|------|----------|
   | annotations | `@Service` | BUSINESS_SERVICE |
   | annotations | `@Repository` | DATA_ACCESS |
   | annotations | `@RestController` / `@Controller` | API_ENDPOINT |
   | annotations | `@Entity` | DOMAIN_MODEL |
   | annotations | `@Transactional` | TRANSACTION_BOUNDARY |
   | annotations | `@Component` | INFRASTRUCTURE |
   | nodes.label | `*Service` | BUSINESS_SERVICE |
   | nodes.label | `*DTO` / `*Request` / `*Response` | DTO |
   | nodes.label | `*Repository` / `*Dao` | DATA_ACCESS |
   | nodes.label | `*Controller` | API_ENDPOINT |
   | nodes.label | `*Config` / `*Configuration` | INFRASTRUCTURE |

   - 注解类规则优先级高于命名匹配;同节点多规则命中时全部写入(后续 query 层按 confidence/source 决策)
   - 推导在 `anatomist index` 流程末尾自动执行(隶属 index, 不引入新子命令)

4. **Javadoc 二次提炼到 semantic_annotations(source=JAVADOC, confidence=HIGH)**
   - 扫描 `nodes.javadoc` 非空的 Node,把 Javadoc 第一段(摘要句)写入 `business_description`
   - 触发点同上(随 index 自动执行)

5. **新 CLI `anatomist index-docs <path> [--output ...]`** —— 独立子命令
   - 扫描范围(本期): `README.md` / `docs/**/*.md` / `**/ADR-*.md`
   - 不扫描: `CHANGELOG.md` / `swagger*.json` / `openapi*.json`(后续 task)
   - title 解析: markdown 第一个 `#` 标题;无则取文件名
   - doc_type 判定: 路径模式 → README / ADR / DOC
   - module 字段: 多模块项目下从路径推断(如 `domain/docs/...` → module=domain),无则 null
   - 写入 documents 表 + doc_content FTS5 同步

### 范围外(明确推迟)

- `anatomist enrich --node` / `--package` —— Agent LLM 工作流输入
- `anatomist annotate <node-id> ...` —— Agent LLM 工作流写回
- 文档→代码 FTS5 关联辅助命令(docs §1107-1114 示例)
- API_SPEC (swagger/openapi JSON 解析) —— 独立心智模型
- Phase 4 向量相似度
- 增量索引

## 为什么做

- **现状**: Phase 1 结构索引 + 语义层(Javadoc)已就绪,但 `nodes.javadoc` 是"原文存档",还没二次提炼到 `semantic_annotations` 供查询;约定推导规则在 docs §1043 列得很清楚但代码零实现;`documents` 表仅在 docs DDL 注释里出现。这三块共同构成"结构 → 语义"的最低闭环。
- **价值**:
  - `semantic_annotations(source=CONVENTION)` + `(source=JAVADOC)` 让 Agent 在 query 阶段就能直接按业务语义(`category=BUSINESS_SERVICE`)过滤节点,无需重复跑 LLM
  - `documents` + `doc_content` FTS5 让 Agent 用关键词命中文档片段,与 nodes 关联(`OrderService` → 命中 README 中的描述段落)
  - 一次 merge-back 把"建表 + CONVENTION 推导 + JAVADOC 提炼 + index-docs"四件事打包,避免半成品状态(只有表无写入 / 只有写入无文档源)
- **风险控制**:
  - schema 新增 2 表 + 1 FTS5,**不修改** nodes/edges/annotations DDL,Phase 1 索引零回归
  - 推导逻辑纯 SQL/Java 后处理,不触发 JavaParser,index 性能影响 < 5%
  - `index-docs` 独立子命令,与 `index` 完全解耦,任一失败不影响另一方

## 需求类型

feature(Phase 2 首切片)

## 约束条件

- **不引入新生产依赖**(4 直接依赖预算继续生效;markdown 标题解析手写正则,不引入 commonmark)
- **schema 兼容向前**: `documents` / `doc_content` / `semantic_annotations` 三张新表 + 触发器追加到 `schema.sql`,既有表零改动
- **索引器零回归**: Phase 1 fixture baseline(16 types / 47 methods / 75 CONTAINS / ≥1 LAMBDA / ≥1 METHOD_REF)必须保持
- **CLI 兼容**: `anatomist index` 默认行为不变(自动包含 CONVENTION + JAVADOC 推导,但产出统计输出明示 `Semantic annotations: <n>`);`index-docs` 是新增子命令
- **测试覆盖**: 11 条约定规则各 1 个单元用例(可参数化);Javadoc 提炼 ≥ 2 用例(有/无 Javadoc);index-docs ≥ 3 用例(README / docs/ / ADR);IndexCommandIT 验证 fixture 上 semantic_annotations 行数 ≥ 期望基线
- **Index/Query 隔离**: 推导逻辑全部在 index phase 完成,query 侧只读
- **多模块路径推断**: index-docs 的 module 字段从路径前缀解析,不要求 pom.xml 参与
