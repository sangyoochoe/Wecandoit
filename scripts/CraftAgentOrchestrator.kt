package ai.storyteller.craftanything

/**
 * One coordinator owns the run. Study/Build/Check/Refine are specialist stages,
 * not independent agents or separate sessions with their own lifecycle.
 *
 * The current backend is an AI chat request per stage; this does not claim
 * access to the upstream MCP coding-agent tool loop or a real Gradle runner.
 */
internal class CraftAgentOrchestrator(
    private val workflow: CraftWorkflowStore,
    private val ask: (String, (String) -> Unit) -> Unit,
    private val saveProject: (String) -> Int
) {
    data class Outcome(val fileCount: Int, val reviewRounds: Int, val studyNotes: String)

    fun create(
        studyPrompt: String,
        buildPrompt: (String) -> String,
        reviewPrompt: (String, String, Int) -> String,
        reviewRounds: Int = 3,
        onStage: (String) -> Unit,
        onComplete: (Result<Outcome>) -> Unit
    ) {
        require(reviewRounds in 0..3) { "개선 반복은 0~3회까지 허용됩니다." }
        onStage("Study · 참고자료 분석")
        ask(studyPrompt) studyResult@ { study ->
            if (study.startsWith("AI 프로젝트 생성 실패:")) {
                fail(study, onComplete); return@studyResult
            }
            runCatching {
                workflow.addEvidence("study_notes", "study-agent", study, 0.55)
                workflow.transition(CraftWorkflowStore.Phase.BUILD, "Build · 새 프로젝트 생성")
            }.onFailure { fail(it.message ?: "Study 단계 저장 실패", onComplete); return@buildResult }
            onStage("Build · 독립 프로젝트 생성")
            ask(buildPrompt(study)) buildResult@ { generated ->
                val initialCount = runCatching { saveProject(generated) }
                if (initialCount.isFailure) {
                    fail(initialCount.exceptionOrNull()?.message ?: "프로젝트 저장 실패", onComplete)
                    return@reviewResult
                }
                runCatching {
                    workflow.transition(CraftWorkflowStore.Phase.CHECK, "Check · 소스 구조 검사")
                    workflow.recordCheck(CraftWorkflowStore.CheckResult(
                        "source_structure", true, null,
                        "경로·중복·파일 수·크기 검증 통과. Gradle 빌드 및 앱 실행은 미실행.", false
                    ))
                }.onFailure { fail(it.message ?: "소스 검사 기록 실패", onComplete); return@ask }
                review(1, reviewRounds, study, initialCount.getOrThrow(), buildPrompt(study), reviewPrompt, onStage, onComplete)
            }
        }
    }

    private fun review(
        round: Int,
        limit: Int,
        study: String,
        fileCount: Int,
        buildContext: String,
        reviewPrompt: (String, String, Int) -> String,
        onStage: (String) -> Unit,
        onComplete: (Result<Outcome>) -> Unit
    ) {
        if (round > limit) {
            runCatching {
                workflow.transition(CraftWorkflowStore.Phase.SOURCE_READY,
                    "소스 준비 완료 · 실제 빌드 미검증",
                    "단일 오케스트레이터의 Study/Build/Check/Refine 단계 완료")
            }.onSuccess { onComplete(Result.success(Outcome(fileCount, limit, study))) }
                .onFailure { fail(it.message ?: "완료 상태 저장 실패", onComplete) }
            return
        }
        runCatching {
            workflow.transition(CraftWorkflowStore.Phase.REFINE, "Refine · AI 개선 $round/$limit")
        }.onFailure { fail(it.message ?: "개선 단계 전환 실패", onComplete); return }
        onStage("Refine · AI 개선 $round/$limit")
        ask(reviewPrompt(buildContext, study, round)) reviewResult@ { revised ->
            val count = runCatching { saveProject(revised) }
            if (count.isFailure) {
                fail(count.exceptionOrNull()?.message ?: "개선 결과 저장 실패", onComplete)
                return@ask
            }
            runCatching {
                workflow.addEvidence("ai_refinement", "round-$round",
                    "AI가 전체 소스 파일을 다시 생성하고 경로·크기 검사를 통과함. 빌드·실행 검증은 아님.", 0.4)
                workflow.transition(CraftWorkflowStore.Phase.CHECK, "Check · 개선 결과 소스 검사")
                workflow.recordCheck(CraftWorkflowStore.CheckResult(
                    "refine_source_$round", true, null,
                    "개선 결과의 경로·중복·파일 수·크기 검증 통과. Gradle 빌드 및 앱 실행은 미실행.", false
                ))
            }.onFailure { fail(it.message ?: "개선 검사 기록 실패", onComplete); return@ask }
            review(round + 1, limit, study, count.getOrThrow(), buildContext, reviewPrompt, onStage, onComplete)
        }
    }

    private fun fail(reason: String, onComplete: (Result<Outcome>) -> Unit) {
        runCatching { workflow.fail(reason) }
        onComplete(Result.failure(IllegalStateException(reason)))
    }
}
