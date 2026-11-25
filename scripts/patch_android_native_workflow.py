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
    external fun nativeWorkflowRecordCheck(stateJson: String, checkId: String, passed: Int, score: Double, summary: String, executed: Boolean): String
""")
bridge.write_text(k, encoding="utf-8")

workflow = root / "app/src/main/java/ai/storyteller/craftanything/CraftWorkflow.kt"
if workflow.exists():
    k = workflow.read_text(encoding="utf-8")
    old = '''        require(next in allowed) { "잘못된 단계 전환: " + current.name + " -> " + next.name }
        data.put("phase", next.name).put("status", status)
        event("PHASE_CHANGED", if (detail.isBlank()) status else detail)
        save()'''
    new = '''        require(next in allowed) { "잘못된 단계 전환: " + current.name + " -> " + next.name }
        val native = JSONObject(NativeBridge.nativeWorkflowTransition(data.toString(), next.name, status, detail))
        require(native.optBoolean("ok")) { native.optString("error", "Rust 워크플로 엔진이 단계를 거부했습니다.") }
        data = native.getJSONObject("state")
        save()'''
    if old in k:
        k = k.replace(old, new, 1)
    old_check = '''        a.put(JSONObject().put("id", result.checkId).put("passed", result.passed)
            .put("score", result.score).put("summary", result.summary.take(4000))
            .put("executed", result.executed).put("at", System.currentTimeMillis()))
        event("CHECK_RECORDED", result.summary)
        save()'''
    new_check = '''        val native = JSONObject(NativeBridge.nativeWorkflowRecordCheck(
            data.toString(), result.checkId, result.passed?.let { if (it) 1 else 0 } ?: -1, result.score ?: Double.NaN, result.summary, result.executed
        ))
        require(native.optBoolean("ok")) { native.optString("error", "Rust 검사 기록 실패") }
        data = native.getJSONObject("state")
        save()'''
    if old_check in k:
        k = k.replace(old_check, new_check, 1)
    workflow.write_text(k, encoding="utf-8")
