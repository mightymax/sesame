package nl.lindeman.sesame

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.drawable.AnimationDrawable
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.AlarmClock.EXTRA_MESSAGE
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import com.beust.klaxon.Klaxon
import com.google.android.material.snackbar.Snackbar
import org.eclipse.paho.android.service.MqttAndroidClient
import org.eclipse.paho.client.mqttv3.*
import java.util.*
import kotlin.concurrent.schedule
import kotlin.math.floor
import kotlin.properties.Delegates

const val PREF_KEY_BROKER = "broker"
const val PREF_KEY_TOPIC_STATUS = "topic_status"
const val PREF_KEY_TOPIC_COMMANDS = "topic_commands"
const val PREF_KEY_USERNAME = "username"
const val PREF_KEY_PASSWORD= "password"
const val PREF_KEY_DRY_RUN = "dryrun"
const val COMMAND_OPEN = "open"
const val COMMAND_CLOSE = "close"
const val COMMAND_STATUS = "status"
const val COMMAND_STOP = "stop"
const val COMMAND_TOGGLE = "toggle"
const val DOOR_STATUS_OPEN = "OPEN"
const val DOOR_STATUS_CLOSED = "CLOSED"
const val DOOR_STATUS_OPENING = "OPENING"
const val DOOR_STATUS_CLOSING = "CLOSING"

data class DoorStatus(
    val range: Int = 500,
    val temperature: Double = 21.0,
    val humidity: Double = 50.0,
    val light: Int = 50,
    var door: String = DOOR_STATUS_OPEN,
    val ssid: String = "",
    val ip: String = "",
    val rssi: Int = 0,
    val message: String = "",
    var error: Boolean = false
)

@SuppressLint("SetTextI18n")
class MainActivity : AppCompatActivity() {


    private var isError: Boolean = false

    private lateinit var toolbar: Toolbar;
    private lateinit var prefs: SharedPreferences

    private lateinit var vibrator: Vibrator
    private lateinit var mediaPlayer: MediaPlayer

    private lateinit var garageAnimation: AnimationDrawable

    lateinit var mqttClient: MqttAndroidClient

    var currentStatus: DoorStatus by Delegates.observable(DoorStatus()) {_, _, newDoorStatus ->

        if (!findViewById<ImageView>(R.id.humidityIcon).isVisible) {
            findViewById<ImageView>(R.id.humidityIcon).visibility = View.VISIBLE
            findViewById<ImageView>(R.id.temperatureIcon).visibility = View.VISIBLE
            findViewById<ImageView>(R.id.rulerIcon).visibility = View.VISIBLE
            findViewById<ImageView>(R.id.lightbulbIcon).visibility = View.VISIBLE
        }

        findViewById<TextView>(R.id.rangeView).text = newDoorStatus.range.toString() + " cm."
        findViewById<TextView>(R.id.temparatureView).text = String.format("%.1f", newDoorStatus.temperature) + " ºC"
        findViewById<TextView>(R.id.humidityView).text = String.format("%.1f", newDoorStatus.humidity) + " %"

        dimLight(newDoorStatus)

        if (!newDoorStatus.error && !isError) {
            val resID = resources.getIdentifier(
                    "garage_" + newDoorStatus.door.toLowerCase(), "drawable",
                    packageName
            )
            if (resID > 0) {
                findViewById<ImageView>(R.id.animatedGarageButton).apply {
                    setBackgroundResource(resID)
                    garageAnimation = background as AnimationDrawable
                    garageAnimation.start();
                }
            }
        } else {
            isError = true
            mediaPlayer.start()
            findViewById<ImageView>(R.id.animatedGarageButton).apply {
                setBackgroundResource(R.drawable.garage_error)
                garageAnimation = background as AnimationDrawable
                garageAnimation.start();
            }
            Timer("Error", false).schedule(2000) {
                newDoorStatus.error = false
                isError = false
                mediaPlayer.stop()
                mediaPlayer.prepare()
            }
        }
        if (newDoorStatus.message != "") Snackbar.make(findViewById(R.id.canvas),newDoorStatus.message, Snackbar.LENGTH_LONG).show()
    }


