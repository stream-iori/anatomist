# External fixtures (L3 smoke tests)

This directory holds **vendored** open-source projects we use as larger-scale
smoke targets for anatomist. They are added as git submodules so the repo
itself stays small and tests pin a specific revision.

## Apache Commons Lang 3.12.0 (JDK 8)

Used by `CommonsLangSmokeIT` to validate that anatomist can index a real-world
dependency-free JDK 8 library end-to-end without crashing, and to exercise the
indexer at scale (hundreds of types, thousands of methods).

### One-time setup

```bash
# Add as a submodule, pinned to the 3.12.0 release tag.
git submodule add https://github.com/apache/commons-lang.git \
    fixtures/external/commons-lang

# Apache release tags are prefixed with `rel/`. Pin to 3.12.0:
git -C fixtures/external/commons-lang fetch --tags
git -C fixtures/external/commons-lang checkout rel/commons-lang-3.12.0

# Record the pinned commit in the parent repo.
git add fixtures/external/commons-lang .gitmodules
```

If the `rel/commons-lang-3.12.0` tag is not present locally, list available tags
with `git -C fixtures/external/commons-lang tag | grep 3.12` and pick the
closest match.

### Fresh checkout

```bash
git submodule update --init --recursive
```

## Running external-fixture tests

External tests carry the JUnit tag `external` (set via `@Tag("external")`).
They run by default whenever you invoke them explicitly:

```bash
mvn test -Dtest=CommonsLangSmokeIT
```

If the submodule directory is missing, each test self-skips via
`assumeTrue(...)` rather than failing, and the Surefire report shows
`Tests run: N, Skipped: N` — visible enough to notice in CI.
