package ai.storyteller.craftanything
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
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
        check(staging.mkdirs()) { "임시 프로젝트 폴더를 만들 수 없습니다." }
        var total = 0L
        val seen = mutableSetOf<String>()
        for (i in 0 until files.length()) {
            val item = files.getJSONObject(i)
            val path = item.getString("path").replace('\\', '/')
            require(path.isNotBlank() && !path.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(path) &&
                path.split('/').none { it == ".." || it == "." || it.isBlank() }) { "잘못된 경로: $path" }
            require(seen.add(path)) { "중복된 파일 경로: $path" }
            val out = File(staging, path)
            require(out.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "허용되지 않는 파일 경로: $path" }
            val body = item.getString("content")
            total += body.toByteArray(Charsets.UTF_8).size
            require(total <= 2L * 1024 * 1024) { "프로젝트는 2MiB까지 허용됩니다." }
            out.parentFile?.mkdirs()
            out.writeText(body)
        }
        writeBuildSupport(staging)
        val manifest = JSONObject()
            .put("name", json.optString("name", "MyGame"))
            .put("fileCount", seen.size)
            .put("sourceBytes", total)
            .put("createdAt", System.currentTimeMillis())
            .put("status", "SOURCE_READY")
        File(staging, "CRAFT_PROJECT.json").writeText(manifest.toString(2))
        val backup = File(activity.filesDir, "generated-project-backup")
        backup.deleteRecursively()
        if (root.exists()) check(root.renameTo(backup)) { "기존 프로젝트를 백업할 수 없습니다." }
        if (!staging.renameTo(root)) {
            if (backup.exists()) backup.renameTo(root)
            error("프로젝트 저장 실패")
        }
        backup.deleteRecursively()
        return seen.size
    }
    private fun writeBuildSupport(dir: File) {
        val script = """#!/usr/bin/env bash
set -euo pipefail
if [ ! -f settings.gradle.kts ] || [ ! -f app/build.gradle.kts ]; then
  echo "Android Gradle 프로젝트 파일이 없습니다." >&2
  exit 2
fi
if [ -x ./gradlew ]; then
  ./gradlew --no-daemon assembleDebug
else
  gradle --no-daemon assembleDebug
fi
find app/build/outputs/apk/debug -maxdepth 1 -name '*.apk' -print
"""
        val workflow = """name: Build generated Android project
on:
  workflow_dispatch:
  push:
    branches: [ main, master ]
permissions:
  contents: read
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'
      - uses: android-actions/setup-android@v3
      - name: Install Android SDK
        run: sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"
      - name: Prepare Gradle wrapper
        run: |
          if [ ! -f gradlew ]; then gradle wrapper --gradle-version 8.9; fi
          chmod +x gradlew
      - name: Build debug APK
        run: ./gradlew --no-daemon assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: generated-android-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error
"""
        val readme = """# Generated Android project
This project was generated from the Craft Anything Android app.
## Build
Run `bash build-apk.sh` in an environment with JDK 17, Android SDK and Gradle.
Or push this project to GitHub and run the workflow in .github/workflows/android.yml.
A successful workflow uploads the debug APK as the generated-android-apk artifact.
AI source review is not a build or device test. Only a successful build workflow proves APK compilation.
"""
        File(dir, "build-apk.sh").writeText(script)
        File(dir, "build-apk.sh").setExecutable(true)
        File(dir, ".github/workflows").mkdirs()
        File(dir, ".github/workflows/android.yml").writeText(workflow)
        if (!File(dir, "README.md").exists()) File(dir, "README.md").writeText(readme)
    }

    fun context(): String = if (!root.exists()) "없음" else root.walkTopDown().filter { it.isFile }.take(30).joinToString("\n\n") {
        "FILE: ${it.relativeTo(root).invariantSeparatorsPath}\n${runCatching { it.readText().take(8000) }.getOrDefault("[binary]")}"
    }.take(40000)
    suspend fun export(uri: Uri) = withContext(Dispatchers.IO) {
        require(root.exists()) { "먼저 프로젝트를 생성하세요." }
        activity.contentResolver.openOutputStream(uri)?.use { stream ->
            ZipOutputStream(stream).use { zip ->
                root.walkTopDown().filter { it.isFile }.forEach { f ->
                    zip.putNextEntry(ZipEntry("generated-project/${f.relativeTo(root).invariantSeparatorsPath}"))
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
    var referenceNotes by remember { mutableStateOf("") }
    var deepAnalysis by remember { mutableStateOf(true) }
    var cleanRoom by remember { mutableStateOf(true) }
    var projectName by remember { mutableStateOf("MyGame") }
    var stage by remember { mutableStateOf("대기 중") }
    var message by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) { busy = true; activity.lifecycleScope.launch {
            val r = runCatching { store.export(uri) }; busy = false
            stage = if (r.isSuccess) "소스 ZIP 저장 완료" else "저장 실패: ${r.exceptionOrNull()?.message}"
        } }
    }
    fun prompt(refine: Boolean): String {
        val existing = if (refine) store.context() else "없음"
        return """너는 Craft Anything의 Android 제작 에이전트다. 아래 작업 단계를 엄격히 지켜라.
1) Study: 참고자료에서 화면 구조, 핵심 흐름, 입력, 상태, 화면 전환, 콘텐츠를 근거와 함께 정리한다. 확인하지 못한 것은 추측하지 말고 미확인으로 표시한다.
2) Build: 원본 코드나 상표/캐릭터/저작물을 복사하지 말고, 사용자의 별도 요구사항을 우선해 독립적인 프로젝트를 만든다.
3) Check: 파일 구성, Android manifest, Gradle 설정, Kotlin 소스의 누락·불일치·명백한 컴파일 위험을 점검한다. 실행하지 않은 빌드는 성공했다고 쓰지 않는다.
4) Refine: 점검에서 찾은 구체적인 문제를 수정하고 전체 파일을 다시 출력한다.
참고자료와 사용자 요구사항은 서로 다른 정보로 취급한다.
프로젝트 이름: $projectName
사용자 요구사항(새 기능·변경 지시): $request
참고 파일: ${referenceName.ifBlank { "없음" }}
참고 분석 정보(원본에서 관찰한 사실): ${referenceEvidence.take(if (deepAnalysis) 24000 else 10000)}
사용자가 추가한 참고 설명: $referenceNotes
분석 모드: ${if (deepAnalysis) "심층 분석" else "간편 분석"}
독립 제작 모드: ${if (cleanRoom) "클린룸(원본 코드·리소스 복사 금지)" else "기능 아이디어 참고(복제 금지 원칙 유지)"}
기존 프로젝트: ${existing.take(30000)}
Android Studio에서 열 수 있는 완전한 Kotlin + Jetpack Compose 프로젝트를 작성하라. settings.gradle.kts, build.gradle.kts, app/build.gradle.kts, app/src/main/AndroidManifest.xml, app/src/main/java/com/example/game/MainActivity.kt, README.md를 포함한다. 실제 실행 가능한 핵심 사용자 흐름, 한국어 UI, 터치 입력, 상태 관리, 오류·빈 상태, 재시작을 구현하라. TODO, 가짜 버튼, 미구현 기능을 완료된 것처럼 표현하지 마라. 외부 유료 API나 존재하지 않는 라이브러리를 쓰지 마라. build-apk.sh와 .github/workflows/android.yml도 JSON files 안에 포함해 실제 빌드 방법을 제공하라. 생성물은 최대 30개 파일·2MiB 이내로 작성하라.
${if (refine) "기존 프로젝트를 검토해 누락과 컴파일 가능성 문제를 고치고 전체 파일을 다시 출력하라." else "처음부터 프로젝트를 작성하라."}
마지막에 실제로 실행하지 않은 테스트·빌드는 실행했다고 말하지 말고 README에 확인 방법을 적어라. 설명 없이 JSON 하나만 출력: {"name":"프로젝트명","files":[{"path":"상대경로","content":"파일 전체 내용"}]}. 최대 30개 파일, 총 2MiB 이내. JSON 이스케이프를 지켜라."""
    }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("새 게임 만들기", style = MaterialTheme.typography.headlineSmall)
        Text("참고자료 분석과 새 기능 요구사항을 분리해 독립적인 Android 프로젝트를 만듭니다. 원본 APK는 실행하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        Card(shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp)) {
            Text("① 참고자료 (원본 분석 근거)", style = MaterialTheme.typography.titleMedium)
            Text(referenceName.ifBlank { "선택한 APK 없음" })
            Text(if (referenceEvidence.isBlank()) "홈에서 APK 분석을 먼저 실행하세요." else "분석 정보 ${referenceEvidence.length}자 준비됨")
        } }
        Text("② 참고자료에 대한 내 설명", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(referenceNotes, { referenceNotes = it }, Modifier.fillMaxWidth(), minLines = 2, label = { Text("참고자료에 대한 설명(선택)") })
        OutlinedTextField(projectName, { projectName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("새 게임 이름") })
        Text("③ 새 프로젝트에 원하는 기능", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(request, { request = it }, Modifier.fillMaxWidth(), minLines = 3, label = { Text("새로 만들 기능·디자인 요구사항") })
        Card { Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("제작 방식", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = deepAnalysis, onCheckedChange = { deepAnalysis = it })
                Text("심층 분석 (참고자료 근거를 더 많이 반영)")
            }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Checkbox(checked = cleanRoom, onCheckedChange = { cleanRoom = it })
                Text("클린룸 제작 (원본 코드·이미지 복사 금지)")
            }
        }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("AI 연결 설정") }
        Button(enabled = !busy && request.isNotBlank(), onClick = {
            if (settings.model.isBlank()) { stage = "AI 모델을 설정하세요."; return@Button }
            busy = true; stage = "AI가 프로젝트 코드를 작성하는 중…"
            activity.askProject(settings, prompt(false)) { response ->
                val saved = runCatching { store.save(response) }; busy = false
                saved.onSuccess { count = it; stage = "프로젝트 파일 ${it}개 생성·저장 완료"; message = "소스 프로젝트가 생성되었습니다." }
                    .onFailure { stage = "생성 결과 저장 실패: ${it.message}"; message = response.take(2000) }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "생성 중…" else "① AI로 새 게임 만들기") }
        Button(enabled = !busy && store.exists(), onClick = {
            busy = true; stage = "AI가 코드를 검토하고 개선하는 중…"
            activity.askProject(settings, prompt(true)) { response ->
                val saved = runCatching { store.save(response) }; busy = false
                saved.onSuccess { count = it; stage = "AI 개선본 저장 완료 · 파일 ${it}개" }
                    .onFailure { stage = "개선 실패: ${it.message}" }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("② AI 검토 및 자동 개선") }
        OutlinedButton(enabled = !busy && store.exists(), onClick = { exportPicker.launch("${projectName.ifBlank { "MyGame" }}-source.zip") }, modifier = Modifier.fillMaxWidth()) { Text("③ 프로젝트 소스 ZIP 내보내기") }
        Text("현재 상태: $stage", color = MaterialTheme.colorScheme.primary)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (count > 0) {
            Text("저장된 파일: ${count}개")
            Text("실제 APK 빌드와 원본 화면 비교는 아직 연결되지 않았습니다. AI 코드 검토는 컴파일·기기 테스트를 의미하지 않습니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
