# ESTT Kotlin Simplification Plan v3

## Goal

Reduce **total code volume and cognitive load** without changing external behavior.

This plan replaces the archived second refactor pass. Success is measured by **fewer lines and/or fewer files**, not by new helpers or phases of extraction.

## Success metrics

| Metric | Baseline (2026-06-29) | Target |
|--------|------------------------|--------|
| `EsttService.kt` lines | 436 | **≤ 340** |
| Main `src/main/kotlin` lines (excl. generated) | ~1,800 | **−10% or more** |
| Production Kotlin files | 25 | **≤ 24** (merge, do not split) |
| `./gradlew check` | pass | pass |

## Principles

1. **Delete > merge > inline > keep** — if a change does not remove lines or files, skip it.
2. **No new production files** unless two or more existing files are removed or merged.
3. **No new private helpers** unless they replace duplicated blocks of **≥ 15 lines** each in **≥ 2 places**.
4. **Comments:** keep only non-obvious domain rules; remove bilingual blocks that restate the code.
5. **Behavior frozen:** public API, HTTP routes, response fields, error messages, metrics tags, SQL, cache names unchanged.
6. **Tests stay green** after every phase; run focused tests for touched packages before `./gradlew check`.

## Do not repeat (lessons from refactor pass)

- Multi-phase “characterization → extract → split” without net line reduction
- Bilingual KDoc on every method
- Thin one-line wrappers (`databaseErrorResponse`, branch `historyResult`, etc.)
- Marking phases complete when only code was moved

## Baseline hotspots

| File | Lines | Issue |
|------|-------|-------|
| `EsttService.kt` | 436 | Main target: duplicate history paths, verbose comments, ceremony |
| `EsttController.kt` | 243 | Mostly OpenAPI annotations; low simplification ROI |
| `HistoryFlightProvider.kt` | 168 | Already reasonable; keep split with scanner |
| `HistoryPaginationScanner.kt` | 79 | Keep — real separation |
| `FlyingTimeCalculator.kt` | 128 | Already rolled back; leave as-is |
| `EsttInputValidator.kt` | 21 | Candidate to merge back and delete file |

## Out of scope (needs product/API decision)

- Removing deprecated `FlyingTimeResponse.history` / `.seasonal` booleans
- Dropping OpenAPI annotation blocks from controller
- Enabling skipped integration tests (`xdescribe`)

---

## Phase overview

| Phase | Status | Risk | Expected outcome |
|-------|--------|------|------------------|
| Phase 0: Baseline and scope lock | complete | Low | Metrics recorded; v3 plan approved |
| Phase 1: Trim comment noise | complete | Low | −80~120 lines across service/models/repos |
| Phase 2: Deduplicate EsttService history flow | complete | Medium | Remove redundant history fetch path |
| Phase 3: Slim EsttService ceremony | complete | Low-Medium | Shorter init/logging/Result wrappers |
| Phase 4: Merge EsttInputValidator | complete | Low | Delete 1 file; validation stays identical |
| Phase 5: Verify and measure | complete | Low | `./gradlew check`; record before/after counts |

---

### Phase 0: Baseline and scope lock

**Status:** complete

**Goal**

- Record baseline line counts and lock v3 simplification scope.
- Archive previous refactor plan mentally; do not resume Phases 8–11 style extraction.

**Baseline recorded**

- HEAD: `4bc60cb Simplify by inlining marginal refactor helpers.`
- `EsttService.kt`: 436 lines
- Total main Kotlin: ~1,800 lines (25 files)
- Tests: 15 suites; `./gradlew check` passing at last commit

**Verification**

```bash
wc -l src/main/kotlin/com/gzzn/airport/service/EsttService.kt
find src/main/kotlin -name '*.kt' | wc -l
./gradlew check
```

---

### Phase 1: Trim comment noise

**Status:** complete

**Goal**

- Remove comments that restate code; keep only domain-critical notes.

**Keep comments on (short, optional bilingual one-liner max)**

- `yyMMdd` strict parsing and `2000..2099` mapping
- Operation-day digit-wise matching (not substring)
- History window excludes target date
- Flying-time tolerance strict `<`; schedule deviation inclusive `<=`
- Median integer rule for even counts
- SQL `INSTR` as prefilter only

**Trim heavily**

- `EsttService.kt` class-level and method-level bilingual KDoc blocks
- `HistoryFlightProvider.kt`, repositories, model field essays (`FlyingTimeResponse` property docs)
- Obvious inline comments (`// Validate pagination`, MDC step-by-step narration)

**Files**

- `EsttService.kt`
- `HistoryFlightProvider.kt`
- `HistoryFlightRepository.kt`, `SeasonRepository.kt`
- `FlyingTimeResponse.kt`, `PaginatedHistoryResponse.kt`, `OperationDays.kt`
- `EsttCalculationConfig.kt`