    @RequiresApi(Build.VERSION_CODES.N)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)


        toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.title = ""
        setSupportActionBar(toolbar)


        //clear default text:
        findViewById<TextView>(R.id.rangeView).text = ""
        findViewById<TextView>(R.id.temparatureView).text = ""
        findViewById<TextView>(R.id.humidityView).text = ""
        findViewById<TextView>(R.id.lightsensorView).text = ""
        findViewById<ImageView>(R.id.humidityIcon).visibility = View.INVISIBLE
        findViewById<ImageView>(R.id.temperatureIcon).visibility = View.INVISIBLE
        findViewById<ImageView>(R.id.rulerIcon).visibility = View.INVISIBLE
        findViewById<ImageView>(R.id.lightbulbIcon).visibility = View.INVISIBLE

    }


    override fun onStart() {
        super.onStart()
        findViewById<ImageView>(R.id.animatedGarageButton).apply {
            setBackgroundResource(R.drawable.garage_empty)
            garageAnimation = background as AnimationDrawable
            garageAnimation.start()
        }
        mediaPlayer = MediaPlayer.create(applicationContext, R.raw.alarm)
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator

        val broker = prefs.getString(PREF_KEY_BROKER, "")
        val topicCommands = prefs.getString(PREF_KEY_TOPIC_COMMANDS, "")
        val topicStatus = prefs.getString(PREF_KEY_TOPIC_STATUS, "")
        println(topicStatus)

        if ((broker == "") or (topicCommands == "") or (topicStatus == "")) {
            Toast.makeText(applicationContext, R.string.check_settings, Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, SettingsActivity::class.java))
        } else {
            connect(applicationContext)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.menuitems, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem) = when (item.itemId) {
        R.id.action_settings -> {
            startActivity(Intent(this, SettingsActivity::class.java))
            true
        }

        R.id.action_reconnect -> {
            try {
                val disconToken: IMqttToken = mqttClient.disconnect()
                disconToken.actionCallback = object : IMqttActionListener {
                    override fun onSuccess(asyncActionToken: IMqttToken?) {
                        connect(applicationContext)
                    }

                    override fun onFailure(asyncActionToken: IMqttToken?,
                                           exception: Throwable?) {
                        // something went wrong, but probably we are disconnected anyway
                    }
                }
            } catch (e: MqttException) {
                e.printStackTrace()
            }
            true
        }

        else -> {
            super.onOptionsItemSelected(item)
        }
    }

    fun setPrefs(setprefs: SharedPreferences) {
        prefs = setprefs
    }




    @RequiresApi(Build.VERSION_CODES.Q)
    fun toggleDoor(view: View) {
        if (vibrator.hasVibrator()) {
            var effect: VibrationEffect = VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE)
            vibrator.vibrate(effect)
        }

        prefs.getString(PREF_KEY_TOPIC_COMMANDS, "")?.let{
            when(currentStatus.door.toUpperCase()) {
                DOOR_STATUS_CLOSED -> {
                    currentStatus.door = DOOR_STATUS_OPENING
                    publish(it, COMMAND_OPEN)
                }
                DOOR_STATUS_OPEN -> {
                    currentStatus.door = DOOR_STATUS_CLOSING
                    publish(it, COMMAND_CLOSE)
                }
                else -> publish(it, COMMAND_STOP)
            }
        }

    }

    fun dimLight(status: DoorStatus)
    {
        var lightStatus: String
        lightStatus = if (status.light < 50) "off"
        else "on"
        findViewById<TextView>(R.id.lightsensorView).text = String.format("%d", status.light) + " (${lightStatus})"


        val scale = floor(100 * status.light.toDouble() / 500)
        val color = when {
            scale > 90 -> R.color.light_100
            scale > 80 -> R.color.light_90
            scale > 70 -> R.color.light_80
            scale > 60 -> R.color.light_70
            scale > 50 -> R.color.light_60
            scale > 40 -> R.color.light_50
            scale > 30 -> R.color.light_40
            scale > 20 -> R.color.light_30
            scale > 10 -> R.color.light_20
            else -> R.color.light_10
        }

        findViewById<ImageButton>(R.id.lightbulbView).setColorFilter(ContextCompat.getColor(applicationContext, color))
    }


    fun connect(context: Context) {

        mqttClient = MqttAndroidClient(context, prefs.getString(PREF_KEY_BROKER, ""), MqttClient.generateClientId())
        mqttClient.setCallback(object : MqttCallback {

            override fun messageArrived(topic: String?, message: MqttMessage?) {
                val status = message?.toString()?.let { Klaxon().parse<DoorStatus>(it) };
                if (status != null) currentStatus = status
            }

            override fun connectionLost(cause: Throwable?) {
                if (cause != null) Toast.makeText(applicationContext, "Connection lost ${cause.toString()}", Toast.LENGTH_LONG).show();
            }

            override fun deliveryComplete(token: IMqttDeliveryToken?) {
                println(token.toString())
            }
        })

        val options = MqttConnectOptions()
        prefs.getString(PREF_KEY_USERNAME, "")?.let {
            options.userName = it;
        }
        prefs.getString(PREF_KEY_PASSWORD, "")?.let {
            options.password = it.toCharArray();
        }
        try {
            mqttClient.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    prefs.getString(PREF_KEY_TOPIC_STATUS, "")?.let{
                        subscribe(it)
                    }
                    prefs.getString(PREF_KEY_TOPIC_COMMANDS, "")?.let{
                        publish(it, COMMAND_STATUS);
                    }
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Toast.makeText(applicationContext, R.string.check_settings, Toast.LENGTH_LONG).show()
                    startActivity(Intent(applicationContext, SettingsActivity::class.java))
                }
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }

    }
    fun publish(topic: String, msg: String, qos: Int = 1, retained: Boolean = false) {
        if (prefs.getBoolean(PREF_KEY_DRY_RUN, false)) {
            try {
                Toast.makeText(applicationContext, "Dry run: send '$msg' to topic '$topic'", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {

            }
            return
        }

        try {
            val message = MqttMessage()
            message.payload = msg.toByteArray()
            message.qos = qos
            message.isRetained = retained
            mqttClient.publish(topic, message, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
//                    Toast.makeText(applicationContext, "$msg published to $topic", Toast.LENGTH_SHORT).show();
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Toast.makeText(applicationContext, "Failed to publish $msg to $topic", Toast.LENGTH_LONG).show();
                }
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }

    fun subscribe(topic: String, qos: Int = 1) {
        try {
            mqttClient.subscribe(topic, qos, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
//                    Toast.makeText(applicationContext, "Subscribed to $topic", Toast.LENGTH_SHORT).show();
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Toast.makeText(applicationContext, "Failed to subscribe $topic", Toast.LENGTH_LONG).show();
                }
            })
        } catch (e: MqttException) {
            e.printStackTrace()
        }
    }


}