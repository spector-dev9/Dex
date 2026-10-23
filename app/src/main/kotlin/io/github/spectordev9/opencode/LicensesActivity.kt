package io.github.spectordev9.opencode

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView

class LicensesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.licenses_title)
        setContentView(
            ScrollView(this).apply {
                addView(
                    TextView(this@LicensesActivity).apply {
                        text = readLicenses()
                        typeface = Typeface.MONOSPACE
                        textSize = 12f
                        setTextIsSelectable(true)
                        setPadding(dp(24), dp(24), dp(24), dp(24))
                    },
                )
            },
        )
    }

    private fun readLicenses(): String = buildString {
        append(readAsset("licenses/NOTICES.md"))
        append("\n\n")
        append(readAsset("licenses/SOURCE-OFFER.md"))
        val names = assets.list("licenses/texts").orEmpty()
            .filter { it.endsWith(".txt") || it.endsWith(".md") }
            .sorted()
        for (name in names) {
            append("\n\n")
            append(name)
            append("\n")
            append("=".repeat(name.length))
            append("\n\n")
            append(readAsset("licenses/texts/$name"))
        }
    }

    private fun readAsset(path: String): String =
        assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
