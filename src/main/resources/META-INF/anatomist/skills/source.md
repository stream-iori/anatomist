# Explain source behavior

| Question | First evidence | Continue when |
|---|---|---|
| What does one method do? | resolve exact callable → source | A called method or shared state is needed to explain the behavior. |
| Why this branch or loop? | resolve exact callable → source | Enumerating indexed calls/reads/writes in a context is useful: regions → sites-in. |
| Where does a value come from? | resolve exact callable → source | Cross a method boundary with calls/trace, or a field boundary with accesses. |
| Which calls/reads/writes occur in a context? | resolve callable → regions --kind all → sites-in | Read the owner method to interpret the condition and surrounding code. |
| How does state evolve? | Select relevant methods → source | Fields, constructors or initializers span the lifecycle: inspect the owning type. |

Read the required source range, following `source --offset` when truncated. For
whole-declaration conclusions, cover every page with stable identity and increasing
offsets; parameter units and limits are in `source --help`.

Entity/declaration input selects a declaration. Call/access/reference sites and
dispatch targets select their recorded source range; they do not automatically read
a target method body. To read that body, resolve its exact callable separately.

Regions are contexts observed on indexed call/read/write sites, not a complete list
of syntax branches. Sites-in matches the recorded context; nested contexts may be
separate. Empty region/site results do not prove a method has no branches.

There is no separate data-flow graph. Value, guard and exception explanations come
from the selected source. Static paths do not establish runtime values or path
feasibility. Lombok metadata establishes modeled capabilities only; partial or
unmodeled behavior needs source/build evidence.
