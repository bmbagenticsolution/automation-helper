package com.automation.helper

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.method.ScrollingMovementMethod
import android.view.accessibility.AccessibilityManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.automation.helper.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val executor by lazy { CommandExecutor(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.outputView.movementMethod = ScrollingMovementMethod()

        binding.btnEnableAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            } else {
                toast("Overlay permission already granted")
            }
        }

        binding.btnStartBubble.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                toast("Grant overlay permission first")
                return@setOnClickListener
            }
            startService(Intent(this, FloatingBubbleService::class.java))
            toast("Floating bubble started — open your test app")
        }

        binding.btnStopBubble.setOnClickListener {
            stopService(Intent(this, FloatingBubbleService::class.java))
            toast("Floating bubble stopped")
        }

        binding.btnRun.setOnClickListener {
            val script = binding.scriptInput.text.toString()
            if (script.isBlank()) { toast("Type some commands first"); return@setOnClickListener }
            binding.outputView.text = ""
            executor.runScript(
                script = script,
                onLine = { i, line, result ->
                    runOnUiThread {
                        binding.outputView.append("[$i] $line\n    -> ${result.message}\n\n")
                    }
                },
                onDone = {
                    runOnUiThread { binding.outputView.append("--- script finished ---\n") }
                }
            )
        }

        // Quick-fill the input with an example so the user can see the format.
        if (binding.scriptInput.text.isNullOrBlank()) {
            binding.scriptInput.setText(
                """
                # Example: warm up like a human, then tap Login, type, submit
                human on
                human normal
                browse 20
                waitfor "Login" 10
                tap "Login"
                type "myusername"
                tap "Password"
                type "mypassword"
                tap "Submit"
                """.trimIndent()
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = isAccessibilityEnabled()
        val overlay = Settings.canDrawOverlays(this)
        val sb = StringBuilder()
        sb.append("Accessibility service: ")
            .append(if (enabled) "ON" else "OFF (tap Enable Accessibility)")
            .append('\n')
        sb.append("Overlay permission: ")
            .append(if (overlay) "GRANTED" else "NOT GRANTED")
            .append('\n')
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            sb.append("Notifications permission: optional but recommended\n")
        }
        binding.statusView.text = sb.toString()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val target = "$packageName/$packageName.AutomationAccessibilityService"
        return am.getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.id.equals(target, ignoreCase = true) }
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
