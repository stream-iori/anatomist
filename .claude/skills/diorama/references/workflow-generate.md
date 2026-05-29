# Generate: Skeleton → DSL Test → Implementation

## 职责

根据 `tasks.md` 推进当前 task 的代码落地，而不是自由扩张领域模型。

它可以：
- 为当前 task 生成实现所必需的骨架代码
- 生成 DSL 测试代码
- 补全当前 task 对应的业务实现

它不应：
- 脱离当前 task 发明新的领域方向
- 无依据扩张领域模型
- 把不在 design.md / tasks.md 中确认的内容大量写进代码库

## Phase 协议

遵循 phase-protocol.md 通用 Entry/Exit 协议。

| 项 | 值 |
|----|-----|
| 前置输入 | tasks.md |
| 产物 | 代码 |
| anchors | append-only（不删已有） |
| next | consolidate |

## 子阶段与断点恢复

generate 内部每个任务分三个子阶段，进度由 `tasks.md` checklist 跟踪：

```text
Phase 1: Skeleton
Phase 2: DSL Test
Phase 3: Implementation
```

中断恢复时，agent 读取 `tasks.md` checklist 确定已完成的子项，从未勾选项继续。
详见 phase-protocol.md 中断恢复段。

### Anchors 使用

| 子阶段 | 参考 anchor | 用途 |
|--------|------------|------|
| Skeleton | `dev_entries` | 确定骨架类的目标文件路径和命名 |
| DSL Test | `acceptance_entries` | 确定测试类的目标文件路径和命名 |
| Implementation | 两者 | 验证实现代码与测试代码的路径与 anchors 一致 |

若 anchors 对应的路径在代码库中已存在，优先复用而非新建；若 anchors 为空，则从 design.md §6/§8 和 tasks.md 技术评估节推断路径。

## 输入

- `.diorama/tasks/<current_task>/design.md`
- `.diorama/tasks/<current_task>/tasks.md`
- `.diorama/tasks/<current_task>/task.json`（**必须读取 `anchors.dev_entries` 和 `anchors.acceptance_entries`**）
- `.diorama/knowledge/facts/glossary.json`
- `.diorama/knowledge/facts/domain-model.md`
- `.diorama/knowledge/rules/index.md`
- `.diorama/knowledge/references/index.md`

## 边界守卫（Guardrails）

在 generate 开始前，Agent 必须检查：

1. 新增内容是否服务于当前 task
2. 是否优先复用了已有模型和已有代码
3. 新增领域概念是否与 glossary / domain-model / design.md 结论一致
4. 是否出现明显超出当前 task 范围的领域扩张——以 REQ-ID 为边界，超出 PRD 定义的需求范围即 scope creep
5. 生成的代码文件路径是否与 `task.json.anchors` 一致——若路径偏离 anchors 且无合理原因，应回退修正或说明

若检查失败，默认行为：停止继续扩张生成，回到 `plan` 或请求人工确认。

## 关于"新领域骨架代码"

在以下条件同时满足时，可以生成新的领域骨架代码：

1. 当前 task 的 tasks.md 已明确需要新增该骨架
2. 该骨架是实现当前 task 所必需的最小新增
3. 该骨架与 glossary.json / domain-model.md / design.md 结论一致

## Design Amendment

generate 阶段实现过程中，可能发现 design.md 需要轻量修改（如新增字段、补充场景细节）。为避免全量 rewind 到 specify 的代价，支持 design amendment 机制。

### 允许的修改范围

- 新增字段/属性到已有实体
- 补充场景的细节描述（Given/When/Then 内容细化）
- 修正 typo 或措辞不准确
- 追加遗漏的边界条件说明

### 禁止的修改（必须 rewind 到 specify）

- 删除 REQ 编号或整个功能需求
- 修改核心架构决策（如 SUT 边界、隔离策略）
- 变更接口契约的请求/响应结构
- 增加全新的功能方向（scope creep）

### 执行流程

1. Agent 判定修改属于"允许范围"
2. 执行 `design-amend --task <task-dir> --repo <repo> --summary "<修改摘要>"`
3. 脚本校验当前 phase == generate，校验 design.md 存在
4. Agent 编辑 design.md 内容
5. 脚本在 design.md 末尾追加 `## Amendments` 记录段
6. 脚本更新 task.json 增加 `amendments` 数组
7. 脚本 commit: `diorama(amend): <summary>`
8. 建议重新执行 conformance-check 验证修改后的一致性

## Gate 执行

每个子阶段完成后，Agent 应执行该子阶段的 Gate 命令并验证期望结果：

1. 复制 tasks.md 中的 Gate 命令（反引号内的完整命令）到终端执行
2. 检查输出是否符合期望结果描述
3. 若 Gate 失败，修正代码直到 Gate 通过，再继续下一子阶段
4. Gate 通过后勾选该子阶段的 checkbox 并更新 `**Status**`

