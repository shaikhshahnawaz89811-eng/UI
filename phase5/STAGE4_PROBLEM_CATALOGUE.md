# Stage 4 Problem Catalogue

Stage 4 started from the working Stage 3 source and recorded failures before fixes. No source-file deletion was used.

## Fixed

| ID | Finding | Root cause | Fix | Verification |
|---|---|---|---|---|
| S4-01 | `ek word tayar karo` was routed incorrectly | `word` was treated as a format only when seen near filename-like words | Context-aware Word format recognition with create-verb confirmation | 5,000/5,000 router pass |
| S4-02 | `Excel formulas`, `presentation skills` could be mistaken for file tasks | Format token matching did not ignore normal topic followers | Topic-word guard around XLSX/PPTX format matches | 5,000/5,000 router pass |
| S4-03 | Short/reversed edit phrases were missed | Replacement grammar had incomplete Hindi/Hinglish forms | Added targeted replacement/verb patterns including `ki jagah`, `se badlo`, `kar do`, `karna` and reversed forms | 5,000/5,000 router pass |
| S4-04 | Missing Excel sheet-name safety change initially broke an existing single-sheet edit | A stricter router rule was applied at the wrong layer | Restored Stage 3 routing; executor now infers the sheet only when exactly one workbook sheet exists, otherwise fails honestly | Full legacy regression 0 failures + deep execution pass |
| S4-05 | Text could be submitted while PDF/ZIP/video/provider validation was still running | Send state did not share the attachment validation lifecycle | Added `AttachmentSendGate`; attachment check locks Send and `+` until validated files are committed | Source-order check + JVM gate tests |
| S4-06 | Race boundary could release Send before attachment commit | Lock release ordering could have exposed a one-event gap | Release is in `finally` only after `addPicked(ok)` on UI thread | Dedicated source sanity assertion |

## Diagnostic / intentionally not hidden

- The older conversation-quality stress diagnostic is still **1242/1300 (95.5%)** with 58 known misses. Those are retained as diagnostic findings and are not relabelled as Stage 4 router failures.
- Android APK/device validation remains pending because this execution environment has no Android SDK, Gradle compiler, or `adb` device bridge. No APK/device pass is claimed.
- The 300-phone sample is prepared in `phase5/phone_sample300.tsv`; its device run must happen through GitHub/Android tooling or a connected phone.
