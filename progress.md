# Progress Log: ESTT Algorithm Correction

## 2026-07-01 — Plan reset

### Session start

- User requested `/planning-with-files`: archive old plan, start new plan targeting algorithm issues from audit.
- Ran session catchup (no unsynced output).
- `git diff --stat`: uncommitted work from simplification v4 still in working tree (not part of this plan's baseline commit).

### Planning files

| Action | Path |
|--------|------|
| Archived | `plans/archive/simplification-v4/{task_plan,findings,progress}.md` |
| Archived | `plans/archive/2026-06-29-*.md` |
| Created | Root `task_plan.md` — algorithm correction phases 0–4 |
| Created | Root `findings.md` — P0–P4 analysis |
| Created | Root `progress.md` — this file |

### Audit recap (completed in prior conversation)

- `docs/algorithm.md` rewritten and aligned with source.
- Identified P0 raw-row cap and P1 seasonal non-determinism as actionable fixes.
- P2/P3/P4 deferred pending business input.

### Next actions

1. Phase 0: `./gradlew test` baseline
2. Phase 1: history scan metrics (no behavior change)
3. Phase 2: scan-until-qualified for calculation path
4. Phase 3: seasonal SQL ordering

### Test results

| Run | Command | Result |
|-----|---------|--------|
| 1 | `zsh -lc './gradlew test'` | FAILED — KSP stale `build/generated/ksp/test/resources/dummy` |
| 2 | `zsh -lc './gradlew clean test'` | **BUILD SUCCESSFUL** (37s) |

### Errors

| Error | Attempt | Resolution |
|-------|---------|------------|
| — | — | — |

## 2026-07-01 — Phase 0 & 1

### Phase 0 complete

- Documented call graph in `findings.md` (calculate uses single-fetch `getHistoryFlights`, not paged scan).
- `zsh -lc './gradlew test'` — BUILD SUCCESSFUL.

### Phase 1 complete (observability)

- `HistoryFlightScan` data class; `getHistoryFlights` returns scan metadata.
- Metrics: `estt.history.calc.scan.raw_rows`, `.filtered_rows`, `.qualified_rows` (summaries); `estt.history.calc.scan.calls` with `hit_scan_limit` / `qualified_sufficient` tags.
- INFO log in `EsttService` when cap hit with insufficient qualified samples.
- Tests: `HistoryFlightProviderTest`, `FlyingTimeCalculatorTest`.

### Phase 2 complete — scan-until-qualified

- `HistoryFlightProvider.scanCalculationHistory`: pages raw rows; extends beyond `maxHistoryRows` when schedule-qualified count < `minHistoryFlight`.
- Injects `FlyingTimeCalculator.filterByScheduleDeviation` for stop condition.
- `HistoryFlightScan.extendedBeyondBudget` + metric tag.
- Tests updated to `getArrivalFlightPage`; new regression tests in `HistoryFlightProviderTest`.

### Phase 3 complete — seasonal ORDER BY

- `SeasonRepository.getSeasonalArrivalFlight`: `ORDER BY START_DATE DESC, FLIGHT_NUMBER FETCH FIRST 1 ROW ONLY`.

### Phase 4 complete — docs bilingual

- `docs/algorithm.md`, `docs/api.md`, `docs/code-map.md` rewritten with 中文 + English sections.
- `docs/development.md` doc index updated; `CHANGELOG.md` bilingual entry.
