from pathlib import Path

p = Path("craft-anything-android/app/src/main/java/ai/storyteller/craftanything/ProjectBuilder.kt")
s = p.read_text(encoding="utf-8")

imports = """import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
"""
if "import android.graphics.BitmapFactory" not in s:
    s = s.replace("import android.net.Uri\n", "import android.net.Uri\n" + imports)

helper = r'''
private suspend fun compareProjectScreenshots(activity: MainActivity, reference: Uri, result: Uri): Int =
    withContext(Dispatchers.IO) {
        fun decode(uri: Uri): Bitmap {
            val source = activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                ?: error("이미지를 읽을 수 없습니다.")
            val scaled = Bitmap.createScaledBitmap(source, 48, 48, true)
            if (scaled !== source) source.recycle()
            return scaled
        }
        val a = decode(reference)
        val b = try { decode(result) } catch (e: Exception) { a.recycle(); throw e }
        try {
            var diff = 0L
            for (y in 0 until 48) for (x in 0 until 48) {
                val ca = a.getPixel(x, y); val cb = b.getPixel(x, y)
                diff += kotlin.math.abs(android.graphics.Color.red(ca) - android.graphics.Color.red(cb))
                diff += kotlin.math.abs(android.graphics.Color.green(ca) - android.graphics.Color.green(cb))
                diff += kotlin.math.abs(android.graphics.Color.blue(ca) - android.graphics.Color.blue(cb))
            }
            (100.0 * (1.0 - diff.toDouble() / (48 * 48 * 3 * 255))).toInt().coerceIn(0, 100)
        } finally { a.recycle(); b.recycle() }
    }

'''
if "private suspend fun compareProjectScreenshots" not in s:
    s = s.replace("@Composable\ninternal fun ProjectBuilderScreen", helper + "@Composable\ninternal fun ProjectBuilderScreen", 1)

state_anchor = '    var busy by remember { mutableStateOf(false) }\n'
state_add = '''    var referenceShot by remember { mutableStateOf<Uri?>(null) }
    var resultShot by remember { mutableStateOf<Uri?>(null) }
    var visualScore by remember { mutableStateOf<Int?>(null) }
    var compareError by remember { mutableStateOf("") }
    val referenceShotPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { referenceShot = it; visualScore = null }
    val resultShotPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { resultShot = it; visualScore = null }
'''
if "var referenceShot by remember" not in s:
    s = s.replace(state_anchor, state_anchor + state_add, 1)

if '시각 비교 점수' not in s:
    ui_anchor = '        OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("AI 연결 설정") }\n'
    ui_add = '''        Text("화면 비교", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { referenceShotPicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                Text(if (referenceShot == null) "원본 화면 선택" else "원본 화면 변경")
            }
            OutlinedButton(onClick = { resultShotPicker.launch("image/*") }, modifier = Modifier.weight(1f)) {
                Text(if (resultShot == null) "새 게임 화면 선택" else "새 화면 변경")
            }
        }
        Button(enabled = !busy && referenceShot != null && resultShot != null, onClick = {
            busy = true; compareError = ""; stage = "두 화면의 색상·배치를 비교하는 중…"
            activity.lifecycleScope.launch {
                val result = runCatching { compareProjectScreenshots(activity, referenceShot!!, resultShot!!) }
                busy = false
                result.onSuccess { visualScore = it; stage = "화면 비교 완료 · 유사도 $it%" }
                    .onFailure { compareError = it.message ?: "비교 실패"; stage = "화면 비교 실패" }
            }
        }, modifier = Modifier.fillMaxWidth()) { Text("④ 두 화면 비교하기") }
        if (visualScore != null) {
            Text("시각 비교 점수: $visualScore%", style = MaterialTheme.typography.titleMedium)
            Text("이 점수는 축소 이미지의 픽셀 색상 유사도이며, 게임 기능이나 동작의 동일성을 뜻하지 않습니다.", style = MaterialTheme.typography.bodySmall)
        }
        if (compareError.isNotBlank()) Text(compareError, color = MaterialTheme.colorScheme.error)
'''
    if ui_anchor not in s: raise SystemExit("UI insertion anchor missing")
    s = s.replace(ui_anchor, ui_anchor + ui_add, 1)

