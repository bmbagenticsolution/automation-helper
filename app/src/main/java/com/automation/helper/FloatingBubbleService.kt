package com.automation.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.core.app.NotificationCompat
import kotlin.math.abs

/**
 * A foreground service that draws a small movable bubble on top of every other
 * app. Tapping the bubble pops up a command box. Issuing a command from there
 * runs it through the AccessibilityService against whatever app is currently
 * in the foreground (NOT this app).
 */
class FloatingBubbleService : Service() {

    private lateinit var wm: WindowManager
    private var bubbleView: View? = null
    private var panelView: View? = null
    private val executor by lazy { CommandExecutor(applicationContext) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        startInForeground()
        showBubble()
    }

    override fun onDestroy() {
        super.onDestroy()
        bubbleView?.let { runCatching { wm.removeView(it) } }
        panelView?.let { runCatching { wm.removeView(it) } }
        bubbleView = null
        panelView = null
    }

    private fun showBubble() {
        val view = LayoutInflater.from(this).inflate(R.layout.floating_bubble, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 240
        }

        view.findViewById<ImageView>(R.id.bubble_icon).setOnTouchListener(makeDragListener(view, params))
        wm.addView(view, params)
        bubbleView = view
    }

    /**
     * Drag-or-tap detector. If the touch moves more than ~10px, treat it as a
     * drag. If it stays put, treat it as a tap and open the command panel.
     */
    private fun makeDragListener(view: View, params: WindowManager.LayoutParams) =
        object : View.OnTouchListener {
            private var initialX = 0; private var initialY = 0
            private var touchX = 0f; private var touchY = 0f
            private var dragged = false

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x; initialY = params.y
                        touchX = event.rawX; touchY = event.rawY
                        dragged = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = event.rawX - touchX
                        val dy = event.rawY - touchY
                        if (abs(dx) > 10 || abs(dy) > 10) dragged = true
                        params.x = initialX + dx.toInt()
                        params.y = initialY + dy.toInt()
                        runCatching { wm.updateViewLayout(view, params) }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragged) togglePanel()
                    }
                }
                return true
            }
        }

    private fun togglePanel() {
        if (panelView != null) { hidePanel(); return }
        showPanel()
    }

    private fun showPanel() {
        val view = LayoutInflater.from(this).inflate(R.layout.command_input, null)
        val input = view.findViewById<EditText>(R.id.command_input)
        val output = view.findViewById<TextView>(R.id.command_output)
        val runBtn = view.findViewById<Button>(R.id.btn_run)
        val closeBtn = view.findViewById<Button>(R.id.btn_close)

        runBtn.setOnClickListener {
            val script = input.text.toString()
            if (script.isBlank()) return@setOnClickListener
            output.text = ""
            executor.runScript(
                script = script,
                onLine = { i, line, result ->
                    output.post { output.append("[$i] $line -> ${result.message}\n") }
                },
                onDone = { output.post { output.append("--- done ---\n") } },
            )
        }
        closeBtn.setOnClickListener { hidePanel() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            // FLAG_ALT_FOCUSABLE_IM lets the soft keyboard come up so the user
            // can type the command without the panel stealing global focus.
            WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL; y = 80 }

        wm.addView(view, params)
        panelView = view
    }

    private fun hidePanel() {
        panelView?.let { runCatching { wm.removeView(it) } }
        panelView = null
    }

    private fun overlayType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else
            @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

    private fun startInForeground() {
        val channelId = "automation_bubble"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && nm.getNotificationChannel(channelId) == null) {
            nm.createNotificationChannel(
                NotificationChannel(channelId, "Automation Bubble", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Automation Helper")
            .setContentText("Floating bubble is active")
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(1, notif)
        }
    }
}
