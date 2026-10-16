package com.mobilerpgpack.phone.engine.activity

import android.app.Activity
import android.app.ActivityOptions
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.os.Build
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.hardware.display.DisplayManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout

internal class SecondScreenPresentationController(
    private val context: Context,
    private val onSecondScreenActiveChanged: (Boolean) -> Unit = {},
    private val onSecondScreenSurfaceChanged: (Surface?, Int, Int) -> Unit = { _, _, _ -> },
) {
    private val displayManager = context.getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var presentation: SecondScreenPresentation? = null
    private var isStarted = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = updatePresentation()
        override fun onDisplayRemoved(displayId: Int) = updatePresentation()
        override fun onDisplayChanged(displayId: Int) = updatePresentation()
    }

    fun start() {
        if (!isStarted) {
            displayManager.registerDisplayListener(displayListener, mainHandler)
            isStarted = true
        }
        updatePresentation()
    }

    fun stop() {
        if (isStarted) {
            displayManager.unregisterDisplayListener(displayListener)
            isStarted = false
        }
        presentation?.dismiss()
        presentation = null
        SecondScreenHudActivity.detach()
        // Only close the HUD activity when the game is going away for good:
        // relaunching it means another round of focus juggling.
        if ((context as? Activity)?.isFinishing == true) {
            SecondScreenHudActivity.instance?.get()?.finish()
            SecondScreenHudActivity.gameActivity = null
        }
        onSecondScreenSurfaceChanged(null, 0, 0)
        onSecondScreenActiveChanged(false)
    }

    private fun gameDisplayId(): Int =
        (context as? Activity)?.windowManager?.defaultDisplay?.displayId ?: Display.DEFAULT_DISPLAY

    // Swapped layout: the game was launched onto a secondary display (see
    // DualScreenLayout), so the HUD belongs on the default display, which a
    // Presentation can't use; it is hosted by SecondScreenHudActivity there.
    private fun showHudActivity() {
        presentation?.dismiss()
        presentation = null
        val game = context as? Activity ?: return
        SecondScreenHudActivity.gameActivity = java.lang.ref.WeakReference(game)
        // Display-changed events arrive often; only (re)attach when needed so
        // the engine isn't handed the same surface over and over.
        if (SecondScreenHudActivity.surfaceListener !== onSecondScreenSurfaceChanged) {
            onSecondScreenActiveChanged(true)
            SecondScreenHudActivity.attach(onSecondScreenSurfaceChanged)
        }
        if (SecondScreenHudActivity.instance?.get()?.isFinishing == false) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val intent = Intent(game, SecondScreenHudActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                or Intent.FLAG_ACTIVITY_NO_ANIMATION)
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY)
        try {
            game.startActivity(intent, options.toBundle())
            Log.i(TAG, "HUD activity launched on the default display (swapped)")
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to launch HUD activity on the default display", error)
            onSecondScreenActiveChanged(false)
        }
    }

    private fun updatePresentation() {
        if (gameDisplayId() != Display.DEFAULT_DISPLAY) {
            showHudActivity()
            return
        }
        val display = findSecondaryDisplay()
        val currentDisplayId = presentation?.display?.displayId

        if (display?.displayId == currentDisplayId) return

        presentation?.dismiss()
        presentation = null
        onSecondScreenSurfaceChanged(null, 0, 0)

        if (display == null) {
            Log.i(TAG, "No secondary display found")
            onSecondScreenActiveChanged(false)
            return
        }

        presentation = SecondScreenPresentation(
            context = context,
            display = display,
            onSecondScreenSurfaceChanged = onSecondScreenSurfaceChanged,
        ).also { nextPresentation ->
            try {
                nextPresentation.show()
                onSecondScreenActiveChanged(true)
                Log.i(TAG, "Second screen presentation shown on display ${display.displayId}")
            } catch (error: WindowManager.InvalidDisplayException) {
                Log.w(TAG, "Unable to show second screen presentation", error)
                presentation = null
                onSecondScreenActiveChanged(false)
            }
        }
    }

    // Never the game's own display: a Presentation there would cover the game.
    private fun findSecondaryDisplay(): Display? {
        val gameDisplay = gameDisplayId()
        val presentationDisplay = displayManager
            .getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.displayId != gameDisplay }

        return presentationDisplay ?: displayManager.displays
            .firstOrNull { it.displayId != Display.DEFAULT_DISPLAY && it.displayId != gameDisplay }
    }

    private class SecondScreenPresentation(
        context: Context,
        display: Display,
        private val onSecondScreenSurfaceChanged: (Surface?, Int, Int) -> Unit,
    ) : Presentation(context, display) {
        private lateinit var hudSurfaceView: SurfaceView
        private val surfaceCallback = object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                publishSurface(holder)
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                publishSurface(holder, width, height)
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                onSecondScreenSurfaceChanged(null, 0, 0)
            }
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            requestWindowFeature(Window.FEATURE_NO_TITLE)
            super.onCreate(savedInstanceState)

            window?.setBackgroundDrawable(ColorDrawable(Color.BLACK))
            window?.setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
            )
            // A Presentation is a Dialog, focusable by default. A controller
            // follows the focused display, and UZDoom stops drawing when its
            // window loses focus, so a tap on the HUD froze the game. Touch
            // still reaches non-focusable windows.
            window?.addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)

            val root = FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                keepScreenOn = true
            }

            hudSurfaceView = SurfaceView(context).apply {
                keepScreenOn = true
                holder.setFormat(PixelFormat.OPAQUE)
                holder.addCallback(surfaceCallback)
            }
            root.addView(
                hudSurfaceView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.BOTTOM,
                ),
            )

            setContentView(root)
        }

        override fun dismiss() {
            if (::hudSurfaceView.isInitialized) {
                hudSurfaceView.holder.removeCallback(surfaceCallback)
            }
            onSecondScreenSurfaceChanged(null, 0, 0)
            super.dismiss()
        }

        private fun publishSurface(holder: SurfaceHolder, width: Int? = null, height: Int? = null) {
            val surface = holder.surface
            val surfaceFrame = holder.surfaceFrame
            val surfaceWidth = width ?: surfaceFrame.width()
            val surfaceHeight = height ?: surfaceFrame.height()
            if (surface.isValid && surfaceWidth > 0 && surfaceHeight > 0) {
                onSecondScreenSurfaceChanged(surface, surfaceWidth, surfaceHeight)
            }
        }
    }

    private companion object {
        private const val TAG = "SecondScreen"
    }
}