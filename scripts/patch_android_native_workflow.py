from pathlib import Path

root = Path("craft-anything-android")
native = root / "native"
src = native / "src"
src.mkdir(parents=True, exist_ok=True)

workflow_rs = Path("scripts/android_workflow.rs").read_text(encoding="utf-8")
(src / "android_workflow.rs").write_text(workflow_rs, encoding="utf-8")

lib = src / "lib.rs"
text = lib.read_text(encoding="utf-8")
if "mod android_workflow;" not in text:
    text += "\n\nmod android_workflow;\n"
lib.write_text(text, encoding="utf-8")

bridge = root / "app/src/main/java/ai/storyteller/craftanything/NativeBridge.kt"
k = bridge.read_text(encoding="utf-8")
needle = "    external fun nativeStatus(): String"
if "nativeWorkflowTransition" not in k:
    k = k.replace(needle, needle + """
    external fun nativeWorkflowTransition(stateJson: String, next: String, status: String, detail: String): String
    external fun nativeWorkflowAddEvidence(stateJson: String, kind: String, source: String, summary: String, confidence: Double): String
    external fun nativeWorkflowRecordCheck(stateJson: String, checkId: String, passed: Int, score: Double, summary: String, executed: Boolean): String
""")
bridge.write_text(k, encoding="utf-8")

workflow = root / "app/src/main/java/ai/storyteller/craftanything/CraftWorkflow.kt"
if workflow.exists():
    k = workflow.read_text(encoding="utf-8")

    # Route the actual Kotlin phase transition through Rust. Match the source's
    # current interpolation form, not an obsolete string-concatenation variant.
    old = '''        require(next in allowed) { "잘못된 단계 전환: ${current.name} -> ${next.name}" }
        data.put("phase", next.name).put("status", status)
        event("PHASE_CHANGED", if (detail.isBlank()) status else detail); save()'''
    new = '''        require(next in allowed) { "잘못된 단계 전환: ${current.name} -> ${next.name}" }
        val native = JSONObject(NativeBridge.nativeWorkflowTransition(data.toString(), next.name, status, detail))
        require(native.optBoolean("ok")) { native.optString("error", "Rust 워크플로 단계 전환 실패") }
        data = native.getJSONObject("state")
        save()'''
    if old in k:
        k = k.replace(old, new, 1)
    elif "NativeBridge.nativeWorkflowTransition(data.toString(), next.name" not in k:
        raise SystemExit("Kotlin phase-transition anchor missing; refusing a partial patch")

    old = '''        a.put(JSONObject().put("id", result.checkId).put("passed", result.passed)
            .put("score", result.score).put("summary", result.summary.take(4000))
            .put("executed", result.executed).put("at", System.currentTimeMillis()))
        event("CHECK_RECORDED", result.summary); save()'''
    new = '''        val native = JSONObject(NativeBridge.nativeWorkflowRecordCheck(
            data.toString(), result.checkId,
            result.passed?.let { if (it) 1 else 0 } ?: -1,
            result.score ?: Double.NaN, result.summary, result.executed
        ))
        require(native.optBoolean("ok")) { native.optString("error", "Rust 검사 기록 실패") }
        data = native.getJSONObject("state")
        save()'''
    if old in k:
        k = k.replace(old, new, 1)
    elif "NativeBridge.nativeWorkflowRecordCheck(" not in k:
        raise SystemExit("Kotlin check-record anchor missing; refusing a partial patch")

    workflow.write_text(k, encoding="utf-8")
