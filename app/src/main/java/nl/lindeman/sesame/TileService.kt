package nl.lindeman.sesame

import android.graphics.drawable.Icon
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity


@RequiresApi(Build.VERSION_CODES.N)
class TileService : TileService() {
    @RequiresApi(Build.VERSION_CODES.O)
    override fun onClick() {
        super.onClick()
        val activity = MainActivity()
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this)
        activity.setPrefs(prefs)
        activity.connect(this)
        prefs.getString(PREF_KEY_TOPIC_COMMANDS, "/garage/commands")?.let{
            activity.publish(it, COMMAND_TOGGLE)
            if (prefs.getBoolean(PREF_KEY_DRY_RUN, false)) {
                Toast.makeText(applicationContext, "Dry run: send '${COMMAND_TOGGLE}' to topic '$it'", Toast.LENGTH_LONG).show()
            }
            val vibrator = getSystemService(AppCompatActivity.VIBRATOR_SERVICE) as Vibrator
            if (vibrator.hasVibrator()) {
                val effect: VibrationEffect = VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
                vibrator.vibrate(effect)
            }
        }
    }

    override fun onTileAdded() {
        super.onTileAdded()
        qsTile.state = Tile.STATE_INACTIVE
        qsTile.label = "Garage"
        qsTile.icon = Icon.createWithResource(this, R.drawable.garage_tile)
        qsTile.updateTile()
    }
}

