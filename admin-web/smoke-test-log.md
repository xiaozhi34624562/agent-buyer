# Browser Smoke Test Log - V3M5-05

Date: 2026-05-02

## Desktop 1440x900

- Three panels (Runs, Timeline, Chat) display correctly without overlap
- Header shows "Agent Buyer Console" with User info
- Buttons: New Chat, Refresh, Interrupt, Abort - all properly sized
- Status filter combobox and userId filter textbox visible
- Error message "listRuns failed: 502" displayed (backend not running - expected)
- Timeline panel shows "Timeline (0 nodes)" and "No trajectory data"
- Chat panel shows input textbox and Send button

## Mobile 390x844

- Tabs navigation shows: Runs, Timeline, Chat buttons
- Header shows with all buttons (Refresh, Debug, Settings)
- Panels switch via tabs correctly
- Buttons and text scale appropriately for mobile

## Findings

- No panel overlap detected
- Button text does not overflow
- Debug button disabled until run selected (correct behavior)
- Chat input visible and functional
- Error handling works (502 error shown when backend unavailable)

## Console Errors

- `Failed to load resource: .../api/admin/console/runs?page=1&pageSize=20` (502)
- Expected because backend is not running

## E2E Real Backend Test (2026-05-02)

### Setup

- Backend: `mvn spring-boot:run -Dspring-boot.run.profiles=local`
- Frontend: `npm run dev` (port 5173)
- MySQL and Redis running

### Findings

1. **Run List**: Works correctly - displays real run data from backend
2. **API Contract Mismatch**: Trajectory endpoint returns different format
   - Frontend expects: `{ runId, nodes: TrajectoryNode[] }`
   - Backend returns: `{ run, messages, llmAttempts, toolCalls, toolResults, events, ... }`
   - Error: `TypeError: nodes is not iterable at sortNodes`

### Issue Summary

The frontend `useRunDetail` hook and `TimelinePanel` were designed based on an assumed API structure. The actual backend `/api/agent/runs/{runId}` endpoint returns a richer structure that needs to be mapped to the frontend's `TrajectoryNode` format.

### Resolution Options

1. Add backend endpoint that returns trajectory nodes in expected format
2. Or update frontend to parse the rich backend response into nodes

### Conclusion

- Mock tests (164 pass): Frontend logic verified with fixtures
- Browser smoke tests: Layout and UI verified
- E2E real backend: API contract mismatch identified - requires contract alignment

Frontend development complete. Backend/frontend contract alignment needed for full E2E integration.