from pathlib import Path

root = Path("craft-anything-android")
app = root / "app"
gradle = app / "build.gradle.kts"
s = gradle.read_text(encoding="utf-8")
dep = '    implementation("com.google.mediapipe:tasks-genai:0.10.24")'
if dep not in s:
    s = s.replace('    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")',
                  '    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")\n' + dep)
gradle.write_text(s, encoding="utf-8")

src = app / "src/main/java/ai/storyteller/craftanything/LocalTaskModel.kt"
src.write_text(r'''package ai.storyteller.craftanything

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import java.io.File

/**
 * Runs a user-imported MediaPipe LLM task bundle fully on-device.
 * This adapter intentionally accepts only .task bundles; GGUF and .litertlm
 * belong to different runtimes and are not passed to this API.
 */
class LocalTaskModel(private val context: Context) {
    private var inference: LlmInference? = null
    private var activePath: String? = null

    fun installedModel(): File? {
        val file = File(context.filesDir, "local-model.task")
        return file.takeIf { it.isFile && it.length() > 0L }
    }

    fun load(modelFile: File) {
        require(modelFile.isFile && modelFile.length() > 0L) { "모델 파일이 비어 있습니다." }
        require(modelFile.extension.equals("task", ignoreCase = true)) {
            ".task 파일만 MediaPipe 엔진에서 열 수 있습니다. GGUF와 .litertlm은 다른 엔진이 필요합니다."
        }
        if (activePath == modelFile.absolutePath && inference != null) return
        close()
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelFile.absolutePath)
            .setMaxTokens(1024)
            .build()
        inference = LlmInference.createFromOptions(context, options)
        activePath = modelFile.absolutePath
    }

    fun generate(prompt: String): String {
        require(prompt.isNotBlank()) { "질문을 입력하세요." }
        val engine = inference ?: error("먼저 로컬 모델을 불러오세요.")
        return engine.generateResponse(prompt)
    }

    fun close() {
        runCatching { inference?.close() }
        inference = null
        activePath = null
    }
}
''', encoding="utf-8")

main = app / "src/main/java/ai/storyteller/craftanything/MainActivity.kt"
s = main.read_text(encoding="utf-8")
if "import java.io.File" not in s:\n    s = s.replace("import java.io.ByteArrayOutputStream", "import java.io.ByteArrayOutputStream\\nimport java.io.File")\nif "private val localTaskModel by lazy" not in s:
    s = s.replace(
        'class MainActivity : ComponentActivity() {',
        'class MainActivity : ComponentActivity() {\n    private val localTaskModel by lazy { LocalTaskModel(this) }'
    )
# Import and run model file in app-private storage. Never read a large model into a byte array.
if "suspend fun importLocalTaskModel" not in s:
    anchor = '    private suspend fun readSelectedBytes(uri: Uri): Pair<String, ByteArray> ='
    methods = '''    suspend fun importLocalTaskModel(uri: Uri): File = withContext(Dispatchers.IO) {
        val name = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "model.task"
        require(name.endsWith(".task", true)) { "MediaPipe 모델(.task) 파일을 선택하세요." }
        val target = File(filesDir, "local-model.task")
        val temp = File(filesDir, "local-model.task.part")
        contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().buffered().use { output ->
                val buffer = ByteArray(1024 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    require(total <= 8L * 1024 * 1024 * 1024) { "모델 파일은 8GB 이하만 가져올 수 있습니다." }
                    output.write(buffer, 0, n)
                }
                require(total > 0L) { "빈 모델 파일입니다." }
            }
        } ?: error("모델 파일을 열 수 없습니다.")
        check(temp.renameTo(target)) { "모델 파일을 앱 저장소로 옮기지 못했습니다." }
        target
    }

    fun runLocalTaskModel(prompt: String, onResult: (Result<String>) -> Unit) {
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val model = localTaskModel.installedModel() ?: error("먼저 .task 모델 파일을 가져오세요.")
                    localTaskModel.load(model)
                    localTaskModel.generate(prompt)
                }
            }
            onResult(result)
        }
    }

    override fun onDestroy() {
        localTaskModel.close()
        super.onDestroy()
    }

'''
    if anchor not in s:
        raise SystemExit("MainActivity insertion anchor missing")
    s = s.replace(anchor, methods + anchor, 1)

