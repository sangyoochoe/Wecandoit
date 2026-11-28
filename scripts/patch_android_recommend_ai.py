from pathlib import Path

p = Path("craft-anything-android/app/src/main/java/ai/storyteller/craftanything/MainActivity.kt")
s = p.read_text(encoding="utf-8")

if "import androidx.compose.foundation.clickable" not in s:
    s = s.replace("import androidx.compose.foundation.layout.*", "import androidx.compose.foundation.clickable\\nimport androidx.compose.foundation.layout.*", 1)

# Python replacement above must insert a real line break, not a literal backslash-n.
s = s.replace("clickable" + chr(92) + "nimport", "clickable\nimport", 1)
anchor = '    var showAdvancedAi by remember { mutableStateOf(false) }'
if anchor not in s: raise SystemExit("AI settings state anchor not found")
if "var recommendationGoal by remember" not in s:
    s = s.replace(anchor, anchor + '''
    var recommendationGoal by remember { mutableStateOf("보안 위험 확인") }
    var recommendationStatus by remember { mutableStateOf("") }
''', 1)

anchor = '''                MobileTab.AI -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CraftSurface)) {'''
replacement = '''                MobileTab.AI -> LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        val recommendation = when (recommendationGoal) {
                            "보안 위험 확인" -> AiProvider.ANTHROPIC to "보안 이슈를 근거와 불확실성으로 나눠 검토하는 작업에 적합한 선택지입니다."
                            "앱 구조 쉽게 설명" -> AiProvider.OPENAI to "앱 구조 설명과 단계별 질의응답을 시작하기 좋은 기본 선택지입니다."
                            "긴 자료 분석" -> AiProvider.GEMINI to "긴 분석 요약이나 많은 텍스트를 함께 검토할 때 고려할 수 있습니다."
                            else -> AiProvider.QWEN to "비용을 고려한 대안입니다. 실제 요금과 사용 한도는 제공자 정책을 확인하세요."
                        }
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CraftSurface)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("AI 추천", style = MaterialTheme.typography.labelSmall, color = CraftAccent)
                                Text("무엇을 하고 싶으세요?", style = MaterialTheme.typography.titleLarge)
                                Text("목적을 고르면 사용할 AI를 추천하고, 선택하면 설정에 반영합니다.", style = MaterialTheme.typography.bodySmall)
                                listOf("보안 위험 확인", "앱 구조 쉽게 설명", "긴 자료 분석", "비용 절약").forEach { goal ->
                                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                                        RadioButton(selected = recommendationGoal == goal, onClick = { recommendationGoal = goal })
                                        Text(goal, modifier = Modifier.clickable { recommendationGoal = goal }, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                                Text("추천: ${recommendation.first.label}", style = MaterialTheme.typography.titleMedium, color = CraftMint)
                                Text(recommendation.second, style = MaterialTheme.typography.bodySmall)
                                Text("추천 모델: ${recommendation.first.defaultModel}", style = MaterialTheme.typography.bodySmall)
                                Button(onClick = {
                                    aiSettings = AiSettings(provider = recommendation.first, model = recommendation.first.defaultModel,
                                        baseUrl = recommendation.first.defaultBaseUrl, apiKey = "")
                                    SecureAiSettings(activity).save(aiSettings)
                                    recommendationStatus = "${recommendation.first.label} 선택 완료. 설정에서 API 키를 입력하고 저장해 주세요."
                                }, modifier = Modifier.fillMaxWidth()) { Text("이 AI 선택하기") }
                                OutlinedButton(onClick = { activeTab = MobileTab.SETTINGS }, modifier = Modifier.fillMaxWidth()) {
                                    Text("API 키 및 연결 설정")
                                }
                                if (recommendationStatus.isNotBlank()) Text(recommendationStatus, color = CraftMint, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CraftSurface)) {'''
if anchor not in s: raise SystemExit("AI tab insertion anchor not found")
s = s.replace(anchor, replacement, 1)

anchor = '''                                OutlinedTextField(aiQuestion, { aiQuestion = it }, Modifier.fillMaxWidth(),
                                    minLines = 4, label = { Text("무엇을 분석할까요?") })'''
replacement = '''                                Text("처음이라면 아래 질문을 눌러 보세요.", style = MaterialTheme.typography.bodySmall)
                                listOf(
                                    "이 앱이 무슨 일을 하는지 쉽게 설명해줘.",
                                    "위험해 보이는 권한이나 기능을 찾아 근거와 함께 설명해줘.",
                                    "개인정보가 외부로 전송될 가능성을 확인해줘.",
                                    "초보자가 이해할 수 있게 분석 결과를 요약해줘."
                                ).forEach { prompt ->
                                    OutlinedButton(onClick = { aiQuestion = prompt }, modifier = Modifier.fillMaxWidth()) { Text(prompt) }
                                }
                                OutlinedTextField(aiQuestion, { aiQuestion = it }, Modifier.fillMaxWidth(),
                                    minLines = 3, label = { Text("직접 질문하기 (선택)") })'''
if anchor not in s: raise SystemExit("AI question field anchor not found")
s = s.replace(anchor, replacement, 1)

target = 'Text("ON-DEVICE AI / LOCAL MODEL",'
idx = s.find(target)
if idx < 0: raise SystemExit("Local model panel title not found")
start = s.rfind("                    item {", 0, idx)
if start < 0: raise SystemExit("Local model list item not found")
s = s[:start] + s[start:].replace("                    item {", "                    if (advancedMode) item {", 1)
s = s.replace(target, 'Text("고급 기능 / 실험적 로컬 모델",', 1)

s = s.replace(
    "참고: 현재 이 앱 버전에는 GGUF 온디바이스 추론 기능이 포함되어 있지 않습니다. 로컬 AI는 Ollama 서버 연결 방식입니다.",
    "참고: 휴대폰에서 모델을 직접 실행하는 기능은 실험적이며, 지원되는 MediaPipe .task 모델만 대상입니다. 일반적인 AI 사용은 외부 제공자 API 연결이 필요합니다."
)
p.write_text(s, encoding="utf-8")
