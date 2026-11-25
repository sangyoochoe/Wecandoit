# Android engine port: verified status and implementation plan

Updated: 2026-11-25

This document records the verified boundary between the current Android shell and the upstream Craft Anything rebuild workflow. It is not a claim that the Android port is complete.

## Upstream workflow contract

The upstream implementation is in `crates/ai/src/rebuild/` and describes this lifecycle:

1. **Study** — capture a website or inspect a local target and write evidence/notes.
2. **Build** — run a coding agent in the project's `app/` directory. The agent uses the engine's MCP tools; approvals and questions are delivered through the project inbox.
3. **Check** — build the generated project and compare it with the target. Website checks capture the generated site at the same viewport widths and compare screenshots.
4. **Refine** — send measured differences back to the coding agent for another round, bounded by `max_rounds` and `good_enough`.

The upstream run API exposes events including stage changes (`Step`), agent activity, questions/approvals (`Ask`), check results (`Checked`), preview readiness, and final success/failure (`Finished`). These events—not a timer or guessed percentage—must drive Android's persistent progress display.

Relevant upstream source files:
- `crates/ai/src/rebuild/mod.rs`
- `crates/ai/src/rebuild/study.rs`
- `crates/ai/src/rebuild/check.rs`
- `crates/ai/src/rebuild/project.rs`
- `crates/ai/src/rebuild/runs.rs`
- `crates/ai/src/rebuild/notes.rs`
- `crates/ai/src/rebuild/output.rs`

## Verified Android baseline

The current Android workflow extracts `CraftAnything-Android-Phase10.zip`, applies two source patch scripts, builds Rust JNI libraries, then assembles the Android APK.

The workflow explicitly sets:
- `CRAFT_ANDROID_WITH_UPSTREAM_FORMATS=1`
- `CRAFT_ANDROID_WITH_UPSTREAM_ENGINE=0`

The repository README also records that the full desktop AI agent engine is not linked and that desktop process/PTY backends need Android-specific implementations. Therefore the current app is a file-analysis Android app with AI-provider settings; it is not yet an Android implementation of Study → Build → Check → Refine.

## New Android-native Rust workflow core (implementation in progress)

The Android build now injects `scripts/android_workflow.rs` into the extracted JNI crate and exposes three JNI calls:
- `nativeWorkflowTransition`: validates the Study → Build → Check → Refine lifecycle and appends a timestamped event.
- `nativeWorkflowAddEvidence`: stores bounded evidence with a confidence value.
- `nativeWorkflowRecordCheck`: records pass/fail/unknown, an optional numeric score, a summary, and whether the check was actually executed.

The Kotlin workflow store delegates phase transitions, evidence writes and check records to this Rust core, then persists the returned state in app-private storage. The patch script now fails fast if any of these three Kotlin-to-Rust calls is missing, preventing a silent fallback to Kotlin-only state changes. The Rust module includes unit tests for valid/invalid transitions, honest non-executed checks, and bounded evidence. CI runs these Rust tests and compiles Kotlin without creating an APK on ordinary pushes.

This is a real Rust/JNI integration, but it is **not** the upstream desktop agent loop. It does not yet run coding-agent tools, execute generated Gradle builds, launch generated apps, capture runtime output, or automatically feed measured runtime differences into a coding agent. The CI workflow has been changed to attempt the upstream engine feature for the six read-only Android analysis tools; that Android-target compilation has not yet been verified. The upstream AI crate/agent loop remains unlinked. No full Android rebuild run or APK runtime comparison is claimed.

## Required implementation work

### 1. Android-safe engine boundary
- Identify upstream crates and platform services required by the rebuild runner.
- Replace desktop-only process, PTY, browser, filesystem, and approval services with Android-compatible implementations.
- Keep the upstream workflow semantics and event model; do not fake agent output or check scores.
- Use an explicit capability list so unsupported target types and builders are reported as unsupported, not silently treated as successful.

### 2. Persistent project and run state
- Persist the project's request, target, selected mode, builder, current step, run identifier, pending questions/approvals, check results, and output location.
- Resume from the saved project state after activity recreation or process death where the engine supports continuation.
- Keep the run state independent of the currently visible screen.

### 3. Always-visible progress
- Show current stage and latest real status text persistently.
- Show overall completion only when it can be computed from actual stage/check data; otherwise show the current stage without inventing a percentage.
- Show actual check/similarity results separately from the user's target threshold.
- Surface waiting-for-user, paused, failed, and finished states distinctly.
- Use compact progress on the folded cover display and expanded details on the unfolded display.

### 4. Real build/check/refine path
- Connect a supported coding-agent backend that can run safely on Android or through a configured remote build host.
- Route tool approvals and user questions through a clear Korean UI.
- Run the generated project's actual build/check commands in an isolated workspace.
- Feed measured check failures back into the next refinement round.
- Export an APK only when the generated Android project builds successfully; never label a source-only or failed build as an installable app.

### 5. Validation gates
- Unit tests for run-state persistence and event mapping.
- Integration test for a complete website Study → Build → Check → Refine run using a supported builder.
- Integration test for a supported Android project build and APK export.
- Physical Galaxy Z Fold tests for fold/unfold, background/foreground, process recreation, large files, and memory pressure.
- Release signing and reproducible build checks before distribution.

## Current completion statement

As of this update, the Android full rebuild engine is **not connected**. No end-to-end Android project-generation run has been verified. The existing debug APK is a test build, not a production release.

This document is an engineering baseline for the port. It does not mark any of the implementation gates above as complete.
