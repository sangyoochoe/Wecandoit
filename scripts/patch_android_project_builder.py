from pathlib import Path
import shutil

root = Path('craft-anything-android/app/src/main/java/ai/storyteller/craftanything')
main = root / 'MainActivity.kt'
s = main.read_text(encoding='utf-8')
shutil.copyfile('scripts/ProjectBuilder.kt', root / 'ProjectBuilder.kt')
shutil.copyfile('scripts/CraftWorkflow.kt', root / 'CraftWorkflow.kt')
shutil.copyfile('scripts/CraftAgentOrchestrator.kt', root / 'CraftAgentOrchestrator.kt')

if 'BUILD("제작"' not in s:
    s = s.replace(
        '    ANALYZE("홈", "⌂"), TOOLS("분석 도구", "⌘"), AI("AI", "✦"), SETTINGS("설정", "⚙")',
        '    ANALYZE("홈", "⌂"), BUILD("제작", "＋"), TOOLS("분석 도구", "⌘"), AI("AI", "✦"), SETTINGS("설정", "⚙")'
    )
if 'MobileTab.BUILD' not in s:
    s = s.replace('listOf(MobileTab.ANALYZE, MobileTab.AI, MobileTab.SETTINGS)',
                  'listOf(MobileTab.ANALYZE, MobileTab.BUILD, MobileTab.AI, MobileTab.SETTINGS)')
    needle = '                MobileTab.AI -> LazyColumn('
    if needle not in s:
        raise SystemExit('AI tab insertion point not found')
    branch = '''                MobileTab.BUILD -> Column(
                    modifier = Modifier.fillMaxSize().padding(padding)
                ) {
                    ProjectBuilderScreen(
                        activity = activity,
                        settings = aiSettings,
                        referenceName = fileLabel,
                        referenceEvidence = analysisContext,
                        onSettings = { activeTab = MobileTab.SETTINGS }
                    )
                }
'''
    s = s.replace(needle, branch + needle, 1)

if 'fun askProject(' not in s:
    anchor = '    fun discoverAiModels(settings: AiSettings, onResult: (Result<List<String>>) -> Unit) {'
    method = '''    fun askProject(settings: AiSettings, prompt: String, onResult: (String) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val system = "You are Craft Anything's Android project builder. Generate complete, safe, independent source projects from user requirements. Return only the exact JSON format requested by the user, never claim that code was compiled or tested unless a build actually ran. Do not execute or copy proprietary target code."
                    AiChatClient(settings, { _, _ -> error("프로젝트 생성에는 분석 도구 호출을 허용하지 않습니다.") }, false)
                        .ask(prompt, "", system)
                }.getOrElse { "AI 프로젝트 생성 실패: ${it.message ?: "알 수 없는 오류"}" }
            }
            onResult(result)
        }
    }

'''
    if anchor not in s:
        raise SystemExit('askProject insertion point not found')
    s = s.replace(anchor, method + anchor, 1)
main.write_text(s, encoding='utf-8')

provider = root / 'AiProvider.kt'
pv = provider.read_text(encoding='utf-8')
if 'systemPrompt: String? = null' not in pv:
    pv = pv.replace('fun ask(userText: String, analysisContext: String): String {',
                    'fun ask(userText: String, analysisContext: String, systemPrompt: String? = null): String {', 1)
if 'AiMessage("system", systemPrompt ?:' not in pv:
    prefix = 'AiMessage("system", "You are Craft Anything'
    if prefix not in pv:
        raise SystemExit('AI system prompt source anchor not found')
    pv = pv.replace(prefix, 'AiMessage("system", systemPrompt ?: "You are Craft Anything', 1)
provider.write_text(pv, encoding='utf-8')
