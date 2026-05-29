# Domain Model

`.diorama/knowledge/facts/domain-model.md` — anatomist 的领域模型快照。每次 consolidate 增量更新。

## 核心实体

```mermaid
classDiagram
    direction LR

    class Node {
      +String id "保留大小写 FQN"
      +String label "简名"
      +String kind "CLASS/INTERFACE/ENUM/METHOD/FIELD/..."
      +String qualifiedName
      +String pkg
      +String sourceFile
      +String sourceLocation "L<line>"
      +String module
      +String scope "MAIN/TEST"
      +String javadoc
      +String metadata "JSON"
    }

    class Edge {
      +String sourceId
      +String targetId "项目内 (NULL when external)"
      +String externalTargetFqn "外部依赖 FQN (NULL when internal)"
      +String relation
      +String callKind
      +String confidence "EXTRACTED"
      +String context "REFERENCES 的子类"
      +boolean isExternal
    }

    class Annotation {
      +String nodeId
      +String annotationFqn
      +String attributes "JSON"
    }

    class ExtractionResult {
      +List~Node~ nodes
      +List~Edge~ edges
      +List~Annotation~ annotations
    }

    ExtractionResult o-- Node
    ExtractionResult o-- Edge
    ExtractionResult o-- Annotation
    Edge --> Node : source_id
    Edge --> Node : target_id
    Annotation --> Node : node_id
```

## 索引数据流

```mermaid
sequenceDiagram
    autonumber
    participant CLI as IndexCommand
    participant CD as ClasspathDetector
    participant PS as ProjectScanner
    participant JF as JdtParserFactory
    participant TE as TypeExtractor
    participant ME as MethodExtractor
    participant SS as SqliteStore

    CLI->>CD: detectSourcePaths(root) / detect(root)
    CD-->>CLI: sourcePaths, classpath (空时降级 WARN)
    CLI->>PS: scan(sourcePaths)
    PS-->>CLI: List<Path> javaFiles
    CLI->>JF: parseAll(files, requestor)
    JF->>JF: ASTParser.createASTs (共享 binding 上下文)
    loop 每个 CompilationUnit
        JF->>TE: extract(unit, result)
        TE-->>JF: nodes (CLASS/INTERFACE/ENUM)
        JF->>ME: extract(unit, result)
        ME-->>JF: METHOD nodes + CONTAINS edges
    end
    CLI->>SS: initSchema + write(result)
    SS-->>CLI: ok
```

## Phase 1 MVP 实际覆盖

| Extractor | 状态 | 说明 |
|-----------|------|------|
| TypeExtractor | ✅ 实现 | CLASS / INTERFACE / ENUM(含 nested,跳过 anonymous/local) |
| MethodExtractor | ✅ 实现 | METHOD + CONTAINS Edge;跳过 anonymous/local 内方法 |
| FieldExtractor | ⏸ 骨架 | 下个 task |
| CallGraphExtractor | ⏸ 骨架 | 下个 task |
| HierarchyExtractor | ⏸ 骨架 | 下个 task |
| ReferenceExtractor | ⏸ 骨架 | 下个 task |
| FieldAccessExtractor | ⏸ 骨架 | 下个 task |
| AnnotationExtractor | ⏸ 骨架 | 下个 task |

## 验证数据

Fixture `fixtures/mini-spring-shop/` 索引产出:
- 15 types (CLASS + INTERFACE + ENUM)
- 46 methods
- 46 CONTAINS edges
- 0 ANNOTATED_WITH(本期不提取)
- 0 CALLS / INHERITS / IMPLEMENTS / REFERENCES(本期不提取)
