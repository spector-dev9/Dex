package io.github.spectordev9.opencode

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(32), dp(32), dp(32), dp(32))
                addView(
                    TextView(this@MainActivity).apply {
                        text = getString(R.string.app_status)
                        gravity = Gravity.CENTER
                    },
                )
                addView(
                    Button(this@MainActivity).apply {
                        text = getString(R.string.open_source_licenses)
                        setOnClickListener {
                            startActivity(
                                Intent(this@MainActivity, LicensesActivity::class.java),
                            )
                        }
                    },
                )
            },
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
