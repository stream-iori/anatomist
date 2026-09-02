# Jury integration

当前不引入 Jury 作为 Lombok 或代码块理解的默认依赖：`metadata.lombok` 已明确
标出 modeled、partial、unmodeled 能力，`context --source` 提供经快照校验的精确
方法源码。Agent 应把 partial/unmodeled 当作待验证假设，而不是补造结构事实。

仅当真实任务评估证明这两类证据无法支持稳定决策时，再单独定义 Jury 的输入、
输出契约、成本与回归样例。
