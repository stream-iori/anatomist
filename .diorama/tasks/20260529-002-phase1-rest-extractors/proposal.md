# 意图: 20260529-002-phase1-rest-extractors

## 你想做什么

补齐 Phase 1 余下的 **5 个 Extractor**,让 SQLite 边集合从当前 1 种(CONTAINS)扩到 9 种全集。

- **必做**(覆盖 DESIGN.md §存什么关系):
  - `FieldExtractor` — FIELD 节点 + CONTAINS Edge(class → field)
  - `AnnotationExtractor` — annotations 表写入,覆盖类/方法/字段/参数注解
  - `HierarchyExtractor` — INHERITS / IMPLEMENTS / OVERRIDES Edge
  - `ReferenceExtractor` — REFERENCES Edge(field_type / parameter_type / return_type / generic_arg)
  - `CallGraphExtractor` — CALLS Edge(call_kind: INSTANCE/STATIC/CONSTRUCTOR/SUPER/INTERFACE)
  - `FieldAccessExtractor` — READS / WRITES Edge

- **顺带处理**:
  - 移除 MethodExtractor 中 `isAnonymous || isLocal` 临时守卫,改由 TypeExtractor 显式发射 ANONYMOUS_CLASS Node 后再放行(本期是否做,specify 阶段确认)

- **明确不做**:
  - LAMBDA Node + Lambda 内 CALLS 折算遍历
  - METHOD_REF Node
  - 多模块 classpath 合并
  - 增量索引 / Watch
  - 文档索引 / 语义层

## 为什么做

Phase 1 MVP 已经走通"源码 → SQLite"主数据流(15 types / 46 methods / 46 CONTAINS in fixture)。但 Phase 2 查询命令(callers-of / callees-of / hierarchy / deps-of / implementors-of)无一可以工作,因为它们查询的边类型(CALLS/INHERITS/IMPLEMENTS/REFERENCES)全空。

这个 task 把 5 个骨架 Extractor 全部落地,让 Phase 2 有数据可查,且能在 fixture 上端到端验证:Order 应当 IMPLEMENTS 接口、OrderService 应当 CALLS PriceCalculator 等等。

## 需求类型

feature

## 约束条件

- **数据模型严格遵循 DESIGN.md / scenario-1-index.md**:edges 表 9 种 relation 全部按文档定义产生;call_kind 5 种取值;REFERENCES 的 context 4 种取值
- **外部依赖统一走 external_target_fqn**:`is_external=1 ⇒ target_id NULL & external_target_fqn 含完整擦除签名`,严格遵守 edges CHECK 约束
- **不引入新生产依赖**(继承上一 task 的 4 依赖预算)
- **null Binding 一律跳过 + unresolved 计数**,不发明半成品节点/边(R4)
- **fixture 端到端基线**:索引 `fixtures/mini-spring-shop` 后必须满足:
  - `OrderRepository` 至少 1 个实现类 → IMPLEMENTS 边 ≥ 1
  - `OrderService extends BaseService` → INHERITS 边 ≥ 1
  - `OrderService.createOrder` CALLS 至少 3 个被调方法 → CALLS 边 ≥ 3
  - `@Service` / `@Autowired` / `@Transactional` 注解 → annotations 表至少 6 行
  - `private OrderRepository orderRepository` 字段 → FIELD 节点 ≥ 4(服务类依赖注入字段)
- **回归基线不变**:已有的 21 个测试全绿,不修改 schema.sql,fixture 索引产出的 Types/Methods/CONTAINS 数量不减少
- **依然使用 ASTVisitor + 共享 ExtractionContext 模式**(继承自上一 task 的架构选择)
