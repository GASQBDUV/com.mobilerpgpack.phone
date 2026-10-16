package com.mobilerpgpack.phone.engine.activity

import android.app.Activity
import android.app.ActivityManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import java.lang.ref.WeakReference

/**
 * Hosts the HUD surface on the DEFAULT display when the game runs on the
 * secondary one. A Presentation can't be used there: the framework refuses
 * presentation windows on the default display.
 *
 * Focus is the hard part, because UZDoom stops drawing as soon as its window
 * loses focus (D_Display returns early when !AppActive in fullscreen), and a
 * controller always follows the single focused display:
 *
 * - This window must be focusable while it comes up. An activity that is the
 *   only window of its task with no focusable window makes the input
 *   dispatcher ANR ("Application does not have a focused window"), as
 *   OpenMW-DS measured on device.
 * - Once it has had focus, a 1x1 untouchable "anchor" sub-window takes over
 *   the job of being the focusable window on this display, and the main
 *   window becomes non-focusable, so taps on the HUD can never take focus.
 *   (Order matters: anchor first, or the ANR comes back.)
 * - Then the game's task is moved back to the front, which makes the game's
 *   display the focused one again, so the game regains window focus and the
 *   controller.
 *
 * Any controller input that still lands here is forwarded to the game.
 */
internal class SecondScreenHudActivity : Activity() {
    private lateinit var surfaceView: SurfaceView
    private var focusAnchor: View? = null
    private var focusDropped = false

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) = publish(holder)
        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) =
            publish(holder, width, height)
        override fun surfaceDestroyed(holder: SurfaceHolder) {
            currentSurface = null
            surfaceListener?.invoke(null, 0, 0)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setBackgroundDrawableResource(android.R.color.black)
        applyImmersive()
        val root = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            keepScreenOn = true
        }
        surfaceView = SurfaceView(this).apply {
            holder.setFormat(PixelFormat.OPAQUE)
            holder.addCallback(surfaceCallback)
        }
        root.addView(
            surfaceView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.BOTTOM,
            ),
        )
        setContentView(root)
        instance = WeakReference(this)
        Log.i(TAG, "HUD activity up on display ${display?.displayId}")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersive()
            dropFocusabilityAndReturnFocusToGame()
        }
    }

    private fun dropFocusabilityAndReturnFocusToGame() {
        if (focusDropped) return
        focusDropped = true
        window.decorView.post {
            if (!addFocusAnchor()) {
                // Fail safe: staying focusable means the game may stop drawing
                // until it gets focus back, but never an ANR.
                focusDropped = false
                Log.e(TAG, "No focus anchor, staying focusable")
            } else {
                window.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                applyImmersive()
            }
            bringGameToFront()
        }
    }

    private fun addFocusAnchor(): Boolean {
        if (focusAnchor != null) return true
        val token = window.decorView.windowToken ?: return false
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_APPLICATION_PANEL,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSPARENT,
        ).apply {
            this.token = token
            gravity = Gravity.TOP or Gravity.START
        }
        val view = View(this)
        return runCatching { windowManager.addView(view, lp) }
            .onSuccess { focusAnchor = view }
            .onFailure { Log.e(TAG, "Focus anchor addView failed", it) }
            .isSuccess
    }

    private fun bringGameToFront() {
        val game = gameActivity?.get() ?: return
        runCatching {
            getSystemService(ActivityManager::class.java)?.moveTaskToFront(game.taskId, 0)
        }.onFailure { Log.w(TAG, "moveTaskToFront failed", it) }
    }

    private fun applyImmersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    private fun publish(holder: SurfaceHolder, width: Int? = null, height: Int? = null) {
        val frame = holder.surfaceFrame
        val w = width ?: frame.width()
        val h = height ?: frame.height()
        if (holder.surface.isValid && w > 0 && h > 0) {
            currentSurface = Triple(holder.surface, w, h)
            surfaceListener?.invoke(holder.surface, w, h)
        }
    }

    // Controller input that reaches this window belongs to the game.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val game = gameActivity?.get()
        if (game != null && !game.isFinishing && isController(event.source)
            && game.dispatchKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val game = gameActivity?.get()
        if (game != null && !game.isFinishing && isController(event.source)
            && game.dispatchGenericMotionEvent(event)) {
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isController(source: Int): Boolean =
        source and android.view.InputDevice.SOURCE_GAMEPAD == android.view.InputDevice.SOURCE_GAMEPAD ||
            source and android.view.InputDevice.SOURCE_JOYSTICK == android.view.InputDevice.SOURCE_JOYSTICK ||
            source and android.view.InputDevice.SOURCE_DPAD == android.view.InputDevice.SOURCE_DPAD

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Swallow BACK so a stray gamepad B can't close the HUD mid-game; the
        // game activity closes it when it finishes.
    }

    override fun onDestroy() {
        focusAnchor?.let { runCatching { windowManager.removeView(it) } }
        focusAnchor = null
        if (instance?.get() === this) {
            instance = null
            currentSurface = null
            surfaceListener?.invoke(null, 0, 0)
        }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SecondScreenHud"

        @Volatile var instance: WeakReference<SecondScreenHudActivity>? = null
        @Volatile var gameActivity: WeakReference<Activity>? = null
        @Volatile var surfaceListener: ((Surface?, Int, Int) -> Unit)? = null
        @Volatile private var currentSurface: Triple<Surface, Int, Int>? = null

        /** Attach the engine's surface callback, replaying a surface that already exists. */
        fun attach(listener: (Surface?, Int, Int) -> Unit) {
            surfaceListener = listener
            currentSurface?.let { (surface, w, h) ->
                if (surface.isValid) listener(surface, w, h)
            }
        }

        fun detach() {
            surfaceListener = null
        }
    }
}
