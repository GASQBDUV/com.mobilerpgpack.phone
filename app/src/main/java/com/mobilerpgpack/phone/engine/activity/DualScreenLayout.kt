package com.mobilerpgpack.phone.engine.activity

import android.app.Activity
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Build
import android.util.Log
import android.view.Display
import java.io.File

/**
 * Which panel the game goes on.
 *
 * Default ("game on secondary"): the game is launched onto the first
 * non-default display and the HUD goes on the default one. That suits GammaOS
 * on the Anbernic RG DS / RG DS Plus, whose default display is the bottom
 * (touch) panel. On the AYN Thor the default display is the top panel, so the
 * old layout (game on the default display, HUD on the other one) is kept when
 * an empty file named `no_swap_screens` exists in the app's external files dir
 * (Android/data/<package>/files/).
 */
internal object DualScreenLayout {
    private const val TAG = "DualScreenLayout"
    private const val DISABLE_FILE = "no_swap_screens"

    fun swapWanted(context: Context): Boolean {
        val dir = context.getExternalFilesDir(null) ?: return true
        return !File(dir, DISABLE_FILE).exists()
    }

    fun secondaryDisplay(context: Context): Display? {
        val dm = context.getSystemService(DisplayManager::class.java) ?: return null
        return dm.displays.firstOrNull { it.isValid && it.displayId != Display.DEFAULT_DISPLAY }
    }

    /**
     * Starts the game activity, on the secondary display when the swapped
     * layout is wanted and possible, otherwise the normal way.
     */
    fun startGameActivity(context: Context, gameActivity: Class<*>) {
        val intent = Intent(context, gameActivity)
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val target = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && swapWanted(context)) {
            secondaryDisplay(context)
        } else {
            null
        }
        if (target != null) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val options = ActivityOptions.makeBasic().setLaunchDisplayId(target.displayId)
                context.startActivity(intent, options.toBundle())
                Log.i(TAG, "Game launched on display ${target.displayId} (swapped)")
                return
            } catch (error: RuntimeException) {
                // Launching on another display can be refused outright; fall
                // back to a normal launch rather than not starting at all.
                Log.w(TAG, "Launch on display ${target.displayId} refused", error)
            }
        }
        context.startActivity(intent)
    }
}
