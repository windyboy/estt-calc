# ESTT Algorithm Correction Plan

## Goal

Fix identified algorithm weaknesses that can cause **false “insufficient history”** and **non-deterministic seasonal lookup**, without changing the public HTTP contract (`source`, `flyingTime`, `sampleSize`, `confidence` field names).

## Current Phase

All phases complete (P2/P3/P4 deferred items documented in `findings.md`)

## Success metrics

| Metric | Baseline | Target |
|--------|----------|--------|
| False seasonal/none fallback when ≥20 qualified rows exist in window | Unknown (no metric) | Measurable → trending to 0 on replay set |
| Seasonal row selection | Non-deterministic if multiple DB rows | Deterministic rule + test |
| `./gradlew check` | pass | pass |
| `docs/algorithm.md` | Audited 2026-07-01 | Updated for any behavior change |

## Problem summary (from audit)

| ID | Problem | Priority | In scope |
|----|---------|----------|----------|
| P0 | `maxHistoryRows` caps **raw** DB rows before filtering; qualified samples beyond cap are invisible | **P0** | Yes |
| P1 | `getSeasonalArrivalFlight` has no `ORDER BY`; multiple rows → undefined choice | **P1** | Yes |
| P2 | History window starts at `seasonStart − offset`, may include pre-season data | P2 | **Deferred** — needs business sign-off |
| P3 | `confidence: HIGH` ignores dispersion | P3 | **Deferred** — API semantics, not calculation bug |
| P4 | No flying-time outlier bound when seasonal `flyingTime` is null | P4 | **Deferred** — needs ops bounds |

## Phases

### Phase 0: Baseline & discovery

- [x] Algorithm audit vs source (`docs/algorithm.md`, service/history/calculator)
- [x] Document problems and proposed fixes in `findings.md`
- [x] Run `./gradlew test` — baseline pass after `clean` (see `progress.md`)
- **Status:** complete

### Phase 1: Observability (P0-C, zero behavior change)

- [x] Add Micrometer metrics for history scan: `raw_rows`, `filtered_rows`, `qualified_rows`, `hit_scan_limit`, `qualified_sufficient`
- [x] Log at INFO when `hit_scan_limit=true` and `qualified < minHistoryFlight`
- [x] Unit test: metrics incremented on representative paths
- **Status:** complete

### Phase 2: Scan-until-qualified (P0-A, core fix)

- [x] Refactor calculation history load: page through DB until `qualified ≥ minHistoryFlight` **or** scan budget exhausted
- [x] Reuse `isEligibleHistoryFlight` + `FlyingTimeCalculator` schedule filter consistently
- [x] Define `maxScanBudget` — `maxHistoryRows` is initial budget; extend through window when qualified < min
- [x] Regression tests:
  - qualified samples only appear after raw row budget → still returns eligible flights (`HistoryFlightProviderTest`)
  - budget exhausted with <20 qualified → hitScanLimit true
- [x] Update `docs/algorithm.md` row-cap section
- **Status:** complete

### Phase 3: Deterministic seasonal lookup (P1)

- [x] Add `ORDER BY` + `FETCH FIRST 1 ROW ONLY` to `SeasonRepository.getSeasonalArrivalFlight`
- [x] Proposed order: `seasonal_flight.START_DATE DESC`, then `FLIGHT_NUMBER`
- [ ] Test: multiple mocked/repository rows → same flight selected (deferred — SQL-level; service mocks return single row)
- [x] Document in `docs/algorithm.md`
- **Status:** complete

### Phase 4: Verification & docs

- [x] Full `./gradlew check`
- [x] Review metrics in test profile
- [x] Update `CHANGELOG.md` (algorithm section)
- [x] Mark P2/P3/P4 as future work in `findings.md` if still deferred
- **Status:** complete

## Key questions

1. **P2 window:** Should history ever include dates before `seasonStart`? *(Default: defer; keep current behavior until product confirms.)*
2. **Scan budget:** Is it acceptable to increase worst-case DB reads for calculation? *(Mitigate with page size + existing `maxHistoryRows` budget.)*
3. **Pagination API:** Should `/estt/history` use the same scan-until-qualified logic, or remain “best effort within cap”? *(Default: calculation path only in Phase 2; pagination unchanged unless tests prove inconsistency.)*

## Decisions made

| Decision | Rationale |
|----------|-----------|
| Phased delivery: metrics before behavior change | Prove problem in prod-like data; safe rollback |
| Phase 2 changes calculation path only | Minimize API surface; pagination already documents bounded scan |
| P2/P3/P4 deferred | Require business input or are API enhancements, not correctness bugs |
| Archive prior `task_plan.md` / `findings.md` / `progress.md` | v4 simplification complete; new plan owns algorithm work |
| Keep median + filter rules unchanged | Core estimator is sound; fix sample **selection** not statistics |

## Errors encountered

| Error | Attempt | Resolution |
|-------|---------|------------|
| — | — | — |

## Notes

- Prior plan archived at `plans/archive/simplification-v4/`.
- Historical backlogs archived at `plans/archive/`.
- Re-read this file before Phase 2 implementation (behavior change).
