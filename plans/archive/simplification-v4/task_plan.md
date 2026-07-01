# ESTT Kotlin Simplification Plan v4

## Goal

**Deeply simplify project code** while **keeping sufficient documentation** (README + `docs/` + domain comments in source).

## Success metrics

| Metric | Baseline (post-v3) | After v4 | Target | Met |
|--------|-------------------|----------|--------|-----|
| Production `.kt` files | 24 | **23** | ≤ 23 | yes |
| Main `src/main/kotlin` lines | 1,477 | **1,348** | −5% or more | yes (−8.7%) |
| `EsttService.kt` lines | 342 | **263** | ≤ 310 | yes |
| `./gradlew check` | pass | pass | pass | yes |

---

## Phase overview

| Phase | Status |
|-------|--------|
| Phase 0: Baseline | complete |
| Phase 1: Merge pagination scanner | complete |
| Phase 2: Slim EsttService | complete |
| Phase 3: Deduplicate EsttService tests | complete |
| Phase 4: Docs restructure | complete |
| Phase 5: Verify | complete |

---

## Deliverables

- Merged `HistoryPaginationScanner` → `HistoryFlightProvider.scanPaginatedHistory`
- `EsttService`: `catching` helper, inlined telemetry, removed `isOperationDayMatch` wrapper
- `EsttServiceTestSupport.kt` + refactored four service test classes; merged `EsttCalcTest` into `ApplicationTest`
- `docs/algorithm.md`, `docs/api.md`, `docs/code-map.md`; slim README with doc index

## Errors encountered

| Error | Attempt | Resolution |
|-------|---------|------------|
| Gradle config-cache lock from background build | 1 | Killed stuck daemon; reran with `--no-daemon` |
| Spotless formatting after test support | 1 | `./gradlew spotlessApply` |
