# Tasks: [task name]

**PRD**: [design.md](./design.md)
**Branch**: [task/xxx]

> generate 阶段中断恢复时，CodingAgent 应从第一个未完成的 `Status` 或未勾选项继续。

<!--
  Gate 命令规范：
  - Gate 必须是可直接执行的完整命令（包含模块路径、测试类名等）
  - 格式：**Gate**: `<完整可执行命令>` — <期望结果>
  - Agent 在 generate 阶段会直接执行 Gate 命令验证，命令必须可复制粘贴到终端执行
-->

## 技术评估

- 开发入口: [从 design.md §6 获得的入口代码路径，如 src/main/java/com/example/PlanService.java]
- 验收入口: [从 design.md §8 获得的测试代码路径，如 src/test/java/com/example/PlanFlowTest.java]
- SUT 边界: [从 design.md §6 获得的被测系统边界]
- 锚点说明: [为什么这些入口足以指导开发与验收]

## 任务清单

### T1: <任务摘要> [REQ-001, BR-001]

**Status**: [ ] done

#### Phase 1: Skeleton

- [ ] [类名] — [类型] — [说明]

**Gate**: `mvn compile -pl <module> -q` — exit 0

#### Phase 2: DSL Test

- [ ] [测试方法] — 场景 [S1] — 核心断言: [...]

**Gate**: `mvn test-compile -pl <module> -q && mvn test -pl <module> -Dtest=<TestClass>#<method> -q` — ① test-compile exit 0（类与方法已建立）② test fails with AssertionError（红灯；编译失败不算红灯）

#### Phase 3: Implementation

- [ ] [实现要点]

**Gate**: `mvn test -pl <module> -q` — exit 0, all tests green

---

### T2: <任务摘要> [REQ-002]

**Status**: [ ] done

#### Phase 1: Skeleton

- [ ] [类名] — [类型] — [说明]

**Gate**: `mvn compile -pl <module> -q` — exit 0

#### Phase 2: DSL Test

- [ ] [测试方法] — 场景 [S2] — 核心断言: [...]

**Gate**: `mvn test-compile -pl <module> -q && mvn test -pl <module> -Dtest=<TestClass>#<method> -q` — ① test-compile exit 0（类与方法已建立）② test fails with AssertionError（红灯；编译失败不算红灯）

#### Phase 3: Implementation

- [ ] [实现要点]

**Gate**: `mvn test -pl <module> -q` — exit 0, all tests green

---

[按需添加更多任务]
