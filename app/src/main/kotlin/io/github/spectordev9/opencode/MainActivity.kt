package io.github.spectordev9.opencode

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenCodeShell(
                onOpenLicenses = {
                    startActivity(android.content.Intent(this, LicensesActivity::class.java))
                },
            )
        }
    }
}
