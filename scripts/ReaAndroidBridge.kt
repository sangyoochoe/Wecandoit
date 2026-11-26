package ai.storyteller.craftanything

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

internal object ReaAndroidBridge {
    fun analyze(context: Context, endpoint: String, token: String, apk: Uri): String {
        require(endpoint.startsWith("http://") || endpoint.startsWith("https://")) { "REA 주소는 http:// 또는 https://로 시작해야 합니다." }
        require(token.isNotBlank()) { "REA 연결 토큰을 입력하세요." }
        val connection = (URL(endpoint.trimEnd('/') + "/analyze").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 260000
            doOutput = true
            setRequestProperty("Content-Type", "application/vnd.android.package-archive")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-Request-Id", UUID.randomUUID().toString())
        }
        try {
            context.contentResolver.openInputStream(apk).use { input ->
                requireNotNull(input) { "선택한 APK를 읽을 수 없습니다." }
                connection.outputStream.use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        total += n
                        require(total <= 80L * 1024 * 1024) { "APK는 80MiB까지 전송할 수 있습니다." }
                        output.write(buffer, 0, n)
                    }
                }
            }
            val code = connection.responseCode
            val body = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(body) }.getOrElse { error("REA 응답을 JSON으로 읽지 못했습니다: HTTP $code") }
            require(code in 200..299 && json.optBoolean("ok")) {
                json.optString("error").ifBlank { "REA 분석 실패 (HTTP $code)" }
            }
            return json.toString(2).take(120000)
        } finally { connection.disconnect() }
    }
}