**Forbidden**

- Logic changes, signature changes, message changes

**Verification**

```bash
git diff --stat
./gradlew spotlessCheck
./gradlew test --tests com.gzzn.airport.service.EsttServiceTest
```

**Target:** −80~120 lines

---

### Phase 2: Deduplicate EsttService history flow

**Status:** complete

**Goal**

- Remove parallel history-loading paths that do the same work with different wrappers.

**Current duplication**

- `cachedHistoryFlights` → resolves seasonal, calls `historyFlightProvider.getHistoryFlights`
- `getHistoryFlightsWithSeasonFlight` → same provider call when seasonal is non-null, extra `runCatching` + warn path
- `calculateWithSeasonalFlight` already holds non-null `SeasonalFlight` but goes through the second path

**Planned change**

- In `calculateWithSeasonalFlight`, call `historyFlightProvider.getHistoryFlights(seasonalFlight, flightDate)` directly inside existing `Result` chain (preserve failure propagation and messages).
- Delete `getHistoryFlightsWithSeasonFlight` if no other callers remain.
- Do **not** change cache keys or public method behavior for `getHistoryFlights` / `cachedHistoryFlights`.

**Files**

- `EsttService.kt`
- `EsttServiceTest.kt`, `EsttServiceErrorTest.kt` (if needed)

**Risk**

- Medium: calculation pipeline error handling must stay identical.

**Verification**

```bash
./gradlew test --tests com.gzzn.airport.service.EsttServiceTest --tests com.gzzn.airport.service.EsttServiceErrorTest --tests com.gzzn.airport.service.EsttServiceValidationAndMatchingTest
```

**Target:** −25~40 lines in `EsttService.kt`

---

### Phase 3: Slim EsttService ceremony

**Status:** complete

**Goal**

- Reduce init/log/Result boilerplate without new abstractions.

**Candidates**

- Replace 7-line `init` config dump with **one** structured info log (or remove if redundant with Micronaut startup logs).
- Collapse repeated `runCatching { ... }.onFailure { log.error("...", e) }` only if a **single** private inline helper saves net lines (must be ≥ 15 lines saved total; otherwise skip).
- Inline `getOperationDay` if only used once.
- Merge `buildCalculatedResponse` + `buildNoEstimateResponse` only if net shorter (do not split further).

**Forbidden**

- New files, new public methods, changed log message text

**Verification**

```bash
./gradlew test --tests com.gzzn.airport.service.EsttServiceTest
./gradlew check
```

**Target:** −20~35 lines in `EsttService.kt`

---

### Phase 4: Merge EsttInputValidator

**Status:** complete

**Goal**

- Delete `EsttInputValidator.kt` by moving its 4 `require` checks back into `EsttService` as a private function.

**Why**

- 21-line file for one call site adds navigation cost without meaningful separation.

**Files**

- Delete: `EsttInputValidator.kt`
- Edit: `EsttService.kt`

**Verification**

```bash
./gradlew test --tests com.gzzn.airport.service.EsttServiceValidationAndMatchingTest
./gradlew check
```

**Target:** −1 file; net lines ≈ unchanged or slightly lower

---

### Phase 5: Verify and measure

**Status:** complete

**Goal**

- Confirm success metrics; update planning files; stop.

**Verification**

```bash
git diff --check
./gradlew check
wc -l src/main/kotlin/com/gzzn/airport/service/EsttService.kt
find src/main/kotlin -name '*.kt' | wc -l
```

**Deliverables**

- Update `findings.md` with before/after counts
- Update `progress.md` with phase log
- Mark all v3 phases complete in this file

---

## Errors encountered

| Error | Attempt | Resolution |
|-------|---------|------------|
| Prior refactor plan grew code ~825→1115 lines | v2 pass | Archived; v3 plan uses deletion metrics |
| Session catchup home path missing | 1 | Use `.codex/skills/planning-with-files/scripts/session-catchup.py` |

## Current stop point

**All v3 phases complete.** `./gradlew check` passed 2026-07-01.

## Final metrics (v3)

| Metric | Baseline | After | Target | Met |
|--------|----------|-------|--------|-----|
| `EsttService.kt` lines | 436 | 342 | ≤ 340 | ~yes (−2) |
| Main `src/main/kotlin` lines | ~1,800 | 1,477 | −10% or more | yes (−18%) |
| Production `.kt` files | 25 | 24 | ≤ 24 | yes |
| `./gradlew check` | pass | pass | pass | yes |

Phase 1 note: user chose compromise — short bilingual comments on domain rules only; model field KDoc not restored.