示例执行流程：
```text
完成 Skeleton 代码 → 执行 Gate: mvn compile -pl <module> -q → exit 0 ✓ → 继续 DSL Test
完成 DSL Test 代码 → 执行 Gate: mvn test-compile ... → exit 0 ✓（类与方法已建立）&& mvn test ... → AssertionError ✓ (红灯；编译失败不算红灯) → 继续 Implementation
完成 Implementation → 执行 Gate: mvn test -pl <module> -q → exit 0 ✓ (绿灯) → 标记 Status [x] done
```

## Task Checkpoint

每个 task 的 Phase 3 Gate 通过且 Status 标记为 `[x] done` 后，Agent **必须（强制）** 执行 task-checkpoint：

> **强制规则**：generate phase-exit 时，脚本会校验 `phase.task_checkpoints` 非空。若没有任何 task-checkpoint 记录，phase-exit 将返回 `error: no_task_checkpoints` 并拒绝退出。

1. 确认 tasks.md 中该 task 的 `**Status**: [x] done` 已勾选
2. 执行 `task-checkpoint --task-id T1`（脚本子命令）
3. 脚本自动完成：
   - 校验 git 分支
   - 校验该 task 在 tasks.md 中标记为 done
   - 创建 task checkpoint commit + record commit
   - 更新 task.json 和 session
4. 继续下一个 task

### 含 Task Checkpoint 的完整执行流程

```text
完成 Skeleton → Gate 通过 → 继续 DSL Test
完成 DSL Test → Gate 通过 → 继续 Implementation
完成 Implementation → Gate 通过 → 标记 Status [x] done
→ 执行 task-checkpoint (T1)
→ 继续 T2 Skeleton
...
最后一个 task checkpoint 完成后 → 执行后置步骤
```

## Generate 后置步骤

> 注：由于每个 task 完成时已创建 task-checkpoint commit，generate phase-exit commit 可能仅包含 task.json/session 更新和 conformance 产物。

所有 tasks.md 子阶段完成后，Agent 应执行以下后置步骤：

1. **最终 Gate 验证**：执行最后一个 task 的 Phase 3 Gate 命令，确认全量测试通过
2. **Conformance-check**：对照 design.md 和 rules/index.md 验证代码合规性（详见下方 Conformance-check 段）
3. **TodoList 更新**：将子阶段 TodoList 替换为后置步骤列表：
   ```text
   最终 Gate 验证
   Conformance-check（design.md 对照 + rules 合规）
   phase-exit (generate → consolidate)
   consolidate (增量知识提炼)
   phase-exit (consolidate → done)
   merge-back (task 分支 → base_branch)
   ```
4. **phase-exit**：执行 `phase-exit --phase generate`，完成 generate checkpoint 和 handoff（`generate → consolidate`）
5. **Auto-advance to consolidate**：generate exit 后，自动进入 consolidate phase。consolidate 会处理时序图回写等知识更新任务，无需手动触发
6. **Human Confirmation**：consolidate 完成后，Agent **必须**向用户确认是否合并。确认内容包括：
   - 本次变更概要（涉及文件、新增/修改内容）
   - conformance-check 结果摘要
   - 是否存在遗留问题（NEEDS CLARIFICATION / CONDITIONAL 项）
   - 明确询问"是否执行 merge-back 将 task 分支合并回 base_branch？"
   - 用户确认后才执行 merge-back；用户拒绝时，保留当前状态不合并
7. **merge-back**：用户确认后执行 `merge-back`，将 task 分支合并回 base_branch

### Conformance-check

在 phase-exit 之前，Agent 必须执行 conformance-check，对照 design.md 验证代码实现的完整性和合规性。流程详见 `workflow-review.md`。

**执行步骤**：

1. 读取 design.md，提取所有 REQ-/BR-/AC- 编号和场景编号
2. 读取 tasks.md，提取每个任务与 REQ/BR 的追溯关系
3. 读取 task.json anchors，定位代码文件
4. 逐个 REQ/BR/AC 确认在 tasks.md 中有对应任务且代码有对应实现
5. 若 `.diorama/knowledge/rules/index.md` 存在，读取索引中指向的规则文件，逐条检查规则合规
6. 输出合规报告，含整体评估（PASS / CONDITIONAL / FAIL）

**结果处理**：

- **PASS**：所有 REQ 有追溯、所有规则合规 → 继续 phase-exit
- **CONDITIONAL**：所有 REQ 有追溯、但存在规则不合规项 → 提示不合规项，建议是否修正后继续 phase-exit
- **FAIL**：存在 REQ 无追溯 → **建议修正后重新执行 conformance-check**，再继续 phase-exit

conformance-check 结果不阻塞 phase-exit（仅提供事实供决策），但 FAIL 时 Agent 应优先修正后再推进。