# Compose state and SAF picker for the local model.
needle = '    var discoveringModels by remember { mutableStateOf(false) }'
insert = '''    var discoveringModels by remember { mutableStateOf(false) }
    var localModelStatus by remember { mutableStateOf("이 기기에 설치된 로컬 모델을 확인하세요.") }
    var localModelPrompt by remember { mutableStateOf("안녕하세요. 한국어로 간단히 자기소개해 주세요.") }
    var localModelAnswer by remember { mutableStateOf("") }
    var localModelBusy by remember { mutableStateOf(false) }
    var localModelInstalled by remember { mutableStateOf(false) }
    val localModelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            localModelBusy = true
            localModelStatus = "모델 파일을 앱 저장소로 복사하는 중…"
            activity.lifecycleScope.launch {
                val result = runCatching { activity.importLocalTaskModel(uri) }
                localModelBusy = false
                result.onSuccess {
                    localModelInstalled = true
                    localModelStatus = "모델 가져오기 완료: ${it.name} · ${it.length() / (1024 * 1024)} MiB"
                }.onFailure {
                    localModelStatus = "모델 가져오기 실패: ${it.message}"
                }
            }
        }
    }'''
if needle in s and "localModelPicker =" not in s:
    s = s.replace(needle, insert, 1)

# Add local model panel to AI tab after its existing card, before the answer section.
anchor = '''                    if (aiAnswer.isNotBlank()) {
                        item {'''
panel = '''                    item {
                        Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = CraftSurface)) {
                            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("ON-DEVICE AI / LOCAL MODEL", style = MaterialTheme.typography.labelSmall, color = CraftAccent)
                                Text("내 기기에서 AI 실행", style = MaterialTheme.typography.titleLarge)
                                Text("모델 파일을 앱 안으로 가져오면 네트워크 없이 실행합니다. 현재 이 엔진은 MediaPipe .task 형식만 지원합니다. 모델은 APK에 포함되지 않으며 앱 내부 저장소에 복사됩니다.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { localModelPicker.launch(arrayOf("*/*")) },
                                    enabled = !localModelBusy, modifier = Modifier.fillMaxWidth()) {
                                    Text("기기에서 .task 모델 가져오기")
                                }
                                Text(localModelStatus, style = MaterialTheme.typography.bodySmall)
                                OutlinedTextField(localModelPrompt, { localModelPrompt = it },
                                    Modifier.fillMaxWidth(), minLines = 2, label = { Text("로컬 모델에게 질문") })
                                Button(onClick = {
                                    localModelBusy = true
                                    localModelAnswer = ""
                                    localModelStatus = "모델 로딩 및 추론 중… 첫 실행은 시간이 걸릴 수 있습니다."
                                    activity.runLocalTaskModel(localModelPrompt) { result ->
                                        localModelBusy = false
                                        result.onSuccess {
                                            localModelAnswer = it
                                            localModelStatus = "기기 내 추론 완료 · 네트워크 전송 없음"
                                        }.onFailure {
                                            localModelStatus = "실행 실패: ${it.message}"
                                        }
                                    }
                                }, enabled = localModelPrompt.isNotBlank() && !localModelBusy,
                                    modifier = Modifier.fillMaxWidth()) {
                                    Text(if (localModelBusy) "로컬 AI 실행 중…" else "인터넷 없이 실행")
                                }
                                if (localModelAnswer.isNotBlank()) Text(localModelAnswer, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                    if (aiAnswer.isNotBlank()) {
                        item {'''
if anchor in s and "ON-DEVICE AI / LOCAL MODEL" not in s:
    s = s.replace(anchor, panel, 1)
main.write_text(s, encoding="utf-8")
