# Findings: ESTT Algorithm Correction

**Date:** 2026-07-01  
**Context:** Algorithm audit (`docs/algorithm.md` vs `EsttService`, `HistoryFlightProvider`, `FlyingTimeCalculator`, repositories).  
**Prior work archived:** `plans/archive/simplification-v4/`

---

## What is already correct (do not change)

- Integer **median** over all qualified samples (not mean of top N).
- **Asymmetric schedule deviation:** early arrivals always kept; late beyond `maxScheduleDeviation` rejected (inclusive at threshold).
- **Dual tolerance:** schedule deviation in calculator; flying-time deviation vs seasonal in history provider (strict `<`).
- **Operation day:** digit-wise `1..7` matching; service revalidates after SQL `INSTR` prefilter.
- **Target operation date excluded** from history window (`endDate = flightDate − 1`).
- **Fallback chain:** HISTORY → SEASONAL (positive `flyingTime`) → NONE.

---

## P0 — Raw-row cap before filtering (critical)

### Observation

```text
HistoryFlightRepository.getArrivalFlight
  ORDER BY flight_date DESC
  FETCH FIRST maxHistoryRows ROWS ONLY   -- default 300 raw rows

→ HistoryFlightProvider.isEligibleHistoryFlight (filter)
→ FlyingTimeCalculator.getQualifiedHistoryFlights (schedule filter)
→ if qualified.size >= minHistoryFlight → median
```

### Failure mode

If the newest 300 raw rows have a **high filter rejection rate**, older qualified rows in the same date window are never fetched. Service returns `SEASONAL` or `NONE` despite ≥20 qualified flights existing in the window.

### Evidence in code

- `HistoryFlightProvider.getHistoryFlights` — single `getArrivalFlight` call with `config.maxHistoryRows`
- `HistoryFlightProvider.scanPaginatedHistory` — already pages raw batches for `/estt/history`; calculation path does not use this loop

### Proposed fix (Phase 2)

Page raw rows (chunk size `maxOf(limit, 100)` pattern already in `scanPaginatedHistory`) until:

1. `qualifiedCount >= minHistoryFlight`, **or**
2. `rawScanned >= maxHistoryRows` (scan budget), **or**
3. DB returns no more rows

Apply schedule-deviation filter when counting qualified samples for the stop condition.

### Risk

More DB round-trips on calculation for flights that need deep scans. Mitigation: keep `maxHistoryRows` as scan budget; Phase 1 metrics expose hit rate.

---

## P1 — Non-deterministic seasonal flight row

### Observation

`SeasonRepository.getSeasonalArrivalFlight` — no `ORDER BY`. Micronaut Data returns one row if multiple match `INSTR` + active season.

### Failure mode

Wrong `flyingTime` anchor for tolerance filter; wrong `operationDays` / season bounds if multiple valid rows exist.

### Proposed fix (Phase 3)

```sql
ORDER BY seasonal_flight.START_DATE DESC, seasonal_flight.FLIGHT_NUMBER
FETCH FIRST 1 ROW ONLY
```

Service-layer `OperationDays.matches` + season bounds remain the authority.

---

## P2 — History window may precede season start (deferred)

### Observation

`startDate = seasonStart.minusDays(historyStartOffsetDays)` — can extend before `seasonStart`.

### Trade-off

- **Pro keep:** More samples early in season.
- **Pro change:** `startDate = max(seasonStart, flightDate - lookback)` — avoids cross-season contamination.

**Status:** Deferred pending product/ops confirmation.

---

## P3 — `confidence: HIGH` semantics (deferred)

`HIGH` only means `source == HISTORY` and `qualified >= minHistoryFlight`. No dispersion or seasonal delta check.

**Status:** API enhancement; out of scope unless user requests response shape change.

---

## P4 — No duration outlier filter when seasonal `flyingTime` is null (deferred)

When `flyingTime == null` but ≥20 qualified samples exist, tolerance filter is skipped; median uses all schedule-acceptable rows.

**Status:** Optional hard bounds (e.g. 30–600 min) need ops input.

---

## Phase 0 call graph (confirmed 2026-07-01)

```text
GET /estt/flyTime
  EsttController.calculate
    EsttService.calculate
      validateInputs
      fetchAndCalculate
        getSeasonalFlight → cachedSeasonalFlight [@Cacheable seasonal-flight]
          SeasonRepository.getSeasonalArrivalFlight (INSTR prefilter)
          OperationDays.matches + seasonStart..seasonEnd revalidation
        ├─ seasonalFlight == null → NONE (no history query)
        └─ calculateWithSeasonalFlight
             historyFlightProvider.getHistoryFlights          ← P0: single fetch, FETCH FIRST maxHistoryRows
               HistoryFlightRepository.getArrivalFlight
               filter isEligibleHistoryFlight (in memory)
             flyingTimeCalculator.calculate
               getQualifiedHistoryFlights (schedule deviation)
               median | SEASONAL fallback | NONE

GET /estt/history (pagination — different path)
  EsttService.getPaginatedHistoryFlights
    historyFlightProvider.getPaginatedHistory
      scanPaginatedHistory (paged raw scan up to maxHistoryRows)

GET /estt/history (non-paginated cache path — NOT used by calculate)
  EsttService.cachedHistoryFlights [@Cacheable history-flights]
    historyFlightProvider.getHistoryFlights (same single-fetch path)
```

**P0 confirmation:** `calculateWithSeasonalFlight` calls `getHistoryFlights`, not `scanPaginatedHistory`. One `getArrivalFlight` capped at `maxHistoryRows` raw rows.

---

## Code touch map (planned)

| File | Phase | Change |
|------|-------|--------|
| `HistoryFlightProvider.kt` | 1–2 | Shared scan loop; metrics; `getHistoryFlights` uses paging |
| `EsttService.kt` | 1 | Wire metrics / logging if needed |
| `FlyingTimeCalculator.kt` | 2 | Expose schedule filter for reuse in scan stop condition (or inline) |
| `SeasonRepository.kt` | 3 | `ORDER BY` + single row |
| `docs/algorithm.md` | 2–4 | Row-cap semantics, seasonal ordering |
| `*Test.kt` | 1–3 | Regression + metric tests |

---

## Open questions for user

1. Approve P2 window change (`max(seasonStart, …)`)?  
2. Accept increased DB scan on calculation for edge flights?  
3. Should `/estt/history` pagination align with calculation scan logic?
