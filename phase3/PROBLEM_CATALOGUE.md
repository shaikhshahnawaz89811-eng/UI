# Phase 3 — Problem Catalogue

This catalogue records problems found while exercising the 1,000-question web-planner corpus and the adjacent conversation stress suite. Status means the Phase 3 code in this ZIP, not a prediction about future work.

## Fixed in Phase 3

### 1. Reading a web picture did not always trigger a search
**Observed:** image-reading prompts such as “photo padh ke batao” could classify as CHAT instead of fetching a web picture.

**Fix:** `readImages` is now a direct search trigger, while showing the image is suppressed unless the user explicitly asks to show it too.

**Status:** fixed; all 100 image-read corpus rows pass.

### 2. “images mat dikhao” could become whole-answer read-only
**Observed:** an image-display constraint could be interpreted as “do not show anything”, blocking unrelated links/steps.

**Fix:** image-specific hiding is separated from whole-answer read-only handling. A text-only constraint such as “photo mat dikhao, bas price batao” still preserves the existing read-only contract.

**Status:** fixed and covered by mixed-intent regressions.

### 3. URL path words polluted intent detection
**Observed:** a pasted URL containing a path such as `/guide` could accidentally set the `steps` flag even when the user only asked to open/read the URL.

**Fix:** intent-pattern matching now runs on user text with URLs removed; URL extraction itself is still preserved for the page-reading path.

**Status:** fixed; URL corpus passes without URL-path-induced step flags.

### 4. General comparisons were unnecessarily restricted to products
**Observed:** comparisons such as “Python vs Java” and “OLED vs LCD” could stay offline because the planner only promoted product/fresh/digit-model comparisons.

**Fix:** comparisons are now web-worthy by default, except when a clear teaching/learning cue is present (for example “Python vs Java kaunsa seekhu”), preserving the existing offline-learning behaviour.

**Status:** fixed and regression-tested.

### 5. Generic “bank account” procedure wording was incomplete
**Observed:** “bank account kaise…” did not always match the procedure vocabulary because only “bank account open…” was recognized.

**Fix:** procedure matching accepts generic `bank account` as well as explicit account-opening wording.

**Status:** fixed.

### 6. “Latest/current” wording missed some named procedures/products
**Observed:** a message such as “latest IRCTC Tatkal rules” could fall through when the time word was present but price/role/fact signals were absent.

**Fix:** current/latest detection now also considers named products, procedure terms and proper nouns.

**Status:** fixed; fresh-intent corpus passes.

## Still visible outside the web-planner score

### 7. Conversation-level same-language paraphrase weakness
The existing 1,300-message conversation stress suite currently reports 19/39 (48.7%) for its `REPEAT_PARA` slice. This is a separate memory/conversation classification problem, not a Phase 3 Tavily-planner failure.

**Status:** known weakness; retained as an explicit research target for later work.

### 8. Conversation-level cross-language paraphrase weakness
The same stress suite reports 11/45 (24.4%) for `REPEAT_XLANG`. This is also outside the web planner and is intentionally reported instead of being converted into a false Phase 3 web score.

**Status:** known weakness; retained for later conversation/memory work.

## Phase 3 release gate

- 1,000 web-planner questions: **PASS**
- Planner mismatches: **0**
- Full JVM checks: **3,051 / 3,051 PASS**
- Live Tavily runner: **included and dry-run verified**
- Real-key live run in this environment: **not executed because no real Tavily key was supplied**
