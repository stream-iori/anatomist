# /diorama review

调用 diorama skill，action: review。

对照 design.md 和 rules/index.md 验证 generate 产出的代码是否符合设计意图和项目规则。

适用于：

- generate 完成后，自动执行的 conformance-check 验证
- 代码修改后，手动触发重新验证合规性
- CI/CD 流程中的合规检查

review 不属于 task phase，不影响 task phase checkpoints。

review 输出合规报告，包含：
- REQ/BR/AC 追溯（design.md → tasks.md → 代码）
- Rules 规则合规检查
- 场景覆盖追溯
- 整体评估（PASS / CONDITIONAL / FAIL）
