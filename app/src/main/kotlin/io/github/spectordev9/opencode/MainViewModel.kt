package io.github.spectordev9.opencode

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val runtime: AppRuntime = AppRuntime.obtain(application.applicationContext, appScope)
}
