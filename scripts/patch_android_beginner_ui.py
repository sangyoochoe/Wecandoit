from pathlib import Path

p = Path("craft-anything-android/app/src/main/java/ai/storyteller/craftanything/MainActivity.kt")
s = p.read_text(encoding="utf-8")

s = s.replace(
    '    var providerMenu by remember { mutableStateOf(false) }',
    '    var providerMenu by remember { mutableStateOf(false) }\n    var showAdvancedAi by remember { mutableStateOf(false) }'
)

marker = '''                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CraftSurface)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("PREFERENCES / AI",'''
replacement = '''                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CraftSurface)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("처음 사용하시나요?", style = MaterialTheme.typography.titleLarge)
                                Text("1. 홈에서 APK 또는 DEX 파일 선택\\n2. 분석 시작을 눌러 기기에서 분석\\n3. AI 탭에서 궁금한 점 입력\\n4. 결과를 확인하고 필요한 내용을 복사해 저장하세요.", style = MaterialTheme.typography.bodyMedium)
                                Text("파일 분석은 기기에서 진행됩니다. AI 질문과 분석 요약은 선택한 AI 제공자에게 전송됩니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("참고: 현재 이 앱 버전에는 GGUF 온디바이스 추론 기능이 포함되어 있지 않습니다. 로컬 AI는 Ollama 서버 연결 방식입니다.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CraftSurface)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("PREFERENCES / AI",'''
if marker not in s:
    raise SystemExit("Could not find settings card insertion point")
s = s.replace(marker, replacement, 1)

old = '''                                OutlinedTextField(aiSettings.model, { aiSettings = aiSettings.copy(model = it) },
                                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("모델 ID") })
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {'''
new = '''                                if (aiSettings.provider != AiProvider.OLLAMA && aiSettings.provider != AiProvider.CUSTOM) {
                                    Text("기본 모델: ${aiSettings.model}", style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (showAdvancedAi) {
                                OutlinedTextField(aiSettings.model, { aiSettings = aiSettings.copy(model = it) },
                                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("모델 ID") })
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {'''
if old not in s:
    raise SystemExit("Could not find model fields")
s = s.replace(old, new, 1)

old = '''                                OutlinedTextField(aiSettings.baseUrl, { aiSettings = aiSettings.copy(baseUrl = it) },
                                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("API 주소") })
                                OutlinedTextField(aiSettings.apiKey, { aiSettings = aiSettings.copy(apiKey = it) },
                                    Modifier.fillMaxWidth(), singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    label = { Text(if (aiSettings.provider == AiProvider.OLLAMA) "API 키 (선택)" else "API 키") })
                                Button('''
new = '''                                OutlinedTextField(aiSettings.baseUrl, { aiSettings = aiSettings.copy(baseUrl = it) },
                                    Modifier.fillMaxWidth(), singleLine = true, label = { Text("서버 주소") })
                                }
                                if (aiSettings.provider != AiProvider.OLLAMA) OutlinedTextField(aiSettings.apiKey, { aiSettings = aiSettings.copy(apiKey = it) },
                                    Modifier.fillMaxWidth(), singleLine = true,
                                    visualTransformation = PasswordVisualTransformation(),
                                    label = { Text("API 키") })
                                TextButton(onClick = { showAdvancedAi = !showAdvancedAi }) {
                                    Text(if (showAdvancedAi) "고급 설정 접기" else "고급 설정 (모델·서버 주소)")
                                }
                                Button('''
if old not in s:
    raise SystemExit("Could not find endpoint/key fields")
s = s.replace(old, new, 1)


# Persist settings synchronously and show the actual result.
provider_file = p.parent / "AiProvider.kt"
provider_src = provider_file.read_text(encoding="utf-8")
provider_src = provider_src.replace(
    "    fun save(settings: AiSettings) {",
    "    fun save(settings: AiSettings): Boolean {"
)
provider_src = provider_src.replace(
    "        prefs.edit()",
    "        return prefs.edit()",
    1
)
provider_src = provider_src.replace(
    '            .apply()',
    '            .commit()',
    1
)
provider_file.write_text(provider_src, encoding="utf-8")

s = s.replace(
    '                                        SecureAiSettings(activity).save(aiSettings)\n                                        status = "AI 설정을 안전하게 저장했습니다."',
    '                                        val saved = SecureAiSettings(activity).save(aiSettings)\n                                        status = if (saved) "설정 저장 완료" else "설정 저장 실패. 다시 시도해 주세요."'
)

p.write_text(s, encoding="utf-8")
