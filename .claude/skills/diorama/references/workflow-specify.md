# Specify: 意图挑战 + PRD 产出

## 职责

把用户的模糊意图挑战、补全为可落地的产品需求文档（PRD），同时锚定代码现实。

它应：
- 理解用户意图（可模糊、可矛盾、可残缺）
- 五维挑战：完整性、一致性、可行性、可测性、影响面
- 扫描代码入口点、识别 SUT 边界、确定依赖隔离策略
- 产出结构化 PRD，每个需求可追溯、可验证

它不应：
- 无追问地直接翻译意图
- 跳过代码扫描凭空设计实现上下文
- 发明用户未提及的需求方向

## Phase 协议

遵循 phase-protocol.md 通用 Entry/Exit 协议。

| 项 | 值 |
|----|-----|
| 前置输入 | proposal.md |
| 产物 | proposal.md（更新）, design.md |
| anchors | 不碰 |
| next | plan |

## 输入与输出

- 输入：`.diorama/tasks/<current_task>/proposal.md`
- 输出：`.diorama/tasks/<current_task>/proposal.md`（更新）, `.diorama/tasks/<current_task>/design.md`
- 模板：`.diorama/templates/design-template.md`
- 辅助知识：`.diorama/knowledge/facts/glossary.json`、`.diorama/knowledge/facts/domain-model.md`、`.diorama/knowledge/references/index.md`

## proposal.md 回写规则

proposal.md 在 specify 阶段不是只读输入，而是可更新的活文档。

五维挑战过程中对意图的追加和修正应回写到 proposal.md：

- **只追加**挑战后补全的信息（遗漏场景、边界条件、用户确认的补充）
- **只修正**经用户确认的矛盾点或错误理解
- **不覆盖**用户原始意图的表述，保留原始措辞
- **不删除**已有内容，只增补和修正

回写时机：每次与用户确认一个挑战点后，立即将确认结果追加/修正到 proposal.md 对应段落，而非等到 specify 结束时一次性回写。

### 回退到 specify 时

rewind 到 specify 时，proposal.md 已包含上一次挑战的沉淀。重新分析时应：
1. 重新读取 proposal.md（含上一次的追加和修正）
2. 基于最新理解重新挑战
3. 新的追加和修正继续回写到 proposal.md

## 五维挑战

### 1. 完整性
- happy path 覆盖了，异常路径呢？边界条件呢？
- 补全遗漏场景，标记为 `[NEEDS CLARIFICATION]`

### 2. 一致性
- 需求之间有矛盾吗？术语使用统一吗？
- 标记矛盾并要求用户决策

### 3. 可行性
- 技术上能实现吗？现有架构支持吗？
- 扫描代码后标记技术风险

### 4. 可测性
- 验收标准能被客观判定吗？
- 每条 REQ 必须有对应的 AC（验收标准）

### 5. 影响面
- 会影响哪些现有功能？需要回归什么？
- 从代码扫描结果推导影响范围

## 实时扫描原则

Agent 应根据当前 task 实时扫描代码，而不是依赖预先静态抽好的实现清单。

推荐扫描顺序：

1. 读取目标项目中的 `AGENTS.md`（如果存在）
2. 读取 `glossary.json`（来自 `.diorama/knowledge/facts/`）
3. 读取 `domain-model.md`（来自 `.diorama/knowledge/facts/`）
4. 读取 `proposal.md` 中的约束条件段落，在后续五维挑战中优先校验这些约束（如接口兼容性、性能要求、截止日期等）
5. 定位当前需求对应的入口代码（如 controller / handler / job / service）
5. 定位相关应用服务、聚合、仓储、外部依赖
6. 查找相关已有测试
7. 基于以上结果填充 design.md §7 实现上下文
8. 从 domain-model.md 复制相关场景时序图作为参考，生成本次变更链路时序图（§6）
9. 扫描接口代码（Controller/RPC/MQ），分析是否存在接口变更，填充接口契约（§8）

## design.md 格式规则

### 变更时序图（§6）

design.md §6 变更时序图服务于人类读者，帮助快速理解"本次变更改了什么链路"：

- **变更链路时序图**：基于代码扫描结果，生成本次变更涉及的调用路径时序图，对新增/修改步骤用 Mermaid `Note` 标注 `[NEW]`/`[MOD]`
- **参考时序图**：从 `domain-model.md` 中复制与本次变更相关的场景时序图，作为变更前对照
- 复制参考时序图时只复制相关场景，不要全量复制
- 如果 `domain-model.md` 尚无对应场景时序图，可省略参考部分，但变更链路时序图必须生成
- 参与者控制在 6 个以内，链路过长按边界拆分多张图

### 变更模型图（§6）

design.md §6 变更模型图用于可视化模型结构变更，与变更时序图互补：

