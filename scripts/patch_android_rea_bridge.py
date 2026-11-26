from pathlib import Path
import shutil
root = Path("craft-anything-android/app/src/main/java/ai/storyteller/craftanything")
shutil.copyfile("scripts/ReaAndroidBridge.kt", root / "ReaAndroidBridge.kt")
builder = root / "ProjectBuilder.kt"
s = builder.read_text(encoding="utf-8")
if "var reaEndpoint by remember" not in s:
    s = s.replace(
        '    var referenceNotes by remember { mutableStateOf("") }',
        '''    var referenceNotes by remember { mutableStateOf("") }
    var reaEndpoint by remember { mutableStateOf("http://192.168.0.10:8765") }
    var reaToken by remember { mutableStateOf("") }
    var reaStatus by remember { mutableStateOf("REA 연결 안 됨") }
    var reaEvidence by remember { mutableStateOf("") }
    var selectedApk by remember { mutableStateOf<Uri?>(null) }
    val reaApkPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        selectedApk = uri
        if (uri != null) reaStatus = "APK 선택됨 · REA 정적 분석 대기"
    }'''
    )
    anchor = '        Text("② 참고자료에 대한 내 설명", style = MaterialTheme.typography.labelLarge)'
    panel = '''        Card(shape = RoundedCornerShape(16.dp)) { Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("REA Android 역분석 연결", style = MaterialTheme.typography.titleMedium)
            Text("PC에서 REA 브리지를 실행한 뒤 APK를 보내 Manifest·권한·클래스·메서드 근거를 분석합니다. APK를 설치하거나 실행하지 않습니다.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(reaEndpoint, { reaEndpoint = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("REA 호스트 주소") })
            OutlinedTextField(reaToken, { reaToken = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("연결 토큰") })
            OutlinedButton(onClick = { reaApkPicker.launch("application/vnd.android.package-archive") }, modifier = Modifier.fillMaxWidth()) {
                Text(if (selectedApk == null) "분석할 APK 선택" else "APK 다시 선택")
            }
            Button(enabled = !busy && selectedApk != null && reaToken.isNotBlank(), onClick = {
                val apk = selectedApk ?: return@Button
                busy = true
                reaStatus = "REA로 APK 전송 및 정적 분석 중…"
                activity.lifecycleScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { ReaAndroidBridge.analyze(activity, reaEndpoint, reaToken, apk) }
                    }
                    busy = false
                    result.onSuccess {
                        reaEvidence = it
                        reaStatus = "REA 정적 분석 완료 · 근거 ${it.length}자"
                    }.onFailure { reaStatus = "REA 분석 실패: ${it.message}" }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text("REA APK 정적 분석 실행") }
            Text(reaStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        } }
'''
    if anchor not in s:
        raise SystemExit("REA panel insertion point missing")
    s = s.replace(anchor, panel + anchor, 1)
    s = s.replace('${referenceEvidence.take(if (deepAnalysis) 24000 else 10000)}',
                  '${(referenceEvidence + "\\n" + reaEvidence).take(if (deepAnalysis) 36000 else 12000)}')
    builder.write_text(s, encoding="utf-8")
manifest = Path("craft-anything-android/app/src/main/AndroidManifest.xml")
m = manifest.read_text(encoding="utf-8")
if "android.permission.INTERNET" not in m:
    m = m.replace("<application", '<uses-permission android:name="android.permission.INTERNET" />\n    <application', 1)
if "android:usesCleartextTraffic" not in m:
    m = m.replace("<application", '<application android:usesCleartextTraffic="true"', 1)
manifest.write_text(m, encoding="utf-8")
