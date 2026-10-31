package com.aura.launcher

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableIntStateOf
import com.aura.launcher.data.AppGraph
import com.aura.launcher.ui.LauncherRoot

class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph
    private val homeSignal = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        graph = AppGraph(this)
        graph.apps.start()
        setContent {
            LauncherRoot(
                graph = graph,
                homeSignal = homeSignal.intValue,
                onRequestStoragePermission = ::requestStorage,
            )
        }
    }

    /** 按遥控器"主页"键时,系统会把 HOME 意图重新投递到这里。 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.hasCategory(Intent.CATEGORY_HOME)) homeSignal.intValue++
    }

    override fun onResume() {
        super.onResume()
        graph.resumeTick.value++
    }

    override fun onDestroy() {
        graph.apps.stop()
        super.onDestroy()
    }

    private fun requestStorage() {
        if (Build.VERSION.SDK_INT < 30) {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        graph.resumeTick.value++
    }
}
