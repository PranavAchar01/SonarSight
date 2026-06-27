package com.sixthsense

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.gson.GsonBuilder
import com.sixthsense.core.SceneState
import com.sixthsense.debug.AppGraph
import com.sixthsense.ws.SceneSocket
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Operator / developer console — NOT the end-user interface. The blind user is
 * guided by the belt and voice; this screen exists for development and the demo
 * operator (toggle mock, fire belt tests, watch the live SceneState).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var sceneView: TextView
    private var socket: SceneSocket? = null
    private val gson = GsonBuilder().setPrettyPrinting().create()

    private val requestBt = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        Log.i(TAG, "BT permissions: $result")
        AppGraph.beltClient.connect()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppGraph.init(this)
        setContentView(buildUi())
        observeScene()
        startDashboardSocket()
    }

    private fun buildUi(): ScrollView {
        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        root.addView(TextView(this).apply {
            text = getString(R.string.title)
            textSize = 24f
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.subtitle)
            textSize = 12f
            setPadding(0, 0, 0, pad)
        })

        fun button(label: String, onClick: () -> Unit) = Button(this).apply {
            text = label
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { onClick() }
        }

        root.addView(button(getString(R.string.btn_connect_belt)) { connectBelt() })
        root.addView(button(getString(R.string.btn_mock_on)) {
            AppGraph.mockSceneProducer.setEnabled(true)
        })
        root.addView(button(getString(R.string.btn_mock_off)) {
            AppGraph.mockSceneProducer.setEnabled(false)
        })
        root.addView(button(getString(R.string.btn_test_left)) {
            AppGraph.beltClient.send(byteArrayOf(200.toByte(), 0, 0, 0))
        })
        root.addView(button(getString(R.string.btn_test_center)) {
            AppGraph.beltClient.send(byteArrayOf(0, 200.toByte(), 0, 0))
        })
        root.addView(button(getString(R.string.btn_test_right)) {
            AppGraph.beltClient.send(byteArrayOf(0, 0, 200.toByte(), 0))
        })
        root.addView(button(getString(R.string.btn_ask)) {
            val answer = AppGraph.voiceAgent.ask("what's ahead of me?")
            Log.i(TAG, "Voice answer: $answer")
        })

        sceneView = TextView(this).apply {
            text = getString(R.string.scene_waiting)
            textSize = 13f
            setPadding(0, pad, 0, 0)
            gravity = Gravity.START
        }
        root.addView(sceneView)

        return ScrollView(this).apply { addView(root) }
    }

    private fun connectBelt() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestBt.launch(
                arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            )
        } else {
            requestBt.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    private fun observeScene() {
        lifecycleScope.launch {
            AppGraph.sceneBus.state.collectLatest { scene ->
                sceneView.text = render(scene)
            }
        }
    }

    private fun render(s: SceneState): String {
        val summary = buildString {
            append("mock=${AppGraph.mockSceneProducer.isEnabled()}  ")
            append("belt=${AppGraph.beltClient.isConnected}\n")
            append("zones L/C/R = %.2f / %.2f / %.2f\n".format(s.depth.left, s.depth.center, s.depth.right))
            append("pathClear=${s.pathClear}  conf=%.2f\n".format(s.conf))
            append("belt packet=${s.belt}\n")
            if (s.ocr.present) append("ocr=\"${s.ocr.text}\"\n")
            append("\n")
        }
        return summary + gson.toJson(s)
    }

    private fun startDashboardSocket() {
        socket = SceneSocket(AppGraph.sceneBus).also { it.launch(AppGraph.scope) }
    }

    override fun onDestroy() {
        socket?.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SixthSenseScene"
    }
}
