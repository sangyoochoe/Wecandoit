package ai.storyteller.craftanything
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private class GeneratedProjectStore(private val activity: MainActivity) {
    private val root = File(activity.filesDir, "generated-project")
    fun save(raw: String): Int {
        var text = raw.trim()
        if (text.startsWith(96.toChar())) text = text.substringAfter('\n').substringBeforeLast(96.toChar()).trim()
        val json = JSONObject(text)
        val files = json.optJSONArray("files") ?: error("AI 응답에 files 목록이 없습니다.")
        require(files.length() in 1..30) { "파일은 1~30개까지 허용됩니다." }
        val staging = File(activity.filesDir, "generated-project-staging")
        staging.deleteRecursively()
        var total = 0L
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            val path = item.getString("path").replace('\\', '/')
            require(path.isNotBlank() && !path.startsWith('/') && path.split('/').none { it == ".." || it.isBlank() }) { "잘못된 경로: $path" }
            val body = item.getString("content")
            total += body.toByteArray(Charsets.UTF_8).size
            require(total <= 2L * 1024 * 1024) { "프로젝트는 2MiB까지 허용됩니다." }
            val out = File(staging, path); out.parentFile?.mkdirs(); out.writeText(body)
        }
        root.deleteRecursively(); check(staging.renameTo(root)) { "프로젝트 저장 실패" }
        File(root, "CRAFT_PROJECT.json").writeText(JSONObject().put("name", json.optString("name", "MyGame")).toString(2))
        return files.length()
    }
    fun context(): String = if (!root.exists()) "없음" else root.walkTopDown().filter { it.isFile }.take(30).joinToString("\n\n") {
        "FILE: \${it.relativeTo(root).invariantSeparatorsPath}\n\${runCatching { it.readText().take(8000) }.getOrDefault("[binary]")}"
    }.take(40000)
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        require(root.exists()) { "먼저 프로젝트를 생성하세요." }
        activity.contentResolver.openOutputStream(uri)?.use { stream ->
            ZipOutputStream(stream).use { zip ->
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("generated-project/\${f.relativeTo(root).invariantSeparatorsPath}"))
                    f.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                }
            }
        } ?: error("저장 위치를 열 수 없습니다.")
    }
    fun exists() = root.exists()
}

@Composable
internal fun ProjectBuilderScreen(activity: MainActivity, settings: AiSettings, referenceName: String, referenceEvidence: String, onSettings: () -> Unit) {
    val store = remember(activity) { GeneratedProjectStore(activity) }
    var request by remember { mutableStateOf("참고 게임의 진행 방식은 참고하되 캐릭터와 배경은 새롭게 만들고 모바일 세로 화면에 맞춰 주세요.") }
    var projectName by remember { mutableStateOf("MyGame") }
    var stage by remember { mutableStateOf("대기 중") }
    var message by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) { busy = true; activity.lifecycleScope.launch {
            val r = runCatching { store.export(uri) }; busy = false
            stage = if (r.isSuccess) "소스 ZIP 저장 완료" else "저장 실패: \${r.exceptionOrNull()?.message}"
        } }
    }
    fun prompt(refine: Boolean): String {
        val existing = if (refine) store.context() else "없음"
        return """너는 Android 게임 개발자다. 참고 앱의 관찰 가능한 아이디어만 참고하고 원본 코드를 복사하거나 실행하지 말고 독립적인 새 게임을 만들어라.
프로젝트 이름: $projectName
사용자 요구: $request
참고 파일: \${referenceName.ifBlank { "없음" }}
참고 분석 정보: \${referenceEvidence.take(10000)}
기존 프로젝트: \${existing.take(30000)}
Android Studio에서 열 수 있는 Kotlin + Jetpack Compose 프로젝트 전체를 작성하라. settings.gradle.kts, build.gradle.kts, app/build.gradle.kts, app/src/main/AndroidManifest.xml, app/src/main/java/com/example/game/MainActivity.kt, README.md를 포함한다. 한국어 UI, 터치 입력, 점수, 게임 진행/재시작을 구현하라. 외부 유료 API나 존재하지 않는 라이브러리를 쓰지 마라.
\${if (refine) "기존 프로젝트를 검토해 누락과 컴파일 가능성 문제를 고치고 전체 파일을 다시 출력하라." else "처음부터 프로젝트를 작성하라."}
설명 없이 JSON 하나만 출력: {"name":"프로젝트명","files":[{"path":"상대경로","content":"파일 전체 내용"}]}. 최대 30개 파일, 총 2MiB 이내. JSON 이스케이프를 지켜라."""
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("새 게임 만들기", style = MaterialTheme.typography.headlineSmall)
        Text("참고 APK 분석 결과와 원하는 기능을 바탕으로 새 Android 프로젝트 코드를 생성합니다. 원본 APK는 실행하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        Card(shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp)) {
            Text("참고 자료", style = MaterialTheme.typography.titleMedium)
            Text(referenceName.ifBlank { "선택한 APK 없음" })
            Text(if (referenceEvidence.isBlank()) "홈에서 APK 분석을 먼저 실행하세요." else "분석 정보 \${referenceEvidence.length()}자 준비됨")
        } }
        OutlinedTextField(projectName, { projectName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("새 게임 이름") })
        OutlinedTextField(request, { request = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("원하는 게임과 추가 기능") })
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("AI 연결 설정") }
        Button(enabled = !busy && request.isNotBlank(), onClick = {
            if (settings.model.isBlank()) { stage = "AI 모델을 설정하세요."; return@Button }
            busy = true; stage = "AI가 프로젝트 코드를 작성하는 중…"
            activity.askProject(settings, prompt(false)) { response ->
                val saved = runCatching { store.save(response) }; busy = false
                saved.onSuccess { count = it; stage = "프로젝트 파일 \${it}개 생성·저장 완료"; message = "소스 프로젝트가 생성되었습니다." }
                    .onFailure { stage = "생성 결과 저장 실패: \${it.message}"; message = response.take(2000) }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "생성 중…" else "① AI로 새 게임 만들기") }
        Button(enabled = !busy && store.exists(), onClick = {
            busy = true; stage = "AI가 코드를 검토하고 개선하는 중…"
            activity.askProject(settings, prompt(true)) { response ->
                val saved = runCatching { store.save(response) }; busy = false
                saved.onSuccess { count = it; stage = "AI 개선본 저장 완료 · 파일 \${it}개" }
                    .onFailure { stage = "개선 실패: \${it.message}" }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("② AI 검토 및 자동 개선") }
        OutlinedButton(enabled = !busy && store.exists(), onClick = { exportPicker.launch("\${projectName.ifBlank { "MyGame" }}-source.zip") }, modifier = Modifier.fillMaxWidth()) { Text("③ 프로젝트 소스 ZIP 내보내기") }
        Text("현재 상태: $stage", color = MaterialTheme.colorScheme.primary)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (count > 0) {
            Text("저장된 파일: $count개")
            Text("실제 APK 빌드와 원본 화면 비교는 아직 연결되지 않았습니다. AI 코드 검토는 컴파일·기기 테스트를 의미하지 않습니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
