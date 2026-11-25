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
- `CRAFT_ANDROID_WITH_UPSTREAM_ENGINE=1` (an attempted enablement of the upstream read-only analysis tools; Android-target compilation is not yet verified).

The repository README also records that the full desktop AI agent engine is not linked and that desktop process/PTY backends need Android-specific implementations. Therefore the current app is a file-analysis Android app with AI-provider settings; it is not yet an Android implementation of Study → Build → Check → Refine.

## Single Android orchestrator (implementation in progress)

The generated-project action is now routed through one `CraftAgentOrchestrator`. It owns the run and sequences the specialist stages in order:

1. Study sends reference evidence and user requirements as separate sections and produces an analysis brief.
2. Build receives that brief and asks the configured AI provider for the independent Android project.
3. Check applies the real local source-safety checks (relative paths, traversal rejection, duplicate paths, file count, and total source size) and records that this is not a Gradle/device test.
4. Refine sends the saved project and Study brief back through the same coordinator for up to three full-source revision rounds, with a Check record after each round.

The coordinator is a single controller; the stages are not independent agents. At present each stage is a separate AI chat request. The implementation does **not** yet have the upstream MCP tool loop, autonomous shell/Gradle execution, Android emulator launch, screenshot capture, or runtime-driven refinement. A source-ready result must not be interpreted as a verified APK.

## Generated Android project preflight and remote smoke test

The generated-project save path now runs a structural preflight before replacing the previously saved project. It requires settings.gradle.kts, root and app Gradle scripts, an Android application plugin declaration, compileSdk, namespace, an AndroidManifest with an application and MAIN/LAUNCHER activity, and non-empty Kotlin source under app/src/main/java. Failed preflight leaves the prior saved project intact because validation runs in the staging directory before the atomic replacement.

The generated source ZIP now contains a GitHub Actions workflow that runs Android unit tests, assembles a debug APK, installs it in an Android emulator, launches the package, captures a smoke-test screenshot, and uploads the APK and screenshot. This is a real build-and-launch path **after the user exports the ZIP into a GitHub repository and pushes it**. The Android app does not yet automatically upload generated source to a remote runner or receive that workflow result. Emulator launch is only a startup smoke test, not a feature-level test or visual-equivalence check.

## New Android-native Rust workflow core (implementation in progress)

The Android build now injects `scripts/android_workflow.rs` into the extracted JNI crate and exposes three JNI calls:
- `nativeWorkflowTransition`: validates the Study → Build → Check → Refine lifecycle and appends a timestamped event.
- `nativeWorkflowAddEvidence`: stores bounded evidence with a confidence value.
- `nativeWorkflowRecordCheck`: records pass/fail/unknown, an optional numeric score, a summary, and whether the check was actually executed.

The Kotlin workflow store delegates phase transitions, evidence writes and check records to this Rust core, then persists the returned state in app-private storage. The patch script now fails fast if any of these three Kotlin-to-Rust calls is missing, preventing a silent fallback to Kotlin-only state changes. Rust event history now keeps monotonic sequence numbers after old events are trimmed; check history is capped at 300 records, matching the bounded event log. Unit tests cover valid/invalid transitions, honest non-executed checks, bounded evidence, monotonic event numbering, and bounded check history. CI runs these Rust tests and compiles Kotlin without creating an APK on ordinary pushes.

This is a real Rust/JNI integration, but it is **not** the upstream desktop agent loop. It does not yet run coding-agent tools, execute generated Gradle builds, launch generated apps, capture runtime output, or automatically feed measured runtime differences into a coding agent. The upstream engine feature was briefly enabled for the six read-only Android analysis tools, but CI failed in upstream native bridge code (`serde_json::from_str` type inference at `src/lib.rs:394`). It has been disabled again so the Android JNI workflow can be validated independently; the upstream analysis tools are not currently claimed as available. The upstream AI crate/agent loop remains unlinked. No full Android rebuild run or APK runtime comparison is claimed.

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

As of this update, the Android full rebuild engine is **not connected**. No end-to-end Android project-generation run has been verified. The existing debug APK is a test build, not a production release. Generated project builds and emulator smoke tests are separate workflows and require the user to export the generated source ZIP to a GitHub repository.

This document is an engineering baseline for the port. It does not mark any of the implementation gates above as complete.
