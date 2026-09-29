package com.taxi.meter

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.viewmodel.compose.viewModel
import com.taxi.meter.ui.TaxiRoot
import com.taxi.meter.ui.theme.TaxiTheme

class MainActivity : ComponentActivity() {

    private var vm: MainViewModel? = null

    /** Когда приложение ушло в фон; 0 — ещё не уходило. */
    private var leftAtMs = 0L

    /**
     * Разрешение спрашивается не на старте, а когда водитель включает
     * счётчик: до этого момента приложению местоположение не нужно.
     */
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted ->
        if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) vm?.startMeter()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TaxiTheme {
                val model: MainViewModel = viewModel()
                vm = model
                TaxiRoot(
                    vm = model,
                    onRequestLocationPermission = { askLocation() },
                )
            }
        }
    }

    override fun onStop() {
        super.onStop()
        leftAtMs = SystemClock.elapsedRealtime()
    }

    override fun onStart() {
        super.onStart()
        // Процесс мог и не умирать: после долгой паузы заход всё равно
        // считается новым, и тариф выбирается заново.
        if (leftAtMs > 0L) {
            vm?.onReturnedFromBackground(SystemClock.elapsedRealtime() - leftAtMs)
            leftAtMs = 0L
        }
    }

    private fun askLocation() {
        val needed = buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        requestPermissions.launch(needed.toTypedArray())
    }
}