s = s.replace(
    '기존 프로젝트: \${existing.take(30000)}',
    '기존 프로젝트: \${existing.take(30000)}\n화면 비교 결과: \${visualScore?.let { "픽셀 유사도 $it%" } ?: "아직 비교하지 않음"}'
)
s = s.replace(
    'README.md를 포함한다.',
    'README.md, build-apk.sh, .github/workflows/android.yml을 포함한다. build-apk.sh는 Android SDK와 Gradle이 설치된 환경에서 gradle :app:assembleDebug를 실행하고 APK 위치를 출력한다. GitHub Actions workflow는 push 시 assembleDebug를 실행하고 APK를 artifact로 업로드한다.'
)
s = s.replace(
    '실제 APK 빌드와 원본 화면 비교는 아직 연결되지 않았습니다. AI 코드 검토는 컴파일·기기 테스트를 의미하지 않습니다.',
    '화면 비교는 사용자가 선택한 두 스크린샷의 픽셀 유사도입니다. APK는 내보낸 프로젝트의 build-apk.sh 또는 GitHub Actions에서 실제 빌드해야 하며, 이 화면의 AI 검토만으로 빌드 성공을 의미하지 않습니다.'
)
p.write_text(s, encoding="utf-8")


# Kotlin interpolation must remain active in source strings.
s = s.replace(r"\\\$", "$")

# Always include reproducible APK build instructions in the exported project.
build_script = """#!/usr/bin/env bash
set -euo pipefail
if [ -z "${ANDROID_HOME:-}${ANDROID_SDK_ROOT:-}" ]; then
  echo "Android SDK not found. Install Android Studio / Android SDK first." >&2
  exit 2
fi
if ! command -v gradle >/dev/null 2>&1; then
  echo "Gradle 8.9+ is required. Install Gradle or open this project in Android Studio." >&2
  exit 2
fi
gradle --no-daemon :app:assembleDebug
echo "APK: app/build/outputs/apk/debug/app-debug.apk"
"""
s = s.replace(
    '        File(root, "CRAFT_PROJECT.json").writeText(JSONObject().put("name", json.optString("name", "MyGame")).toString(2))',
    '        File(root, "CRAFT_PROJECT.json").writeText(JSONObject().put("name", json.optString("name", "MyGame")).toString(2))\n'
    '        File(root, "build-apk.sh").writeText(' + '"""' + build_script + '"""' + ')\n'
    '        File(root, "build-apk.sh").setExecutable(true)\n'
    '        val workflow = File(root, ".github/workflows/android.yml"); workflow.parentFile?.mkdirs()\n'
    '        workflow.writeText(' + '"""' + """name: Build Android APK
on:
  push:
  workflow_dispatch:
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
          java-version: "17"
      - uses: android-actions/setup-android@v3
      - uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: "8.9"
      - name: Build debug APK
        run: gradle --no-daemon :app:assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: generated-android-apk
          path: app/build/outputs/apk/debug/*.apk
          if-no-files-found: error
"""' + '"""' + ')',
    1
)
# Reject malformed relative paths and ensure all output remains under the staging folder.
s = s.replace(
    'require(path.isNotBlank() && !path.startsWith(\'/\') && path.split(\'/\').none { it == ".." || it.isBlank() }) { "잘못된 경로: $path" }',
    'require(path.isNotBlank() && !path.startsWith(\'/\') && !path.contains(\':\') && path.split(\'/\').none { it == ".." || it == "." || it.isBlank() }) { "잘못된 경로: $path" }\n'
    '            val safeOut = File(staging, path)\n'
    '            require(safeOut.canonicalPath.startsWith(staging.canonicalPath + File.separator)) { "허용되지 않은 저장 경로: $path" }'
)
# The output file is already constructed below; reuse the validated safe path.
s = s.replace('val out = File(staging, path); out.parentFile?.mkdirs(); out.writeText(body)',
              'val out = safeOut; out.parentFile?.mkdirs(); out.writeText(body)')
