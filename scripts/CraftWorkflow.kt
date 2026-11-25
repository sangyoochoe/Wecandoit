package ai.storyteller.craftanything

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Persistent, inspectable Android workflow state: Study -> Build -> Check -> Refine. */
internal class CraftWorkflowStore(context: Context) {
    enum class Phase { IDLE, STUDY, BUILD, CHECK, REFINE, READY, FAILED, PAUSED }
    private val file = File(context.filesDir, "craft-workflow/state.json")
    private val lock = Any()
    private var data = load()
    private fun fresh() = JSONObject().put("runId", UUID.randomUUID().toString())
        .put("phase", Phase.IDLE.name).put("status", "대기 중")
        .put("events", JSONArray()).put("evidence", JSONArray())
    private fun load() = runCatching { if (file.exists()) JSONObject(file.readText()) else fresh() }.getOrElse { fresh() }
    private fun save() = synchronized(lock) {
        file.parentFile?.mkdirs()
        data.put("updatedAt", System.currentTimeMillis())
        val tmp = File(file.parentFile, "state.tmp")
        tmp.writeText(data.toString(2))
        if (file.exists()) check(file.delete()) { "이전 상태 파일 삭제 실패" }
        check(tmp.renameTo(file)) { "워크플로 상태 저장 실패" }
    }
    fun begin(reference: String, evidence: String) = synchronized(lock) {
        data = fresh()
        data.put("phase", Phase.STUDY.name).put("status", "참고자료 분석")
        event("STUDY", "참고자료 분석 시작")
        addEvidence("reference", reference, evidence, if (evidence.isBlank()) 0.0 else 0.65)
        save()
    }
    fun addEvidence(kind: String, source: String, summary: String, confidence: Double) = synchronized(lock) {
        val a = data.optJSONArray("evidence") ?: JSONArray().also { data.put("evidence", it) }
        a.put(JSONObject().put("id", UUID.randomUUID().toString()).put("kind", kind)
            .put("source", source.take(500)).put("summary", summary.take(12000))
            .put("confidence", confidence.coerceIn(0.0, 1.0)).put("at", System.currentTimeMillis()))
        save()
    }
    fun transition(next: Phase, status: String, detail: String = "") = synchronized(lock) {
        val current = runCatching { Phase.valueOf(data.optString("phase", "IDLE")) }.getOrDefault(Phase.IDLE)
        val allowed = when (current) {
            Phase.IDLE, Phase.READY, Phase.FAILED, Phase.PAUSED -> setOf(Phase.STUDY)
            Phase.STUDY -> setOf(Phase.BUILD, Phase.FAILED, Phase.PAUSED)
            Phase.BUILD -> setOf(Phase.CHECK, Phase.FAILED, Phase.PAUSED)
            Phase.CHECK -> setOf(Phase.REFINE, Phase.READY, Phase.FAILED, Phase.PAUSED)
            Phase.REFINE -> setOf(Phase.CHECK, Phase.READY, Phase.FAILED, Phase.PAUSED)
        }
        require(next in allowed) { "잘못된 단계 전환: " + current.name + " -> " + next.name }
        data.put("phase", next.name).put("status", status)
        event(next.name, if (detail.isBlank()) status else detail)
        save()
    }
    fun fail(reason: String) = synchronized(lock) {
        data.put("phase", Phase.FAILED.name).put("status", reason)
        event("FAILED", reason); save()
    }
    fun snapshot(): JSONObject = synchronized(lock) { JSONObject(data.toString()) }
    private fun event(type: String, message: String) {
        val a = data.optJSONArray("events") ?: JSONArray().also { data.put("events", it) }
        a.put(JSONObject().put("type", type).put("message", message.take(2000)).put("at", System.currentTimeMillis()))
        while (a.length() > 200) a.remove(0)
    }
}