- 必须同时提供**变更前模型**和**变更后模型**，便于对比
- 变更后模型使用 Mermaid `classDef` 高亮样式区分变更类型：
  - 新增实体 → `:::new`（绿色背景 `#d4edda`）
  - 修改实体 → `:::modified`（黄色背景 `#fff3cd`）
  - 删除实体 → `:::deleted`（红色背景 `#f8d7da`，虚线边框）
- 实体内部字段级标注：`[NEW]`（新增字段）、`[MOD: 说明]`（修改字段）；删除字段仅出现在变更前模型
- 只画变更涉及的实体及直接关联，不画全量领域模型
- 如果本次需求不涉及模型结构变更（纯行为/接口变更），填写"无模型变更"即可
- 实体字段只需列出变更相关字段 + 必要标识字段，不追求完整字段清单
- 关系线只画变更影响的关联，参考 domain-model.md 中的 classDiagram 约定

### 接口契约（§8）

design.md §8 接口契约用于分析本次需求是否涉及接口层面的变更：

- 必须扫描代码，确认是否存在 HTTP/RPC/MQ 消费等接口变更
- 如果涉及接口变更，逐条填写新增/修改/删除接口的契约详情
- 修改接口必须标注兼容性（向前兼容/破坏性变更）
- 如果本次需求不涉及任何接口变更，填写"无接口变更"即可
- 接口变更的识别范围包括但不限于：Controller 端点、RPC 服务、MQ 消息格式、对外 SDK

### 变更清单

design.md §7 变更清单使用**普通列表**（`- 条目`），不使用 checkbox（`- [ ] 条目`）。

checkbox 只用于 `tasks.md` 中跟踪 generate 子阶段进度。变更清单是设计文档中的描述性内容，不需要 checkbox 标记。

### 验证矩阵

design.md §10 验证矩阵的「本次覆盖」列：

- **specify 阶段**：全部填 `—`（待定），因为此时无法确定实际覆盖情况
- **generate 完成后**：才可将 `—` 替换为 `✅`（已覆盖）/ `❌ 需 QA`（需人工）/ `❌ 需 SRE`（需运维）

specify 阶段产出的验证矩阵只定义"验证什么"和"用什么手段"，不预设"是否已覆盖"。

## 规则

- 先挑战意图，再结构化——不是机械翻译
- 只问影响需求完整性的问题，不问实现细节
- 如果存在其他已完成 task，可参考 `.diorama/tasks/` 中的历史 `design.md`
- 允许最多 3 个 `[NEEDS CLARIFICATION]` 标记
- 超过 3 个应继续追问用户，不应提交 PRD

## 深度自适应

specify 的深度由 proposal.md 的质量决定：

- **一句话意图** → 多轮追问，深度挑战，逐项补全
- **详细意图** → 快速校验，填补缺漏，确认即可
- **半成品 PRD** → 结构审查，一致性检查，补齐编号

AI 读 scenario 就知道该深该浅，不需要用户提前声明。

## 最低完成判定

满足以下条件，specify 可视为已完成并进入 Exit：

- `proposal.md` 存在且非空（包含意图描述）
- `design.md` 存在且非空
- 至少包含一个 `REQ-` 编号的功能需求，或至少包含一个场景规格（`### S` 标题）

若文件存在但只是零散笔记或未形成结构化 PRD，应视为半成品，中断恢复时重做当前 phase。

## Exit 检查提醒

specify Exit 前应检查 design.md 以下段落，若为空或仅含占位符则发出软提醒（非阻断）：

- **§6 变更时序图**：若为空，提醒"变更时序图未填写，建议补充以帮助 plan 阶段理解变更链路"
- **§6 变更模型图**：若为空，提醒"变更模型图未填写，建议确认是否确无模型结构变更"
- **§8 接口契约**：若为空，提醒"接口契约未填写，建议确认是否确无接口变更"

软提醒不阻断 Exit 流程——若用户确认暂不填写，可继续 Exit。

## specify 完成后修改 design.md

specify 完成后，用户可能在对话中直接 @ design.md 并提出修改需求（如"加一个超时重试场景"）。Agent 应按以下流程处理：

### 流程

1. **识别修改意图**：用户在对话中对 design.md 提出修改请求
2. **增量修改 design.md**：基于用户意图做增量编辑，不全量重写
3. **自动 rewind**：若 `specify ∈ phase.completed`，执行 `rewind-exec --phase specify`（design.md 是后续产物的 source of truth，修改后 plan/generate 产物失效）
4. **重新 exit specify**：修改完成后执行 `phase-entry` + `phase-exit`，生成新的 specify checkpoint
5. **提示后续 phase 需重做**：告知用户"design.md 已修改，plan 和 generate 需重新执行"，建议运行 `/diorama` 继续

### 若 specify 未完成

若 `specify ∉ phase.completed`，无需 rewind，直接在当前 specify 中应用修改。

### working tree 脏时的处理

rewind 需要 working tree clean。若 working tree 脏，提示用户先提交或暂存更改，再进行修改。
