package io.github.spectordev9.opencode.g3client

import android.app.Activity
import android.content.ContentValues
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private lateinit var portField: EditText
    private lateinit var secretField: EditText
    private lateinit var directoryField: EditText
    private lateinit var resultView: TextView
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        val title = TextView(this).apply {
            text = "G3 SEC-1 second-UID client"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        }
        root.addView(title)
        root.addView(label("Calls every documented harness route as a different UID. The harness server must be started from the G3 diagnostics screen."))
        portField = field("port", "45123", InputType.TYPE_CLASS_NUMBER)
        secretField = field("secret", "g3-test-secret-2026", InputType.TYPE_CLASS_TEXT)
        directoryField = field(
            "directory",
            "/data/user/0/io.github.spectordev9.opencode.spike.debug/files/g3/server/workspace",
            InputType.TYPE_CLASS_TEXT,
        )
        root.addView(button("Run SEC-1 matrix") { runMatrix() })
        statusView = TextView(this).apply {
            textSize = 14f
            setPadding(0, 16, 0, 8)
        }
        root.addView(statusView)
        resultView = TextView(this).apply {
            textSize = 12f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(resultView)
    }

    private fun runMatrix() {
        val port = portField.text.toString().trim().toIntOrNull()
        val secret = secretField.text.toString()
        val directory = directoryField.text.toString().trim()
        if (port == null || secret.isBlank() || directory.isBlank()) {
            statusView.text = "port, secret and directory are required"
            return
        }
        statusView.text = "running"
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Thread {
            val results = CopyOnWriteArrayList<Result>()
            val routes = listOf("/global/health", "/doc", "/project", "/session", "/pty", "/event")
            val encodedDirectory = URLEncoder.encode(directory, StandardCharsets.UTF_8.name())
            val cases = listOf(
                Case("no-auth", null),
                Case("basic", basic(secret)),
                Case("basic-empty-user", basic(secret, "")),
                Case("bearer", "Bearer $secret"),
                Case("wrong-password", basic("wrong")),
            )
            routes.forEach { route ->
                cases.forEach { authCase ->
                    val url = buildString {
                        append("http://127.0.0.1:").append(port).append(route)
                        if (route != "/global/health" && route != "/doc") {
                            append(if (route.contains('?')) "&" else "?").append("directory=").append(encodedDirectory)
                        }
                    }
                    val expectRejected = authCase.label != "basic"
                    val (status, error) = request(url, authCase.header, route == "/event")
                    val ok = if (expectRejected) {
                        status == 401
                    } else {
                        status == 200 || (status != 401 && status != 403)
                    }
                    results += Result(route, authCase.label, status, ok, error)
                    runOnUiThread {
                        resultView.text = results.joinToString("\n") {
                            "${if (it.ok) "PASS" else "FAIL"} ${it.status} ${it.case} ${it.route}" +
                                (it.error?.let { errorMessage -> " ($errorMessage)" } ?: "")
                        }
                    }
                }
            }
            val rejected = results.count { it.case != "basic" && it.status == 401 }
            val accepted = results.count { it.case == "basic" && it.status == 200 }
            val pass = results.all { it.ok } && rejected == routes.size * 4
            val json = JSONObject()
                .put("check", "SEC-1")
                .put("generatedAt", SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(Date()))
                .put("host", "127.0.0.1")
                .put("port", port)
                .put("directory", directory)
                .put("clientUid", android.os.Process.myUid())
                .put("pass", pass)
                .put("results", JSONArray(results.map {
                    JSONObject()
                        .put("route", it.route)
                        .put("case", it.case)
                        .put("status", it.status)
                        .put("ok", it.ok)
                        .put("error", it.error)
                }))
            val text = json.toString(2)
            val file = writeReport(text)
            runOnUiThread {
                statusView.text = if (pass) {
                    "PASS: $rejected/24 rejections, $accepted correct-auth 200s"
                } else {
                    "FAIL: $rejected/24 rejections, $accepted correct-auth 200s"
                }
                resultView.text = resultView.text.toString() + "\n\nreport: ${file.absolutePath}"
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }.apply {
            name = "sec1-matrix"
            isDaemon = true
            start()
        }
    }

    private fun request(url: String, authorization: String?, sse: Boolean): Pair<Int, String?> {
        val connection = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (t: Throwable) {
            return 0 to (t.message ?: t.javaClass.simpleName)
        }
        return try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 5_000
            connection.readTimeout = if (sse) 1_500 else 5_000
            connection.setRequestProperty("Accept", if (sse) "text/event-stream" else "application/json")
            if (authorization != null) {
                connection.setRequestProperty("Authorization", authorization)
            }
            val status = connection.responseCode
            if (sse) {
                status to null
            } else {
                val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                status to stream?.bufferedReader()?.use { it.readText().take(200) }
            }
        } catch (t: Throwable) {
            0 to (t.message ?: t.javaClass.simpleName)
        } finally {
            connection.disconnect()
        }
    }

    private fun writeReport(text: String): File {
        val reports = File(filesDir, "reports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(reports, "sec1-$stamp.json")
        file.writeText(text)
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/OpenCodeG3Client")
                }
                val uri: Uri? = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { output -> output.write(text.toByteArray()) }
                }
            }
        }
        return file
    }

    private fun basic(secret: String, user: String = "opencode"): String =
        "Basic " + Base64.getEncoder().encodeToString("$user:$secret".toByteArray(StandardCharsets.UTF_8))

    private fun label(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, 16, 0, 8)
    }

    private fun field(label: String, value: String, inputType: Int): EditText =
        EditText(this).apply {
            hint = label
            setText(value)
            this.inputType = inputType
            setSingleLine(true)
        }

    private fun button(text: String, action: () -> Unit): Button = Button(this).apply {
        this.text = text
        setOnClickListener { action() }
    }

    private data class Case(val label: String, val header: String?)
    private data class Result(
        val route: String,
        val case: String,
        val status: Int,
        val ok: Boolean,
        val error: String?,
    )
}
