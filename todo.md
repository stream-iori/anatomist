# TODO

## 未来：横切关注点 Tags（运行时质量属性）

基于 ISO 25010 质量属性分组，5 维度 8 Tags。这里只记录可从代码直接观察到的事实，不做 DDD/架构层推断。

| 质量属性 | 维度问题 | Tags |
|----------|---------|------|
| **执行模型** | 以什么方式执行？ | `ASYNC`, `SCHEDULED` |
| **数据一致性** | 如何保障正确性？ | `TX`, `IDEMPOTENT` |
| **韧性** | 失败时如何应对？ | `COMPENSABLE`, `DEGRADES` |
| **性能** | 有什么优化手段？ | `CACHED` |
| **安全** | 谁能调用？ | `SECURED` |

### Tag × 推断级别矩阵

| Tag | 含义 | L1 注解 | L2 调用模式 | L3 Agent |
|-----|------|---------|------------|----------|
| `ASYNC` | 异步执行 | @Async | ExecutorService.submit, CompletableFuture.supplyAsync | |
| `SCHEDULED` | 时间触发 | @Scheduled | ScheduledExecutorService, XML `<task:scheduled>` | |
| `TX` | 事务边界内 | @Transactional | TransactionTemplate.execute, PlatformTransactionManager | |
| `IDEMPOTENT` | 幂等设计 | | | ✓ |
| `COMPENSABLE` | 有补偿/回滚逻辑 | | | ✓ |
| `DEGRADES` | 降级/熔断/fallback | @CircuitBreaker, @Retryable | HystrixCommand, Resilience4j | ✓ |
| `CACHED` | 有缓存 | @Cacheable | Cache.get, RedisTemplate.opsForValue | |
| `SECURED` | 有权限控制 | @PreAuthorize, @Secured, @RolesAllowed | SecurityContext.getAuthentication | |

### 推断策略

| 级别 | 来源 | 确定性 | 实现方式 |
|------|------|--------|---------|
| **L1 注解推断** | `annotations` 表直接匹配 | 高 | 固定规则，零误判 |
| **L2 调用模式推断** | `edges(relation='CALLS')` 匹配特定 API | 中 | 可配置规则表，基于已有调用图 |
| **L3 Agent 判断** | LLM 分析代码语义 | 低 | Agent prompt 模板，fallback |

**优先级**：中。等核心迁移场景验证后实施。

---

## 未来：Agent prompt 模板

为 Agent 提供结构化 prompt 模板，指导如何：
- 使用 anatomist 查询结果推理架构结论
- 输出格式为分析报告或源码 diff
- 结合 diorama domain-model.json 做交叉验证

---

## 未来：与 diorama-sdd 的集成（Agent 消费模式）

**角色分工**：
- **diorama-sdd** — 维护领域模型声明（"应该是什么"）
- **anatomist** — 提供代码结构查询能力（"实际是什么"）
- **Agent** — 读入两者，推理差异，输出结论

anatomist 不内置 verify/suggestions 命令。Agent 自己组合 anatomist 查询完成验证，再通过 diorama 的流程（consolidate/annotate）回写。

### diorama 提供的代码锚点（Agent 用于定位查询）

| diorama 字段 | Agent 用什么 anatomist 命令验证 |
|---|---|
| `entity.fqn` | `anatomist context <fqn>` — 类是否存在、结构是否匹配 |
| `glossary.term.code_refs[]` | `anatomist context <fqn>` — 类是否存在、结构是否一致 |
| `bounded_context.packages[]` | `anatomist deps-of <class>` — 检查跨 context 依赖 |
| `business_rules.implemented_by[]` | `anatomist callers-of <method>` — 方法是否存在、是否被调用 |
| `scenarios.participants[].fqn` | `anatomist callees-of <entry> --depth N` — 实际调用链是否覆盖参与者 |

### Agent 验证工作流示例

```
1. Agent 读入 .diorama/knowledge/facts/domain-model.json
2. 对每个 entity.fqn：
     anatomist context <fqn> → 存在？结构匹配？
     不存在 → 标记 stale 或建议更新 fqn
3. 对每个 bounded_context.packages：
     anatomist deps-of <pkg内的类> → 检查是否有跨 context 依赖
     有不当依赖 → 报告 "缺少 ACL"
4. 对每个 business_rules.implemented_by：
     anatomist context <method-fqn> → 方法是否存在
     anatomist field-access <aggregate-field> --mode writes → 所有写入路径是否经过 guard
5. 验证结果 → Agent 自行写回 diorama（更新 stale 标记 / consolidate 建议）
```

---

## 远期：外部指标索引（index-metrics）

anatomist 不重新实现代码质量指标和测试覆盖——这些由成熟 Maven 插件完成。anatomist 的价值是**把分散的报告统一关联到同一份代码图谱上**。

```bash
# 现有工具生成报告
mvn pmd:pmd           → target/pmd.xml（圈复杂度 / LOC / 代码坏味道）
mvn jacoco:report     → target/site/jacoco/jacoco.xml（方法级测试覆盖率）

# anatomist 索引报告，关联到已有 nodes
anatomist index-metrics --pmd target/pmd.xml --jacoco target/site/jacoco/jacoco.xml
```

| 数据源 | 提供什么 | Agent 能回答 |
|--------|---------|-------------|
| PMD | 圈复杂度、LOC、参数数量 | "哪些方法过于复杂？" |
| JaCoCo | 方法级覆盖率 | "哪些关键方法完全没有测试？" |
| git log | 变更频率、最后修改时间 | "哪些高复杂度方法还在频繁变更？"（重构优先级） |

存储：独立 `metrics` 表或扩展 `nodes.metadata`，不侵入核心 schema。

**优先级**：低。当前分层迁移场景不依赖这些指标。
