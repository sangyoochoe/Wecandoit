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


# Restore Kotlin string interpolation in the builder source.
s = s.replace(chr(92) + "$", "$")

p.write_text(s, encoding="utf-8")
