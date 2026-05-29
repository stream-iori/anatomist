# Review: Design Conformance Check

## 职责

Review 对照 `design.md` 和 `rules/index.md` 验证 generate 产出的代码是否符合设计意图和项目规则。

它应：
- 遍历 design.md 中每个 REQ-/BR-/AC- 编号，确认在 tasks.md 和生成代码中有对应实现
- 读取 rules/index.md 中指向的规则文件，逐条检查规则，标注合规/不合规
- 输出结构化的合规报告，供 Agent 或人工决策
- 作为 generate 后置步骤自动执行（workflow-generate.md conformance-check）
- 通过 `/diorama review` 命令随时手动触发

它不应：
- 替代 Gate 命令的编译/测试验证
- 自动修改代码
- 决定是否阻止 phase-exit（仅提供事实，由 Agent/人工决定）
- 重新执行 plan 阶段的业务分析

## 触发方式

### 自动触发（generate 后置步骤）

generate 所有子阶段完成后，phase-exit 之前自动执行（见 `workflow-generate.md` 后置步骤）。

### 手动触发

```text
/diorama review
```

可在任何阶段手动触发，适用于：
- generate 完成后代码修改，需重新验证合规性
- 人工修改代码后对照 design.md 验证
- CI/CD 流程中的合规检查

## 输入

- `.diorama/tasks/<current_task>/design.md` — 提取 REQ-/BR-/AC- 编号及验收标准
- `.diorama/tasks/<current_task>/tasks.md` — 提取任务与 REQ-/BR- 的追溯关系
- `.diorama/tasks/<current_task>/task.json` — 读取 anchors 确定代码文件路径
- `.diorama/knowledge/rules/index.md` — 规则来源索引，读取后按链接读取源文件验证规则文件（如存在）
- anchors 指向的代码文件 — 逐文件验证实现内容

## 提取规则

### 从 design.md 提取

- §3 功能需求中的 `REQ-NNN` — 功能需求追溯
- §4 业务规则中的 `BR-NNN` — 业务规则追溯
- §10 验收标准中的 `AC-NNN` — 验收条件追溯
- §5 场景规格中的场景编号 `S\d+` — 场景覆盖
- §7 实现上下文中的变更清单 — 新增/修改/删除对应
- §8 接口契约中的接口定义 — 接口实现对应

### 从 tasks.md 提取

- `### T<N>:` 任务标题中的 `[REQ-NNN, BR-NNN]` 标注 — 任务与需求的追溯
- `**Status**: [x] done` — 任务完成状态
- 子阶段 checkbox 完成进度 — 细粒度进度

### 从 rules/index.md 提取

- 读取 index.md 中的规则条目（文件路径 + 类别 + 摘要）
- 按文件路径读取对应规则源文件
- 读取 index.md 中指向的规则文件，逐条对照检查合规性
- 若条目包含 `gate` 命令（第四段 `gate: <cmd>`），conformance-check 可选执行该命令并将结果纳入合规报告

## 输出格式

```markdown
# Conformance Report: [task name]

## REQ 追溯

| 编号 | 描述 | tasks.md 追溯 | 代码实现 | 状态 |
|------|------|-------------|---------|------|
| REQ-001 | [需求描述] | T1 [REQ-001, BR-001] | ✅ 已实现 | ✅ |
| REQ-002 | [需求描述] | T2 [REQ-002] | ❌ 未找到对应实现 | ❌ |

## BR 追溯

| 编号 | 规则 | 关联 REQ | tasks.md 追溯 | 代码实现 | 状态 |
|------|------|---------|-------------|---------|------|
| BR-001 | [规则描述] | REQ-001 | T1 | ✅ | ✅ |

## AC 验收追溯

| 编号 | 验收条件 | 关联 REQ | 测试覆盖 | 状态 |
|------|---------|---------|---------|------|
| AC-001 | [验收条件] | REQ-001 | ✅ T1 Phase 2 | ✅ |
| AC-002 | [验收条件] | REQ-002 | ❌ 无对应测试 | ❌ |

## 场景覆盖

| 场景 | 关联 REQ | tasks.md 追溯 | 测试覆盖 | 状态 |
|------|---------|-------------|---------|------|
| S1 | REQ-001 | T1 | ✅ | ✅ |
| S2 | REQ-002 | T2 | ❌ | ❌ |

## Rules 合规

| 规则 | 类别 | 合规 | 说明 |
|------|-------|------|------|
| I. 场景驱动 | 测试策略 | ✅ | 测试方法以场景命名 |
| II. 外部依赖隔离 | 测试策略 | ✅ | HTTP 依赖使用 MockWebServer |
| III. 失败行为覆盖 | 测试策略 | ❌ | 缺少超时/降级测试 |
| IV. 可观测性验证 | 测试策略 | ⚠️ | 部分场景缺少事件验证 |
| 维度1: 主流程正确性 | 经验规则 | ✅ | S1, S2 已覆盖 |
| 维度3: 依赖失败与恢复 | 经验规则 | ❌ | 无外部超时测试 |

## 统计

- REQ 追溯: ✅ N/❌ M
- BR 追溯: ✅ N/❌ M
- AC 验收: ✅ N/❌ M
- 场景覆盖: ✅ N/❌ M
- Rules 合规: ✅ N/❌ M
- 整体评估: PASS / CONDITIONAL / FAIL
```

## 标注规则

### 追溯状态

- `✅`：design.md 编号在 tasks.md 中有追溯且代码有对应实现
- `⚠️`：有追溯但实现不完整或不明确
- `❌`：无追溯或无对应实现

### Rules 合规

- `✅`：规则被明确满足
- `⚠️`：部分满足或不明确
- `❌`：违反规则

### 整体评估

- `PASS`：所有 REQ 有追溯、所有规则合规
- `CONDITIONAL`：所有 REQ 有追溯、但存在规则不合规项
- `FAIL`：存在 REQ 无追溯

## 与 generate 的关系

- Review 作为 generate 后置步骤在 phase-exit 前执行
- Review 结果不阻塞 phase-exit（仅提供事实）
- 若 Review 结果为 FAIL，Agent 应优先修正后重新执行 Review
- 若 Review 结果为 CONDITIONAL，Agent 应提示不合规项并建议是否修正

## 与 coverage-matrix 的关系

| 维度 | coverage-matrix | review |
|------|----------------|--------|
| 关注点 | 8 维度覆盖广度 | design.md → 代码追溯深度 |
| 触发 | `/diorama coverage-matrix` | generate 后自动 + `/diorama review` |
| 输出 | 覆盖矩阵 | 合规报告 |
| 关系 | 维度 5/7 辅助判断 | 独立于覆盖矩阵，聚焦追溯 |

## 不属于 task phase

`/diorama review` 手动触发时不影响 task phase checkpoints，不更新 session dirty 状态。

generate 后置步骤中的自动 conformance-check 同样不写入 phase checkpoints，仅作为 phase-exit 前的验证参考。

## 最低完成判定

满足以下条件，conformance-check 可视为已完成：

- 已提取 design.md 中所有 REQ-/BR-/AC- 编号
- 已对照 tasks.md 和代码文件完成追溯
- 已输出合规报告（含整体评估）
- 若 rules/index.md 存在，已读取索引中规则文件并检查规则合规

若 design.md 不存在或为空，输出提示"design.md 不存在，无法执行 conformance-check"。
