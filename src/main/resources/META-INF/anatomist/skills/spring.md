# Spring decision guide

Use `context`, `deps-of`, and `used-by` for annotation-driven handlers, injection,
wiring, and definitions. Use `bean-config` when XML map keys, list order, nested
values, references, or constructor/property structure matters.

Read the selected query command's `--help`. Before trusting completeness, confirm
that the existing index profile includes the required classpath and Spring XML
coverage; preserve that profile through the incremental gate.

Injection and wiring facts show static configuration relationships. Profiles,
conditional beans, proxies, generated code, and runtime configuration can select a
different path, so use runtime evidence for claims about what actually ran.

MVC routes expand multiple class paths, method paths, and HTTP methods; route
identity includes the handler, so group by the human route label when comparing
duplicate endpoints. Injection facts include explicit injection and the common
single-constructor component convention, with qualifiers in edge metadata.
Page broad XML bean matches with `--limit`/`--offset` and narrow duplicates with
`--module`/`--scope`.
