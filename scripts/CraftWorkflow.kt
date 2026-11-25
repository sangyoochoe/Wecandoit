package ai.storyteller.craftanything

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Durable run ledger. Progress is event-driven; it never invents a percentage. */
internal class CraftWorkflowStore(context: Context) {
    enum class Phase { IDLE, STUDY, BUILD, CHECK, REFINE, SOURCE_READY, READY, FAILED, PAUSED }
    data class CheckResult(val checkId: String, val passed: Boolean?, val score: Double?, val summary: String, val executed: Boolean)

    private val file = File(context.filesDir, "craft-workflow/state.json")
    private val lock = Any()
    private var data = load()
    private fun fresh() = JSONObject().put("schemaVersion", 2).put("runId", UUID.randomUUID().toString())
        .put("phase", Phase.IDLE.name).put("status", "대기 중")
        .put("events", JSONArray()).put("evidence", JSONArray()).put("checks", JSONArray())
    private fun load(): JSONObject = runCatching { if (file.exists()) JSONObject(file.readText()) else fresh() }.getOrElse { fresh() }

    private fun save() = synchronized(lock) {
        file.parentFile?.mkdirs()
        data.put("updatedAt", System.currentTimeMillis())
        val tmp = File(file.parentFile, "state.tmp")
        val backup = File(file.parentFile, "state.bak")
        tmp.writeText(data.toString(2))
        if (backup.exists()) check(backup.delete()) { "상태 백업 정리 실패" }
        if (file.exists()) check(file.renameTo(backup)) { "상태 파일 백업 실패" }
        if (!tmp.renameTo(file)) {
            if (backup.exists()) backup.renameTo(file)
            error("워크플로 상태 저장 실패")
        }
        backup.delete()
    }

    fun begin(reference: String, evidence: String) = synchronized(lock) {
        data = fresh()
        data.put("phase", Phase.STUDY.name).put("status", "참고자료 분석")
        event("STUDY_STARTED", "참고자료 분석 시작")
        val native = JSONObject(NativeBridge.nativeWorkflowAddEvidence(
            data.toString(), "reference", reference, evidence, if (evidence.isBlank()) 0.0 else 0.65
        ))
        require(native.optBoolean("ok")) { native.optString("error", "Rust 참고자료 기록 실패") }
        data = native.getJSONObject("state")
        save()
    }
    fun addEvidence(kind: String, source: String, summary: String, confidence: Double) = synchronized(lock) {
        val native = JSONObject(NativeBridge.nativeWorkflowAddEvidence(data.toString(), kind, source, summary, confidence))
        require(native.optBoolean("ok")) { native.optString("error", "Rust 근거 기록 실패") }
        data = native.getJSONObject("state")
        save()
    }
    private fun addEvidenceInternal(kind: String, source: String, summary: String, confidence: Double) {
        val a = data.optJSONArray("evidence") ?: JSONArray().also { data.put("evidence", it) }
        a.put(JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind)
            .put("source", source.take(500)).put("summary", summary.take(12000))
            .put("confidence", confidence.coerceIn(0.0, 1.0)).put("at", System.currentTimeMillis()))
    }

    fun transition(next: Phase, status: String, detail: String = "") = synchronized(lock) {
        val current = phase()
        val allowed = when (current) {
            Phase.IDLE, Phase.SOURCE_READY, Phase.READY, Phase.FAILED, Phase.PAUSED -> setOf(Phase.STUDY)
            Phase.STUDY -> setOf(Phase.BUILD, Phase.FAILED, Phase.PAUSED)
            Phase.BUILD -> setOf(Phase.CHECK, Phase.FAILED, Phase.PAUSED)
            Phase.CHECK -> setOf(Phase.REFINE, Phase.SOURCE_READY, Phase.READY, Phase.FAILED, Phase.PAUSED)
            Phase.REFINE -> setOf(Phase.CHECK, Phase.FAILED, Phase.PAUSED)
        }
        require(next in allowed) { "잘못된 단계 전환: ${current.name} -> ${next.name}" }
        data.put("phase", next.name).put("status", status)
        event("PHASE_CHANGED", if (detail.isBlank()) status else detail); save()
    }

    fun startRefinement(status: String) = synchronized(lock) {
        val current = phase()
        require(current == Phase.SOURCE_READY || current == Phase.READY || current == Phase.CHECK) {
            "소스 검토가 끝난 프로젝트만 개선할 수 있습니다."
        }
        data.put("phase", Phase.REFINE.name).put("status", status)
        event("REFINEMENT_STARTED", status); save()
    }

    /** Records the exact check and whether it was actually executed. */
    fun recordCheck(result: CheckResult) = synchronized(lock) {
        val a = data.optJSONArray("checks") ?: JSONArray().also { data.put("checks", it) }
        a.put(JSONObject().put("id", result.checkId).put("passed", result.passed)
            .put("score", result.score).put("summary", result.summary.take(4000))
            .put("executed", result.executed).put("at", System.currentTimeMillis()))
        event("CHECK_RECORDED", result.summary); save()
    }
    fun fail(reason: String) = synchronized(lock) {
        data.put("phase", Phase.FAILED.name).put("status", reason); event("RUN_FAILED", reason); save()
    }
    fun pause(reason: String) = synchronized(lock) {
        data.put("phase", Phase.PAUSED.name).put("status", reason); event("RUN_PAUSED", reason); save()
    }
    fun phase(): Phase = runCatching { Phase.valueOf(data.optString("phase", Phase.IDLE.name)) }.getOrDefault(Phase.IDLE)
    fun snapshot(): JSONObject = synchronized(lock) { JSONObject(data.toString()) }
    private fun event(type: String, message: String) {
        val a = data.optJSONArray("events") ?: JSONArray().also { data.put("events", it) }
        a.put(JSONObject().put("sequence", a.length() + 1).put("type", type)
            .put("message", message.take(2000)).put("at", System.currentTimeMillis()))
        while (a.length() > 300) a.remove(0)
    }
}
