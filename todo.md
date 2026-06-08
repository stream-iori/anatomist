# TODO

## 固定业务语义标注体系（待讨论）

为 `semantic_annotations` 定义固定 category 枚举 + 横切关注点 tags，让 Agent 标注结果稳定且可程序化消费，并用于 `--blocks` 切块增强。

### 架构角色 Category（互斥，11 个）

| Category | 含义 |
|----------|------|
| `ENTRY_POINT` | 系统入口：HTTP / RPC / MQ consumer / CLI |
| `ORCHESTRATOR` | 编排/协调：串联多个服务调用，自身无业务逻辑 |
| `DOMAIN_LOGIC` | 核心业务规则：计算、状态转换、约束校验 |
| `DATA_ACCESS` | 持久化读写 |
| `INTEGRATION` | 外部系统调用：支付、短信、第三方 API |
| `TRANSFORMER` | 数据转换/映射：DTO↔Entity |
| `VALIDATOR` | 输入/业务规则校验 |
| `ERROR_HANDLER` | 异常处理/降级/补偿 |
| `EVENT_PRODUCER` | 发布事件/消息 |
| `EVENT_CONSUMER` | 消费事件/消息 |
| `INFRASTRUCTURE` | 基础设施：配置、AOP、过滤器、拦截器 |

### 横切关注点 Tags（可叠加）

| Tag | 含义 | 来源 |
|-----|------|------|
| `TX` | 事务边界内 | @Transactional（自动推断） |
| `ASYNC` | 异步执行 | @Async（自动推断） |
| `SECURED` | 有权限控制 | @PreAuthorize, @Secured（自动推断） |
| `CACHED` | 有缓存 | @Cacheable（自动推断） |
| `IDEMPOTENT` | 幂等设计 | Agent 判断 |
| `COMPENSABLE` | 有补偿/回滚逻辑 | Agent 判断 |

### 实现要点

1. 固定 category 枚举，`annotate` 命令校验输入
2. `semantic_annotations` 表新增 tags 字段（或复用 `domain_context` 存 JSON 数组）
3. TX/ASYNC/SECURED/CACHED 从 `annotations` 表自动推断，不需要 Agent
4. `CallChainSlicer` 读 `semantic_annotations`，用 category 替代 inferRole，用 tags 做 block metadata
5. 提供 Agent prompt 模板，列出 11 个 category 定义和判断标准
