# 扩展模型与 Lombok

> 状态：已交付。扩展 SPI、producer ownership、Spring XML、Lombok AST 签名补全和
> 结构化 Lombok 证据均在生产路径中；bytecode、delombok 与精确 Builder/Accessors
> 签名不进入当前 backlog。

## 结论

Agent 不需要先得到每个 Lombok 成员的精确模拟，才能做出可靠下一步。查询返回的
`lombok` JSON 已区分“已建模、部分建模、未建模”，因此能指导 Agent：使用已证实
的成员，或把未建模 API 当作待验证假设。

```text
Lombok 注解 / lombok.config
           │
           ▼
metadata.lombok
  ├─ modeled_capabilities   → 可作为结构事实使用
  ├─ partial_capabilities   → 需保留不确定性
  └─ unmodeled_capabilities → 可提出假设，不伪造成员
```

`@Builder` 与 `@Accessors` 因而不是功能缺口：前者被明确披露但不生成虚假 API，
后者使受影响 Getter/Setter 降为 partial，避免按默认命名猜测。

## 已交付

| 能力 | 行为 |
|---|---|
| 扩展 SPI | `AstModelExtension` 在核心提取前补模型；`JavaUnitAnalyzer` 和项目资源分析随后执行。 |
| 共享资源 | `ProjectResourceProvider` 发现一次资源，多个 `ProjectResourceAnalyzer` 共享读取。 |
| 事实所有权 | 每条结构事实有 `producer_id`；跨 producer 抢同一 node ID fail-fast。 |
| Lombok AST | `--lombok ast` 处理 Getter、Setter、构造器、Data、Value 和日志字段；默认 `off`。 |
| 查询证据 | `search`、`context`、`declarations-of` 返回 `metadata.lombok` 与 synthetic provenance。 |
| 增量安全 | Lombok 配置/扩展指纹变化触发安全全量重建；字段、注解删除会清理旧 synthetic 事实。 |
| 成本边界 | 不运行 Lombok、不读取项目 bytecode、不生成方法体、不引入反射扫描。 |

架构顺序：

```text
PreparedExtensions
  → JavaParser
  → AstModelExtension        (Lombok AST)
  → core extractors
  → JavaUnitAnalyzer         (Spring 注解等)
  → ProjectResourceAnalyzer  (Spring XML 等)
```

## Agent 使用规则

| `lombok` 状态 | Agent 行为 |
|---|---|
| `modeled_capabilities` | 可使用对应真实/ synthetic 声明和关系。 |
| `partial_capabilities` | 不应据此断言精确名称或完整调用关系。 |
| `unmodeled_capabilities` | 可将 API 存在作为 hypothesis，需源码、编译产物或用户确认。 |

实际生成成员仍以普通 declaration/node 和 `producer_id=lombok-ast` 为准；
`lombok` JSON 是能力与证据摘要，不是成员清单副本。

## 明确不做

- 不默认实现 `@Builder`、`@Accessors`、`@SuperBuilder` 的精确签名；
- 不实现 bytecode overlay 或 delombok 后端；
- 不隐式执行 Maven、Gradle 或 Lombok；
- 不为生成签名伪造方法体、CALLS、READS、WRITES 或 flow；
- 不引入外部 jar 动态插件系统。

这些能力只有在真实 Agent 任务证明现有结构化证据不足，并且收益大于外部构建、
新鲜度与 native-image 复杂度时，才以独立 RFC 重新评估。

## 验证

- `LombokAstModelExtensionTest`、`LombokCapabilitySummaryTest` 锁定规则、边界和排序；
- `LombokExtensionIT` 覆盖全量、增量、Data/Value、Builder/Accessors 降级与查询输出；
- `ExtensionLifecycleTest`、Spring XML、record 回归锁定 SPI 与 producer 语义；
- `just extension-e2e-jvm`、`just extension-e2e-native` 验证 JVM/native 一致性；
- `mvn verify` 覆盖完整回归和覆盖率门禁。
