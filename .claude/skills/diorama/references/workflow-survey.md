# Survey: 领域知识冷启动

## 职责

Survey 是项目的冷启动 action，抽取**稳定、跨 task 可复用**的领域知识。

它应：
- 识别核心领域概念与术语
- 抽取领域模型的核心实体与关系
- 为后续 specify 阶段提供术语统一基础

它不应：
- 抽取 task-specific 的实现细节（入口代码、依赖边界等）
- 生成全量 controller/service/repository 清单
- 替代 specify 阶段的实时代码扫描

## 触发条件

当满足以下**任一**条件时，建议触发 survey：

- `.diorama/knowledge/facts/glossary.json` 不存在
- `.diorama/knowledge/facts/domain-model.md` 不存在
Inception 后自动检查并建议，由用户决定是否执行。

显式调用 `/diorama survey` 可随时触发。

## 输入

- 目标项目源代码
- 目标项目中的 `AGENTS.md`（如果存在）
- 已有的 `.diorama/knowledge/` 内容（增量更新时）

## 输出

- `.diorama/knowledge/facts/glossary.json`
- `.diorama/knowledge/facts/domain-model.md`
- `.diorama/knowledge/facts/tech-context.md`

### glossary.json 结构

```json
{
  "terms": [
    {
      "name": "订单",
      "english": "Order",
      "definition": "客户提交的购买请求",
      "aliases": ["OrderRequest", "PurchaseOrder"]
    }
  ]
}
```

### domain-model.md 要素

必须覆盖：

1. **核心实体**：名称、职责、生命周期
2. **实体关系**：关联、组合、依赖
3. **关键业务规则**：不变式、状态转换约束
4. **领域事件**：重要业务事件
5. **核心流程图**：使用 Mermaid 类图（`classDiagram`）或时序图（`sequenceDiagram`）可视化核心领域关系与关键业务流程

Mermaid 图要求：
- 类图应覆盖核心实体及其关联关系（1对多、组合、依赖），仅保留一张
- 时序图必须按场景拆分为多张，**禁止**生成单张巨型时序图
- 每张时序图聚焦一条业务链路（如：下单流程、支付回调、库存扣减），以场景命名（`### 场景名`）
- 先分析项目代码整体结构，识别出核心业务场景，再逐场景生成时序图
- 时序图参与者控制在 6 个以内，链路过长时按边界拆分
- 图应简洁，聚焦核心领域，不追求覆盖所有实现细节

不需要覆盖：

- 每个 entity 的完整字段清单
- 基础设施实现细节
- task-specific 的调用链
- **Technology Stack**（技术栈、框架版本等不属于领域模型，禁止生成）

## 扫描策略

推荐扫描顺序：

1. 读取目标项目中的 `AGENTS.md`（如果存在）
2. **分析项目代码整体结构**：识别模块/包/分层布局，梳理核心业务场景清单（而非逐文件阅读）
3. 定位核心领域模型包（如 `domain/`, `model/`, `entity/`）
4. 读取核心实体类，提取概念与关系
5. 检查枚举类（通常对应业务状态）
6. 检查异常类（通常对应业务规则）
7. 读取已有测试的业务描述部分
8. 基于步骤 2 的场景清单，逐场景扫描入口→服务→依赖链路，生成对应的时序图
9. 检测项目构建文件（`pom.xml`、`build.gradle`、`package.json` 等），提取技术上下文写入 `tech-context.md`

## 与 specify 实时扫描的边界

| 维度 | survey | specify |
|------|--------|---------|
| 范围 | 全项目领域模型 | 当前 task 相关代码 |
| 粒度 | 概念级 | 实现级 |
| 时效 | 慢变，一次抽取多次复用 | 实时，每次 task 重新扫描 |
| 产出 | glossary + domain-model + tech-context | design.md |

## Git 语义

Survey 不属于 task phase，不写入 task checkpoints。

建议在 survey 更新知识文件后形成独立 knowledge commit：

```text
diorama(survey): refresh project knowledge
```

## 增量更新

当 glossary / domain-model 已存在时：

- 新术语：追加，不覆盖已有
- 已有术语：只更新 definition，不删除
- 废弃术语：不主动删除，需人工确认

### 衰减检查

survey 重新执行时，应对已有知识文件执行衰减检查：

1. **glossary 衰减**：遍历 glossary.json 中每个术语，检查其 `english` 名或 `aliases` 是否仍在代码中出现。不存在的术语标记 `stale: true`，同时保留原始内容不删除
2. **domain-model 衰减**：遍历 domain-model.md 中的核心实体，检查对应的类/接口是否仍在代码中存在。不存在的实体在描述后追加 `[STALE: 代码中未找到对应类]`
3. **衰减报告**：survey 完成后，若发现 stale 条目，输出衰减报告提醒用户确认是否清理

## 最低完成判定

满足以下条件，survey 可视为已完成：

- `glossary.json` 存在且非空，包含至少 1 个 term
- `domain-model.md` 存在且非空，包含至少 1 个核心实体描述
- `domain-model.md` 包含至少 1 个 Mermaid 图（` ```mermaid ` 代码块）
- `tech-context.md` 存在且非空（至少包含 Java 版本或构建工具信息）
