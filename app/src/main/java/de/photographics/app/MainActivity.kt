package de.photographics.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.photographics.app.ui.EditorScreen
import de.photographics.app.ui.IntroOverlay
import de.photographics.app.ui.PhotographicsTheme

class MainActivity : ComponentActivity() {
    private val vm: EditorViewModel by viewModels()
    private var automated = false
    /** Nur für Screenshots: Darstellung dieser Sitzung anpassen (wird nicht gespeichert). */
    private var demoLook by androidx.compose.runtime.mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val stored by vm.settings.collectAsStateWithLifecycle()
            // Screenshots: neutrale Arbeitsfläche und breites Ebenen-Panel, ohne die Einstellungen zu ändern
            val demo: (AppSettings) -> AppSettings = { if (demoLook) it.copy(canvasBg = CanvasBg.DARK, layersWidth = 420) else it }
            val settings = demo(stored)
            PhotographicsTheme(settings) {
                androidx.compose.foundation.layout.Box {
                    EditorScreen(vm, demo)
                    // Einführung beim ersten Start; nicht bei Messungen über adb (Benchmark, Rauchtest)
                    if (!settings.introSeen && !automated) IntroOverlay(onDone = { vm.updateSettings { it.copy(introSeen = true) } })
                }
            }
        }
        // Start- und Editorbildschirm decken das ganze Fenster ab: ohne Fensterhintergrund muss die GPU
        // bei jedem Bild eine Vollbildfläche weniger füllen (der Startbildschirm des Systems behält ihn)
        window.setBackgroundDrawable(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("benchmark", false) == true || intent?.getBooleanExtra("selbsttest", false) == true) automated = true
        // Speicher-Rauchtest (./build.sh smoke) – nur bei aktiviertem USB-Debugging
        if (intent?.getBooleanExtra("selbsttest", false) == true) {
            val adb = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
            android.util.Log.i("PG-Selbsttest", if (adb) "Anforderung erhalten" else "Abgelehnt: USB-Debugging ist aus")
            if (adb) vm.runSelfTest()
            return
        }
        // Screenshots mit Beispieldokumenten (tools/screenshots.sh) – nur bei aktiviertem USB-Debugging
        intent?.getStringExtra("demo")?.let { name ->
            val adb = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
            if (adb) {
                automated = true
                demoLook = true
                vm.openDemo(java.io.File(getExternalFilesDir(null), name), intent.getStringExtra("tool"), intent.getStringExtra("panel"))
            }
            return
        }
        // Bedien-Benchmark (tools/device_bench.sh) – nur bei aktiviertem USB-Debugging
        if (intent?.getBooleanExtra("benchmark", false) == true) {
            val adb = android.provider.Settings.Global.getInt(contentResolver, android.provider.Settings.Global.ADB_ENABLED, 0) == 1
            if (adb) {
                // Bildschirm anlassen: im Ruhezustand drosselt Android die App und verfälscht die Messung
                window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                FrameStats.attach(window)
                FrameStats.skip = intent.getIntExtra("skip", 0)
                vm.runBenchmark(intent.getIntExtra("megapixel", 24))
            }
            return
        }
        if (intent?.action == Intent.ACTION_VIEW || intent?.action == Intent.ACTION_EDIT) {
            intent.data?.let { vm.open(it) }
        }
    }
}
