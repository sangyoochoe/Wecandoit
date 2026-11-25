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
        validateAndroidProject(staging)
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
    /** Structural preflight only; this does not claim a successful Gradle build. */
    private fun validateAndroidProject(dir: File) {
        fun required(path: String): String {
            val file = File(dir, path)
            require(file.isFile && file.length() > 0L) { "필수 파일이 없습니다: $path" }
            return file.readText()
        }
        val settings = required("settings.gradle.kts")
        val rootBuild = required("build.gradle.kts")
        val appBuild = required("app/build.gradle.kts")
        val manifest = required("app/src/main/AndroidManifest.xml")
        val launcherIcon = Regex("android:icon\\s*=\\s*\"@mipmap/([A-Za-z0-9_]+)\"").find(manifest)?.groupValues?.get(1)
            ?: error("Manifest의 android:icon이 @mipmap 리소스를 가리키지 않습니다.")
        val iconXmlFile = File(dir, "app/src/main/res/mipmap-anydpi-v26/$launcherIcon.xml")
        require(iconXmlFile.isFile && iconXmlFile.length() > 0L) { "Android adaptive launcher icon XML이 없습니다." }
        val iconXml = iconXmlFile.readText()
        require(Regex("<adaptive-icon\\b").containsMatchIn(iconXml) && Regex("<foreground\\b").containsMatchIn(iconXml) && Regex("<background\\b").containsMatchIn(iconXml)) { "런처 아이콘은 foreground/background를 분리한 adaptive-icon이어야 합니다." }
        val foregroundRef = Regex("android:drawable\\s*=\\s*\"@drawable/([A-Za-z0-9_]+)\"").find(iconXml)?.groupValues?.get(1)
            ?: error("적응형 아이콘 foreground drawable이 없습니다.")
        val foreground = File(dir, "app/src/main/res/drawable/$foregroundRef.xml")
        require(foreground.isFile) { "런처 아이콘 foreground 파일이 없습니다." }
        val foregroundXml = foreground.readText()
        require(Regex("<vector\\b").containsMatchIn(foregroundXml) &&
            Regex("android:viewportWidth=\\"108\\"").containsMatchIn(foregroundXml) &&
            Regex("android:viewportHeight=\\"108\\"").containsMatchIn(foregroundXml) &&
            Regex("<path\\b").containsMatchIn(foregroundXml) &&
            Regex("android:pathData=\\"[^\\"]+\\"").containsMatchIn(foregroundXml)) {
            "런처 아이콘 foreground는 108x108 viewport와 실제 path가 있는 VectorDrawable이어야 합니다."
        }
        val legacyIcon = File(dir, "app/src/main/res/mipmap-anydpi/ic_launcher.xml")
        require(legacyIcon.isFile && Regex("<vector\\b").containsMatchIn(legacyIcon.readText())) {
            "Android 7 이하용 mipmap-anydpi/ic_launcher.xml VectorDrawable fallback이 없습니다."
        }
        val backgroundRef = Regex("<background[^>]*android:drawable\\s*=\\s*\"@color/([A-Za-z0-9_]+)\"").find(iconXml)?.groupValues?.get(1)
            ?: error("적응형 아이콘 background color가 없습니다.")
        val valuesDir = File(dir, "app/src/main/res/values")
        require(valuesDir.listFiles()?.filter { it.extension == "xml" }?.any { Regex("<color\\s+name=\"$backgroundRef\"").containsMatchIn(it.readText()) } == true) { "런처 아이콘 배경색 리소스가 없습니다: $backgroundRef" }
        require(Regex("include\\s*\\(.*app").containsMatchIn(settings)) { "settings.gradle.kts에 app 모듈이 없습니다." }
        require(Regex("com\\.android\\.application").containsMatchIn(rootBuild + "\\n" + appBuild)) { "Android application Gradle 플러그인이 없습니다." }
        require(Regex("compileSdk\\s*(=\\s*)?\\d+").containsMatchIn(appBuild)) { "compileSdk 설정이 없습니다." }
        require(Regex("namespace\\s*=\\s*\"[A-Za-z_][A-Za-z0-9_.]*\"").containsMatchIn(appBuild)) { "유효한 namespace가 없습니다." }
        require(Regex("<manifest\\b").containsMatchIn(manifest) && Regex("<application\\b").containsMatchIn(manifest)) { "Manifest에 manifest/application 선언이 없습니다." }
        require(Regex("<activity[\\s\\S]*?android:name=\"[^\"]+\"").containsMatchIn(manifest)) { "Manifest에 Activity 진입점이 없습니다." }
        require(Regex("<action[^>]+android:name=\"android.intent.action.MAIN\"").containsMatchIn(manifest) && Regex("<category[^>]+android:name=\"android.intent.category.LAUNCHER\"").containsMatchIn(manifest)) { "Manifest에 MAIN/LAUNCHER 실행 인텐트가 없습니다." }
        val sourceRoot = File(dir, "app/src/main")
        val kotlinFiles = sourceRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        require(kotlinFiles.isNotEmpty()) { "app/src/main 아래 Kotlin 소스가 없습니다." }
        require(kotlinFiles.any { it.relativeTo(sourceRoot).invariantSeparatorsPath.startsWith("java/") }) { "app/src/main/java 아래 Kotlin 진입 소스가 없습니다." }
        require(kotlinFiles.none { it.length() == 0L }) { "비어 있는 Kotlin 소스 파일이 있습니다." }
    }
    private fun writeBuildSupport(dir: File) {
        val script = """#!/usr/bin/env bash
set -euo pipefail
if [ ! -f settings.gradle.kts ] || [ ! -f app/build.gradle.kts ]; then
  echo "Android Gradle 프로젝트 파일이 없습니다." >&2
  exit 2
fi
if [ ! -x ./gradlew ]; then
  echo "Gradle wrapper가 없습니다. GitHub Actions 빌드를 사용하세요." >&2
  exit 3
fi
./gradlew --no-daemon assembleDebug
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
      - name: Build and unit-test Android project
        run: ./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest
      - name: Install and launch on Android emulator
        uses: ReactiveCircus/android-emulator-runner@v2
        with:
          api-level: 35
          arch: x86_64
          profile: pixel_2
          script: |
            ./gradlew --no-daemon :app:connectedDebugAndroidTest
            APK="$(find app/build/outputs/apk/debug -maxdepth 1 -name '*.apk' | head -n 1)"
            test -n "${'$'}APK"
            APP_ID="$(grep -E 'applicationId[[:space:]]*=' app/build.gradle.kts | head -n 1 | sed -E 's/.*applicationId[[:space:]]*=[[:space:]]*"([^"]+)".*/\\1/')"
            if [ -z "${'$'}APP_ID" ]; then
              APP_ID="$(grep -E 'namespace[[:space:]]*=' app/build.gradle.kts | head -n 1 | sed -E 's/.*namespace[[:space:]]*=[[:space:]]*"([^"]+)".*/\\1/')"
            fi
            test -n "${'$'}APP_ID"
            "$ANDROID_HOME/build-tools/35.0.0/aapt" dump badging "${'$'}APK" | grep -E "^application:.*icon="
            adb install "${'$'}APK"
            adb shell monkey -p "${'$'}APP_ID" 1
            sleep 8
            adb shell screencap -p /sdcard/craft-anything-smoke.png
            adb pull /sdcard/craft-anything-smoke.png craft-anything-smoke.png
            mkdir -p icon-review
            adb pull "/sdcard/Android/data/$APP_ID/files/icon-review/." icon-review/
      - uses: actions/upload-artifact@v4
        with:
          name: generated-android-validation
          path: |
            app/build/outputs/apk/debug/*.apk
            craft-anything-smoke.png
            icon-review/*.png
          if-no-files-found: error
"""
        val readme = """# Generated Android project
This project was generated from the Craft Anything Android app.
## Build
Run `bash build-apk.sh` in an environment with JDK 17, Android SDK and Gradle.
Or push this project to GitHub and run the workflow in .github/workflows/android.yml.
The workflow runs unit tests, builds a debug APK, installs and launches it in an Android emulator, captures a smoke-test screenshot, and uploads the APK and screenshot as generated-android-validation. This smoke test verifies launch only; it does not prove feature correctness or visual similarity. The build also verifies the packaged launcher icon resource. The connected Android instrumentation test renders the packaged launcher icon at 32dp, 40dp and 48dp and stores PNG previews in icon-review/. These images are for human visual review, not an automatic aesthetic score. Review them under circle, squircle and teardrop masks, on light and dark wallpapers, and on both Galaxy Z Fold cover and unfolded launchers; the CI emulator cannot reproduce Samsung One UI launcher masks exactly.
The app performs a structural preflight of Gradle files, Android plugin, compileSdk, namespace, Manifest launcher activity and Kotlin source. This is not a Gradle build or device test. Only a successful GitHub Actions build proves APK compilation.
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
    val workflow = remember(activity) { CraftWorkflowStore(activity) }
    val orchestrator = remember(activity, workflow, store, settings) {
        CraftAgentOrchestrator(workflow, { p, callback -> activity.askProject(settings, p, callback) }, store::save)
    }
    var request by remember { mutableStateOf("참고 게임의 진행 방식은 참고하되 캐릭터와 배경은 새롭게 만들고 모바일 세로 화면에 맞춰 주세요.") }
    var referenceNotes by remember { mutableStateOf("") }
    var deepAnalysis by remember { mutableStateOf(true) }
    var cleanRoom by remember { mutableStateOf(true) }
    var projectName by remember { mutableStateOf("MyGame") }
    var stage by remember { mutableStateOf("대기 중") }
    var message by remember { mutableStateOf("") }
    var count by remember { mutableIntStateOf(0) }
    var reviewRoundsCompleted by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) { busy = true; activity.lifecycleScope.launch {
            val r = runCatching { store.export(uri) }; busy = false
            stage = if (r.isSuccess) "소스 ZIP 저장 완료" else "저장 실패: ${r.exceptionOrNull()?.message}"
        } }
    }
    fun prompt(refine: Boolean, round: Int = 1): String {
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
개선 반복: $round / 3
이번 점검 목표: 파일 간 패키지명·Manifest·Gradle 플러그인과 의존성·진입 Activity·Compose 사용·리소스 참조를 서로 대조하고, 발견한 문제를 실제 코드에서 고친다. 빌드 로그나 기기 실행 결과가 제공되지 않았다면 컴파일·실행 성공 여부는 미확인으로 남긴다.
아이콘 설계 규칙: 앱 아이콘을 프로젝트마다 기본 템플릿이나 임의의 로봇·별·문자 아이콘으로 재사용하지 마라. Study에서 파악한 앱의 핵심 목적, 주요 사용자 행동, 장르·업무 맥락, 시각적 분위기와 선택한 UI 스타일(색상, 형태 언어, 대비, 모서리, 밀도)을 바탕으로 이 프로젝트만의 아이콘 콘셉트를 먼저 정하고 README에 디자인 근거를 기록하라. 작은 크기에서도 알아볼 수 있는 단일 상징 중심으로 단순화하고 전경과 배경의 대비, adaptive icon 안전 영역을 지켜라. 앱 내부 아이콘도 같은 형태 언어와 선 굵기·모서리 규칙을 따르고 기능 의미와 맞는 아이콘을 사용하라. 외부 상표나 기존 앱 로고를 모방하지 마라. 반드시 app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml(adaptive icon), app/src/main/res/drawable/ic_launcher_foreground.xml(108x108 viewport의 VectorDrawable path), app/src/main/res/values/ic_launcher_background.xml(color resource), app/src/main/res/mipmap-anydpi/ic_launcher.xml(Android 7 이하용 단순 VectorDrawable fallback)을 만들고 Manifest의 android:icon이 해당 아이콘 리소스를 참조하게 하라. 전경의 핵심 상징은 adaptive icon 중앙 안전영역 안에 배치하고 가장자리 잘림을 피하라. README에 아이콘 콘셉트, 상징의 의미, UI 색상·형태 규칙과 연결한 이유를 기록하라. round icon을 별도 지정하면 같은 콘셉트로 제공하라.
아이콘 시각 검증 규칙: app/src/androidTest/java 아래에 AndroidJUnit4 기반 LauncherIconRenderingTest를 추가하라. InstrumentationRegistry.targetContext.packageManager.getApplicationIcon(packageName)으로 실제 패키징된 앱 아이콘을 가져와 Drawable을 Bitmap/Canvas에 32dp, 40dp, 48dp 크기로 각각 렌더링하고 PNG를 context.getExternalFilesDir(null)/icon-review 아래에 저장하라. 테스트는 모든 이미지가 생성되고 알파가 완전히 비어 있지 않은지 확인하라. app/build.gradle.kts에는 testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"와 androidx.test:runner, androidx.test.ext:junit 의존성을 추가하라. README에 캡처 이미지는 자동 미적 평가가 아니라 작은 크기에서의 수동 디자인 검토용이라고 명시하라.
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
        } }
        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("AI 연결 설정") }
        Button(enabled = !busy && request.isNotBlank(), onClick = {
            if (settings.model.isBlank()) { stage = "AI 모델을 설정하세요."; return@Button }
            busy = true
            message = ""
            workflow.begin(referenceName, referenceEvidence + "\\n" + referenceNotes)
            val studyPrompt = """너는 단일 Craft Anything 오케스트레이터가 지휘하는 Study 전문가다.
참고자료와 사용자 요구사항을 섞지 말고 분리해서 분석하라.
참고자료 이름: ${referenceName.ifBlank { "없음" }}
참고자료에서 실제로 관찰된 정보: ${referenceEvidence.take(if (deepAnalysis) 24000 else 10000)}
사용자가 추가한 참고 설명: $referenceNotes
사용자의 새 기능 요구사항(참고자료가 아님): $request
분석 모드: ${if (deepAnalysis) "심층 분석" else "간편 분석"}
클린룸: ${if (cleanRoom) "원본 코드·이미지·상표 복사 금지" else "기능 아이디어만 참고, 복제 금지"}
화면 구조, 핵심 흐름, 입력, 상태, 전환, 콘텐츠 유형, 반응형 특성을 근거별로 정리하라.
확인하지 못한 사실은 미확인으로 남기고 코드나 기능을 추측하지 마라.
다음 Build 단계가 사용할 간결한 요구사항 명세로 작성하라."""
            orchestrator.create(
                studyPrompt = studyPrompt,
                buildPrompt = { notes -> prompt(false) + "\\n\\nStudy 분석 결과(참고자료에서 확인한 근거):\\n" + notes.take(16000) },
                reviewPrompt = { _, notes, round ->
                    prompt(true, round) + "\\n\\n오케스트레이터 Study 결과:\\n" + notes.take(12000)
                },
                reviewRounds = 3,
                onStage = { stage = it },
                onComplete = { result ->
                    busy = false
                    result.onSuccess {
                        count = it.fileCount
                        reviewRoundsCompleted = it.reviewRounds
                        stage = "Study·Build·Check·Refine 완료 · 소스 ${it.fileCount}개 · 실제 빌드 미검증"
                        message = "단일 오케스트레이터가 4단계를 순서대로 진행했습니다. 소스 구조 검사는 통과했지만 Gradle 빌드와 기기 실행은 아직 하지 않았습니다."
                    }.onFailure {
                        stage = "에이전트 실행 실패: ${it.message}"
                        message = it.message ?: "실행 실패"
                    }
                }
            )
        }, modifier = Modifier.fillMaxWidth()) {
            Text(if (busy) "오케스트레이터 실행 중…" else "① Study → Build → Check → Refine 실행")
        }
        OutlinedButton(enabled = !busy && store.exists(), onClick = { exportPicker.launch("${projectName.ifBlank { "MyGame" }}-source.zip") }, modifier = Modifier.fillMaxWidth()) { Text("③ 프로젝트 소스 ZIP 내보내기") }
        Text("현재 상태: $stage", color = MaterialTheme.colorScheme.primary)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (count > 0) {
            Text("저장된 파일: ${count}개 · AI 자체 점검 반복: ${reviewRoundsCompleted}/3")
            Text("AI 자체 점검은 소스 검토이며 실제 컴파일·기기 실행 테스트가 아닙니다. 내보낸 프로젝트는 build-apk.sh 또는 GitHub Actions에서 별도로 빌드해야 합니다.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}
