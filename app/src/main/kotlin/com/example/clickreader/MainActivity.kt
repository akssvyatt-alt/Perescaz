package com.example.clickreader

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.text.InputType
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private lateinit var tts: TextToSpeech
    private lateinit var audio: AudioManager
    private var ttsReady = false

    private lateinit var root: LinearLayout
    private lateinit var editor: EditText
    private lateinit var settingsBox: LinearLayout
    private lateinit var speedBar: SeekBar
    private lateinit var startBtn: Button
    private lateinit var stopBtn: Button
    private lateinit var statusView: TextView

    private var words: List<String> = emptyList()
    private var pos = 0            // индекс следующего слова
    private var lastStart = 0      // границы последней проигранной порции
    private var lastEnd = 0
    private var reading = false
    private var lastButtons = 0
    private var lastRepeatAt = 0L

    private val wordsPerClick = 3

    // ---------- жизненный цикл ----------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        audio = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        buildUi()
        tts = TextToSpeech(this, this)
        audio.registerAudioDeviceCallback(deviceCallback, null)
    }

    override fun onInit(status: Int) {
        ttsReady = status == TextToSpeech.SUCCESS
        if (ttsReady) {
            tts.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } else {
            setStatus("Не удалось запустить синтезатор речи")
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && reading) {
            hideSystemBars()
            root.requestFocus()
            root.requestPointerCapture()
        }
    }

    override fun onDestroy() {
        audio.unregisterAudioDeviceCallback(deviceCallback)
        tts.stop()
        tts.shutdown()
        super.onDestroy()
    }

    // ---------- интерфейс ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isFocusable = true
            isFocusableInTouchMode = true
            // события мыши при захваченном указателе приходят сюда
            setOnCapturedPointerListener { _, event -> handleMouse(event) }
        }

        editor = EditText(this).apply {
            hint = "Вставьте текст сюда"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        root.addView(editor, LinearLayout.LayoutParams(-1, 0, 1f))

        settingsBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val speedLabel = TextView(this).apply {
            text = "Скорость речи"
            setTextColor(Color.WHITE)
        }
        speedBar = SeekBar(this).apply {
            max = 25          // скорость = (progress + 5) / 10 → 0.5 … 3.0
            progress = 5      // 1.0
        }
        settingsBox.addView(speedLabel)
        settingsBox.addView(speedBar)
        root.addView(settingsBox, LinearLayout.LayoutParams(-1, -2))

        startBtn = Button(this).apply {
            text = "Старт"
            setOnClickListener { startReading() }
        }
        stopBtn = Button(this).apply {
            text = "Стоп"
            visibility = View.GONE
            setOnClickListener { stopReading() }
        }
        root.addView(startBtn, LinearLayout.LayoutParams(-1, -2))
        root.addView(stopBtn, LinearLayout.LayoutParams(-1, -2))

        statusView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
        }
        root.addView(statusView, LinearLayout.LayoutParams(-1, -2))

        setContentView(root)
    }

    private fun setStatus(msg: String) {
        statusView.text = msg
    }

    // ---------- режим чтения ----------

    private fun startReading() {
        val text = editor.text.toString().trim()
        words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) {
            Toast.makeText(this, "Сначала вставьте текст", Toast.LENGTH_SHORT).show()
            return
        }
        if (!ttsReady) {
            Toast.makeText(this, "Синтезатор речи не готов", Toast.LENGTH_SHORT).show()
            return
        }

        val hasCyrillic = text.any { it in 'а'..'я' || it in 'А'..'Я' || it == 'ё' || it == 'Ё' }
        tts.language = if (hasCyrillic) Locale("ru", "RU") else Locale.US
        tts.setSpeechRate((speedBar.progress + 5) / 10f)

        pos = 0
        lastStart = 0
        lastEnd = 0
        lastButtons = 0
        reading = true

        editor.visibility = View.GONE
        settingsBox.visibility = View.GONE
        startBtn.visibility = View.GONE
        stopBtn.visibility = View.VISIBLE
        setStatus("ЛКМ — дальше, ПКМ — повтор\nСредняя кнопка — выход")

        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
            .hideSoftInputFromWindow(root.windowToken, 0)

        hideSystemBars()
        try {
            startLockTask() // закрепление экрана: шторка и "домой" заблокированы
        } catch (e: Exception) {
            Toast.makeText(this, "Закрепление экрана недоступно", Toast.LENGTH_LONG).show()
        }
        root.requestFocus()
        root.requestPointerCapture() // мышь "прикована" к приложению, курсор скрыт
    }

    private fun stopReading() {
        reading = false
        tts.stop()
        root.releasePointerCapture()
        try {
            stopLockTask()
        } catch (e: Exception) {
        }
        showSystemBars()
        editor.visibility = View.VISIBLE
        settingsBox.visibility = View.VISIBLE
        startBtn.visibility = View.VISIBLE
        stopBtn.visibility = View.GONE
        setStatus("")
    }

    // ---------- мышь ----------

    private fun isMouse(ev: MotionEvent) =
        ev.isFromSource(InputDevice.SOURCE_MOUSE) ||
            ev.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)

    /** Реагируем на момент нажатия кнопки (смена состояния buttonState), поэтому
     *  не важно, каким именно типом события система доставила клик. */
    private fun handleMouse(ev: MotionEvent): Boolean {
        if (!reading) return false
        val buttons = ev.buttonState
        val pressed = buttons and lastButtons.inv()
        lastButtons = buttons
        if (pressed and MotionEvent.BUTTON_PRIMARY != 0) speakNext()
        if (pressed and MotionEvent.BUTTON_SECONDARY != 0) repeatLast()
        if (pressed and MotionEvent.BUTTON_TERTIARY != 0) stopReading()
        return true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (reading && isMouse(ev)) {
            handleMouse(ev)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        if (reading && isMouse(ev)) {
            handleMouse(ev)
            return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    // Некоторые версии Android превращают правую кнопку мыши в "Назад" —
    // в режиме чтения считаем это повтором (с защитой от двойного срабатывания).
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (reading) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK -> {
                    if (event.action == KeyEvent.ACTION_DOWN) repeatLast()
                    return true
                }
                KeyEvent.KEYCODE_ESCAPE -> {
                    if (event.action == KeyEvent.ACTION_DOWN) stopReading()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // ---------- озвучка ----------

    private fun headphonesConnected(): Boolean =
        audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
            when (d.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
                else -> Build.VERSION.SDK_INT >= 31 && d.type == AudioDeviceInfo.TYPE_BLE_HEADSET
            }
        }

    private fun canSpeak(): Boolean {
        if (!ttsReady) return false
        if (!headphonesConnected()) {
            setStatus("Наушники не подключены — звук заблокирован")
            return false
        }
        return true
    }

    private fun speakNext() {
        if (pos >= words.size) {
            setStatus("Конец текста")
            return
        }
        if (!canSpeak()) return
        lastStart = pos
        lastEnd = minOf(pos + wordsPerClick, words.size)
        pos = lastEnd
        speakRange()
    }

    private fun repeatLast() {
        val now = SystemClock.uptimeMillis()
        if (now - lastRepeatAt < 250) return
        lastRepeatAt = now
        if (lastEnd <= lastStart) return
        if (!canSpeak()) return
        speakRange()
    }

    private fun speakRange() {
        val chunk = words.subList(lastStart, lastEnd).joinToString(" ")
        setStatus("${lastStart + 1}–$lastEnd из ${words.size}\n\n$chunk")
        tts.speak(chunk, TextToSpeech.QUEUE_FLUSH, null, "chunk")
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            if (reading && !headphonesConnected()) {
                tts.stop()
                setStatus("Наушники отключены — звук остановлен")
            }
        }
    }

    // ---------- системные панели ----------

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.systemBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= 30) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        }
    }
}
