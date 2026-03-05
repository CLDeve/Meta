/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamViewModel - DAT Camera Streaming API Demo
//
// This ViewModel demonstrates the DAT Camera Streaming APIs for:
// - Creating and managing stream sessions with wearable devices
// - Receiving video frames from device cameras
// - Capturing photos during streaming sessions
// - Handling different video qualities and formats
// - Processing raw video data (I420 -> NV21 conversion)

package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.app.Application
import android.content.Intent
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Log
import android.util.Base64
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.StreamSession
import com.meta.wearable.dat.camera.startStreamSession
import com.meta.wearable.dat.camera.types.PhotoData
import com.meta.wearable.dat.camera.types.StreamConfiguration
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.camera.types.VideoFrame
import com.meta.wearable.dat.camera.types.VideoQuality
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  private data class CommandCard(
      val intent: String,
      val title: String,
      val action: String,
      val priority: String,
      val confidence: Double,
      val targetGate: String? = null,
      val reason: String? = null,
  )

  companion object {
    private const val TAG = "StreamViewModel"
    private const val DEFAULT_DESCRIBE_QUESTION = "What is in front of me?"
    private const val MAX_VOICE_RETRIES = 1
    private const val AI_REQUEST_JPEG_QUALITY = 60
    private const val AI_READ_TIMEOUT_MS = 45_000
    private const val STREAM_FPS = 15
    private const val PREVIEW_JPEG_QUALITY = 85
    private const val HANDS_FREE_RESTART_DELAY_MS = 450L
    private const val HANDS_FREE_RECONNECT_DELAY_MS = 1_200L
    private const val HANDS_FREE_STOP_MESSAGE = "Hands-free mode stopped."
    private const val COMMAND_CENTER_SUCCESS = "Sent to command centre"
    private const val COMMAND_CENTER_DISABLED =
        "Set COMMAND_CENTER_URL to forward responses to command centre"
    private const val QA_EVENT_TYPE = "qa"
    private const val MAX_CHAT_MESSAGES = 24
    private val INITIAL_STATE = StreamUiState()
  }

  // AutoDeviceSelector automatically selects the first available wearable device
  private val deviceSelector: DeviceSelector = AutoDeviceSelector()
  private var streamSession: StreamSession? = null

  private val _uiState = MutableStateFlow(INITIAL_STATE)
  val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

  private val streamTimer = StreamTimer()

  private var videoJob: Job? = null
  private var stateJob: Job? = null
  private var timerJob: Job? = null
  private var speechRecognizer: SpeechRecognizer? = null
  private var latestVoiceContext: Context? = null
  private var audioManager: AudioManager? = null
  private var previousAudioMode: Int? = null
  private var hasForcedBluetoothVoiceRoute = false
  private var preferBluetoothVoiceRoute = true
  private var bluetoothNoSpeechFailureCount = 0
  private var suppressNextVoiceError = false
  private var voiceRetryCount: Int = 0
  private var textToSpeech: TextToSpeech? = null
  private var isSpeakingAnswer = false

  init {
    // Collect timer state
    timerJob =
        viewModelScope.launch {
          launch {
            streamTimer.timerMode.collect { mode -> _uiState.update { it.copy(timerMode = mode) } }
          }

          launch {
            streamTimer.remainingTimeSeconds.collect { seconds ->
              _uiState.update { it.copy(remainingTimeSeconds = seconds) }
            }
          }

          launch {
            streamTimer.isTimerExpired.collect { expired ->
              if (expired) {
                // Stop streaming and navigate back
                stopStream()
                wearablesViewModel.navigateToDeviceSelection()
              }
            }
          }
        }
  }

  fun startStream() {
    resetTimer()
    streamTimer.startTimer()
    videoJob?.cancel()
    stateJob?.cancel()
    val streamSession =
        Wearables.startStreamSession(
                getApplication(),
                deviceSelector,
                StreamConfiguration(videoQuality = VideoQuality.HIGH, STREAM_FPS),
            )
            .also { streamSession = it }
    videoJob = viewModelScope.launch { streamSession.videoStream.collect { handleVideoFrame(it) } }
    stateJob =
        viewModelScope.launch {
          streamSession.state.collect { currentState ->
            val prevState = _uiState.value.streamSessionState
            _uiState.update { it.copy(streamSessionState = currentState) }

            // navigate back when state transitioned to STOPPED
            if (currentState != prevState && currentState == StreamSessionState.STOPPED) {
              stopStream()
              wearablesViewModel.navigateToDeviceSelection()
            }
          }
        }
  }

  fun stopStream() {
    videoJob?.cancel()
    videoJob = null
    stateJob?.cancel()
    stateJob = null
    stopVoiceDescribe(disableHandsFreeMode = true)
    streamSession?.close()
    streamSession = null
    streamTimer.stopTimer()
    _uiState.update { INITIAL_STATE }
  }

  fun capturePhoto() {
    if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      viewModelScope.launch { streamSession?.capturePhoto()?.onSuccess { handlePhotoData(it) } }
    }
  }

  fun describeCurrentFrame(question: String = DEFAULT_DESCRIBE_QUESTION) {
    val frame = _uiState.value.videoFrame
    val normalizedQuestion = question.trim().ifBlank { DEFAULT_DESCRIBE_QUESTION }
    appendChatMessage(ChatRole.USER, normalizedQuestion)
    if (frame == null) {
      val noFrameError = "No frame available yet"
      _uiState.update {
        it.copy(
            isDescribeLoading = false,
            describeResult = null,
            describeError = noFrameError,
            commandCenterStatus = null,
            commandCenterError = null,
        )
      }
      appendChatMessage(ChatRole.ASSISTANT, "Error: $noFrameError")
      return
    }

    viewModelScope.launch {
      _uiState.update {
        it.copy(
            isDescribeLoading = true,
            describeError = null,
            describeResult = null,
            commandCenterStatus = null,
            commandCenterError = null,
        )
      }

      val result =
          runCatching { queryOpenAi(frame, normalizedQuestion) }
              .onFailure { Log.e(TAG, "OpenAI describe failed", it) }

      val describeResult = result.getOrNull()
      val describeError = result.exceptionOrNull()?.message
      when {
        !describeResult.isNullOrBlank() -> appendChatMessage(ChatRole.ASSISTANT, describeResult)
        !describeError.isNullOrBlank() -> appendChatMessage(ChatRole.ASSISTANT, "Error: $describeError")
      }
      val handsFreeEnabled = _uiState.value.isHandsFreeModeEnabled
      if (!describeResult.isNullOrBlank()) {
        speakText(describeResult)
      } else if (!describeError.isNullOrBlank()) {
        speakText("I could not get an answer right now. Please try again.")
      }
      var commandCenterStatus: String? = null
      var commandCenterError: String? = null
      runCatching { sendToCommandCenter(normalizedQuestion, describeResult, describeError, frame) }
          .onSuccess { commandCenterStatus = it }
          .onFailure {
            commandCenterError = it.message
            Log.e(TAG, "Command centre send failed", it)
          }

      _uiState.update {
        it.copy(
            isDescribeLoading = false,
            describeResult = describeResult,
            describeError = describeError,
            commandCenterStatus = commandCenterStatus,
            commandCenterError = commandCenterError,
        )
      }

      if (handsFreeEnabled && !isSpeakingAnswer) {
        restartHandsFreeListening()
      }
    }
  }

  private fun appendChatMessage(role: ChatRole, text: String) {
    val normalized = text.trim()
    if (normalized.isEmpty()) {
      return
    }
    _uiState.update {
      val updated = (it.chatMessages + ChatMessage(role = role, text = normalized)).takeLast(MAX_CHAT_MESSAGES)
      it.copy(chatMessages = updated)
    }
  }

  fun showShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = true) }
  }

  fun hideShareDialog() {
    _uiState.update { it.copy(isShareDialogVisible = false) }
  }

  fun sharePhoto(bitmap: Bitmap) {
    val context = getApplication<Application>()
    val imagesFolder = File(context.cacheDir, "images")
    try {
      imagesFolder.mkdirs()
      val file = File(imagesFolder, "shared_image.png")
      FileOutputStream(file).use { stream ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
      }

      val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
      val intent = Intent(Intent.ACTION_SEND)
      intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      intent.putExtra(Intent.EXTRA_STREAM, uri)
      intent.type = "image/png"
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

      val chooser = Intent.createChooser(intent, "Share Image")
      chooser.flags = Intent.FLAG_ACTIVITY_NEW_TASK
      context.startActivity(chooser)
    } catch (e: IOException) {
      Log.e("StreamViewModel", "Failed to share photo", e)
    }
  }

  fun cycleTimerMode() {
    streamTimer.cycleTimerMode()
    if (_uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      streamTimer.startTimer()
    }
  }

  fun resetTimer() {
    streamTimer.resetTimer()
  }

  fun toggleHandsFreeVoice(context: Context) {
    if (_uiState.value.isHandsFreeModeEnabled) {
      stopVoiceDescribe(disableHandsFreeMode = true)
      return
    }

    _uiState.update {
      it.copy(
          isHandsFreeModeEnabled = true,
          describeError = null,
          commandCenterStatus = null,
          commandCenterError = null,
      )
    }
    startVoiceDescribe(context)
  }

  fun startVoiceDescribe(context: Context) {
    latestVoiceContext = context.applicationContext
    prepareVoiceAudioRoute(context, preferBluetooth = preferBluetoothVoiceRoute)
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      _uiState.update {
        it.copy(
            voiceHeardText = null,
            isListening = false,
            isHandsFreeModeEnabled = false,
            describeError = "Speech recognition not available",
        )
      }
      return
    }

    speechRecognizer?.destroy()
    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    suppressNextVoiceError = false
    voiceRetryCount = 0
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault())
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1400L)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2400L)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
    }
    val listener =
        object : RecognitionListener {
          override fun onReadyForSpeech(params: Bundle?) {
            _uiState.update { it.copy(isListening = true, voiceHeardText = null, describeError = null) }
          }
          override fun onBeginningOfSpeech() {}
          override fun onRmsChanged(rmsdB: Float) {}
          override fun onBufferReceived(buffer: ByteArray?) {}
          override fun onEndOfSpeech() {}
          override fun onError(error: Int) {
            if (suppressNextVoiceError) {
              suppressNextVoiceError = false
              _uiState.update { it.copy(isListening = false) }
              return
            }

            if (error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED) {
              _uiState.update { it.copy(isListening = false, describeError = speechErrorMessage(error)) }
              if (_uiState.value.isHandsFreeModeEnabled) {
                restartHandsFreeListening(HANDS_FREE_RECONNECT_DELAY_MS)
              }
              return
            }

            val isNoSpeechError =
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (isNoSpeechError && hasForcedBluetoothVoiceRoute) {
              bluetoothNoSpeechFailureCount += 1
            } else if (!isNoSpeechError) {
              bluetoothNoSpeechFailureCount = 0
            }

            val canRetry =
                isNoSpeechError &&
                    voiceRetryCount < MAX_VOICE_RETRIES
            if (canRetry) {
              voiceRetryCount += 1
              _uiState.update {
                it.copy(isListening = true, describeError = "Didn't catch that. Please say it again.")
              }
              speechRecognizer?.cancel()
              speechRecognizer?.startListening(intent)
              return
            }

            if (isNoSpeechError && hasForcedBluetoothVoiceRoute && bluetoothNoSpeechFailureCount >= 2) {
              preferBluetoothVoiceRoute = false
              _uiState.update {
                it.copy(
                    isListening = false,
                    describeError = "Could not hear clearly from glasses mic. Switched to phone mic.",
                )
              }
              if (_uiState.value.isHandsFreeModeEnabled) {
                restartHandsFreeListening(150L)
              }
              return
            }

            _uiState.update { it.copy(isListening = false, describeError = speechErrorMessage(error)) }
            if (
                _uiState.value.isHandsFreeModeEnabled &&
                    isNoSpeechError
            ) {
              restartHandsFreeListening()
            }
          }
          override fun onResults(results: Bundle?) {
            val text =
                results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?: ""
            val normalizedText = text.trim()
            voiceRetryCount = 0
            bluetoothNoSpeechFailureCount = 0
            _uiState.update { it.copy(isListening = false, voiceHeardText = normalizedText) }
            if (normalizedText.isNotEmpty()) {
              if (isStopHandsFreeCommand(normalizedText)) {
                stopVoiceDescribe(disableHandsFreeMode = true)
                _uiState.update {
                  it.copy(
                      voiceHeardText = normalizedText,
                      describeResult = HANDS_FREE_STOP_MESSAGE,
                      describeError = null,
                      commandCenterStatus = null,
                      commandCenterError = null,
                  )
                }
              } else {
                describeCurrentFrame(normalizedText)
              }
            } else {
              _uiState.update {
                it.copy(
                    describeError = "Didn't catch your question. Try again.",
                    commandCenterStatus = null,
                    commandCenterError = null,
                )
              }
              if (_uiState.value.isHandsFreeModeEnabled) {
                restartHandsFreeListening()
              }
            }
          }
          override fun onPartialResults(partialResults: Bundle?) {}
          override fun onEvent(eventType: Int, params: Bundle?) {}
        }

    speechRecognizer?.setRecognitionListener(listener)
    speechRecognizer?.startListening(intent)
  }

  fun stopVoiceDescribe(disableHandsFreeMode: Boolean = true) {
    voiceRetryCount = 0
    bluetoothNoSpeechFailureCount = 0
    preferBluetoothVoiceRoute = true
    suppressNextVoiceError = true
    speechRecognizer?.stopListening()
    speechRecognizer?.cancel()
    clearVoiceAudioRoute()
    _uiState.update {
      it.copy(
          isListening = false,
          isHandsFreeModeEnabled =
              if (disableHandsFreeMode) false else it.isHandsFreeModeEnabled,
      )
    }
  }

  private fun restartHandsFreeListening(delayMs: Long = HANDS_FREE_RESTART_DELAY_MS) {
    if (!_uiState.value.isHandsFreeModeEnabled) {
      return
    }
    val context = latestVoiceContext ?: getApplication<Application>().applicationContext
    viewModelScope.launch {
      delay(delayMs)
      if (
          _uiState.value.isHandsFreeModeEnabled &&
              !_uiState.value.isListening &&
              !_uiState.value.isDescribeLoading
      ) {
        startVoiceDescribe(context)
      }
    }
  }

  private fun isStopHandsFreeCommand(text: String): Boolean {
    val normalized = text.trim().lowercase(Locale.ROOT)
    return normalized == "stop listening" ||
        normalized == "stop voice" ||
        normalized == "stop voice mode" ||
        normalized == "stop hands free" ||
        normalized == "stop hands-free" ||
        normalized == "cancel voice mode" ||
        normalized == "exit voice mode"
  }

  private fun prepareVoiceAudioRoute(context: Context, preferBluetooth: Boolean) {
    val manager = context.getSystemService(AudioManager::class.java) ?: return
    audioManager = manager
    if (previousAudioMode == null) {
      previousAudioMode = manager.mode
    }
    if (!preferBluetooth) {
      manager.mode = AudioManager.MODE_NORMAL
      runCatching { manager.clearCommunicationDevice() }
          .onFailure { Log.w(TAG, "Failed to clear communication device for phone mic fallback", it) }
      hasForcedBluetoothVoiceRoute = false
      Log.i(TAG, "Voice input using phone microphone fallback.")
      return
    }

    manager.mode = AudioManager.MODE_IN_COMMUNICATION
    val bluetoothDevice =
        manager.availableCommunicationDevices.firstOrNull { device ->
          device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
              device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
        }
    if (bluetoothDevice == null) {
      hasForcedBluetoothVoiceRoute = false
      Log.i(TAG, "No Bluetooth communication device found for voice input; using phone mic.")
      return
    }
    val routed =
        runCatching { manager.setCommunicationDevice(bluetoothDevice) }
            .onFailure { Log.w(TAG, "Failed to route voice input to Bluetooth device", it) }
            .getOrDefault(false)
    hasForcedBluetoothVoiceRoute = routed
    if (routed) {
      Log.i(TAG, "Voice input routed to Bluetooth device: ${bluetoothDevice.productName}")
    } else {
      Log.i(TAG, "Bluetooth route rejected; using phone mic fallback.")
    }
  }

  private fun clearVoiceAudioRoute() {
    val manager = audioManager ?: return
    if (hasForcedBluetoothVoiceRoute) {
      runCatching { manager.clearCommunicationDevice() }
          .onFailure { Log.w(TAG, "Failed to clear Bluetooth communication route", it) }
    }
    previousAudioMode?.let { mode -> manager.mode = mode }
    previousAudioMode = null
    hasForcedBluetoothVoiceRoute = false
  }

  private fun speechErrorMessage(error: Int): String {
    return when (error) {
      SpeechRecognizer.ERROR_NO_MATCH ->
          if (hasForcedBluetoothVoiceRoute) {
            "Could not understand speech from glasses mic (7)."
          } else {
            "Could not understand speech from phone mic (7)."
          }
      SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
          if (hasForcedBluetoothVoiceRoute) {
            "No speech detected from glasses mic in time (6)."
          } else {
            "No speech detected from phone mic in time (6)."
          }
      SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
          "Speech service network issue. Check internet."
      SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> "Too many voice requests. Wait a moment and try again."
      SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
          "Speech service disconnected (11). Retrying..."
      SpeechRecognizer.ERROR_AUDIO -> "Microphone/audio input error."
      SpeechRecognizer.ERROR_CLIENT -> "Speech client error. Try again."
      SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission required."
      SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Speech recognizer busy. Retry."
      SpeechRecognizer.ERROR_SERVER -> "Speech service server error."
      else -> "Voice error: $error"
    }
  }

  private suspend fun queryOpenAi(bitmap: Bitmap, question: String): String = withContext(Dispatchers.IO) {
    val apiKey = BuildConfig.OPENAI_API_KEY.trim()
    if (apiKey.isEmpty()) {
      throw IOException("OPENAI_API_KEY is missing. Rebuild app with OPENAI_API_KEY set.")
    }

    val encodedImage =
        ByteArrayOutputStream().use { stream ->
          bitmap.compress(Bitmap.CompressFormat.JPEG, AI_REQUEST_JPEG_QUALITY, stream)
          Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }
    val payload =
        JSONObject()
            .put("model", BuildConfig.OPENAI_MODEL)
            .put("max_tokens", 220)
            .put(
                "messages",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "content",
                            JSONArray()
                                .put(
                                    JSONObject()
                                        .put("type", "text")
                                        .put(
                                            "text",
                                            "$question Answer briefly using only what is visible in the camera image.",
                                        )
                                )
                                .put(
                                    JSONObject()
                                        .put("type", "image_url")
                                        .put(
                                            "image_url",
                                            JSONObject()
                                                .put("url", "data:image/jpeg;base64,$encodedImage"),
                                        )
                                ),
                        )
                )
            )
            .toString()

    val baseUrl = BuildConfig.OPENAI_BASE_URL.trim().trimEnd('/')
    val url = URL("$baseUrl/chat/completions")
    val connection =
        (url.openConnection() as HttpURLConnection).apply {
          requestMethod = "POST"
          setRequestProperty("Content-Type", "application/json")
          setRequestProperty("Authorization", "Bearer $apiKey")
          doOutput = true
          connectTimeout = 15_000
          readTimeout = AI_READ_TIMEOUT_MS
        }

    connection.outputStream.use { output -> output.write(payload.toByteArray()) }

    val code = connection.responseCode
    val responseStream = if (code in 200..299) connection.inputStream else connection.errorStream
    val responseBody = responseStream.bufferedReader().use { it.readText() }
    if (code !in 200..299) {
      throw IOException("OpenAI error $code: $responseBody")
    }

    try {
      val root = JSONObject(responseBody)
      val choices = root.optJSONArray("choices")
      val firstChoice = choices?.optJSONObject(0) ?: throw IOException("OpenAI returned no choices")
      val message = firstChoice.optJSONObject("message") ?: throw IOException("OpenAI response missing message")
      val content = message.opt("content")
      when (content) {
        is String -> content
        is JSONArray -> {
          val text = buildString {
            for (i in 0 until content.length()) {
              val part = content.optJSONObject(i) ?: continue
              val maybeText = part.optString("text").ifBlank { part.optString("content") }
              if (maybeText.isNotBlank()) {
                if (isNotEmpty()) append('\n')
                append(maybeText)
              }
            }
          }
          if (text.isBlank()) throw IOException("OpenAI response had empty content")
          text
        }
        else -> throw IOException("OpenAI response content format is unsupported")
      }
    } catch (e: JSONException) {
      throw IOException("Unexpected OpenAI response: $responseBody", e)
    }
  }

  private suspend fun sendToCommandCenter(
      question: String,
      answer: String?,
      aiError: String?,
      frame: Bitmap?,
  ): String =
      withContext(Dispatchers.IO) {
        val endpoint = BuildConfig.COMMAND_CENTER_URL.trim()
        if (endpoint.isEmpty()) {
          return@withContext COMMAND_CENTER_DISABLED
        }
        val commandCard = buildCommandCard(question = question, answer = answer, aiError = aiError)

        val encodedFrame =
            frame?.let {
              ByteArrayOutputStream().use { stream ->
                it.compress(Bitmap.CompressFormat.JPEG, 55, stream)
                Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
              }
            }

        val payload =
            JSONObject()
                .put("timestampEpochMs", System.currentTimeMillis())
                .put("eventType", QA_EVENT_TYPE)
                .put("question", question)
                .put("answer", answer ?: JSONObject.NULL)
                .put("aiError", aiError ?: JSONObject.NULL)
                .put("intent", commandCard?.intent ?: JSONObject.NULL)
                .put("commandCard", commandCard?.let { commandCardToJson(it) } ?: JSONObject.NULL)
                .put("imageMimeType", if (encodedFrame != null) "image/jpeg" else JSONObject.NULL)
                .put("frameJpegBase64", encodedFrame ?: JSONObject.NULL)
                .put("source", "meta-wearables-cameraaccess")
                .toString()

        val url = URL(endpoint)
        val connection =
            (url.openConnection() as HttpURLConnection).apply {
              requestMethod = "POST"
              setRequestProperty("Content-Type", "application/json")
              doOutput = true
              connectTimeout = 10_000
              readTimeout = 15_000
            }

        connection.outputStream.use { output -> output.write(payload.toByteArray()) }

        val code = connection.responseCode
        val responseStream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseBody = responseStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
          throw IOException("Command centre error $code: $responseBody")
        }
        COMMAND_CENTER_SUCCESS
      }

  private fun buildCommandCard(question: String, answer: String?, aiError: String?): CommandCard? {
    if (question.isBlank() && answer.isNullOrBlank() && aiError.isNullOrBlank()) {
      return null
    }
    val combined = "$question ${answer.orEmpty()} ${aiError.orEmpty()}".lowercase(Locale.ROOT)
    val gate = extractGateCode("$question ${answer.orEmpty()}")

    if (!aiError.isNullOrBlank()) {
      return CommandCard(
          intent = "retry_ai",
          title = "Retry visual query",
          action = "AI failed. Re-ask the question or switch to a clearer camera angle.",
          priority = "medium",
          confidence = 0.50,
          targetGate = gate,
          reason = aiError,
      )
    }

    if (containsAny(combined, "fire", "smoke", "flame", "burning")) {
      return CommandCard(
          intent = "alert_fire_response",
          title = "Fire safety alert",
          action = "Notify fire response and clear nearby passengers immediately.",
          priority = "critical",
          confidence = 0.92,
          targetGate = gate,
          reason = answer,
      )
    }

    if (containsAny(combined, "collapsed", "fainted", "unconscious", "injured", "bleeding", "medical")) {
      return CommandCard(
          intent = "alert_medical_response",
          title = "Medical assistance needed",
          action = "Dispatch medical responder and keep lane clear for access.",
          priority = "high",
          confidence = 0.88,
          targetGate = gate,
          reason = answer,
      )
    }

    if (
        containsAny(
            combined,
            "weapon",
            "fight",
            "aggressive",
            "suspicious",
            "intruder",
            "unattended bag",
            "security",
        )
    ) {
      return CommandCard(
          intent = "alert_security",
          title = "Security response advised",
          action = "Escalate to security desk and monitor the area until handover.",
          priority = "high",
          confidence = 0.84,
          targetGate = gate,
          reason = answer,
      )
    }

    if (containsAny(combined, "crowd", "crowded", "queue", "long line", "packed", "congestion", "busy")) {
      val gateLabel = gate ?: "nearby gate"
      return CommandCard(
          intent = "open_queue_relief_lane",
          title = "Queue relief at $gateLabel",
          action = "Open support lane and reassign one officer for crowd flow control.",
          priority = "medium",
          confidence = 0.78,
          targetGate = gate,
          reason = answer,
      )
    }

    if (containsAny(combined, "delay", "delayed", "cancelled", "canceled", "boarding", "flight status")) {
      return CommandCard(
          intent = "check_flight_status",
          title = "Verify flight status",
          action = "Cross-check departure feed and broadcast update to affected passengers.",
          priority = "medium",
          confidence = 0.74,
          targetGate = gate,
          reason = answer,
      )
    }

    return CommandCard(
        intent = "observe_only",
        title = "No immediate action",
        action = "Continue monitoring and ask follow-up if the scene changes.",
        priority = "low",
        confidence = 0.64,
        targetGate = gate,
        reason = answer,
    )
  }

  private fun commandCardToJson(card: CommandCard): JSONObject {
    return JSONObject()
        .put("intent", card.intent)
        .put("title", card.title)
        .put("action", card.action)
        .put("priority", card.priority)
        .put("confidence", card.confidence)
        .put("targetGate", card.targetGate ?: JSONObject.NULL)
        .put("reason", card.reason ?: JSONObject.NULL)
  }

  private fun extractGateCode(text: String): String? {
    val gateRegex = Regex("\\b([A-Z]\\d{1,2})\\b")
    return gateRegex.find(text.uppercase(Locale.ROOT))?.groupValues?.getOrNull(1)
  }

  private fun containsAny(haystack: String, vararg needles: String): Boolean {
    return needles.any { haystack.contains(it) }
  }

  private fun speakText(text: String) {
    val app = getApplication<Application>()
    val existing = textToSpeech
    if (existing != null) {
      ensureTtsProgressListener(existing)
      isSpeakingAnswer = true
      existing.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cameraaccess-reply")
      return
    }

    textToSpeech =
        TextToSpeech(app) { status ->
          if (status == TextToSpeech.SUCCESS) {
            val engine = textToSpeech ?: return@TextToSpeech
            ensureTtsProgressListener(engine)
            val setDefaultResult = engine.setLanguage(Locale.getDefault())
            if (setDefaultResult == TextToSpeech.LANG_MISSING_DATA ||
                setDefaultResult == TextToSpeech.LANG_NOT_SUPPORTED) {
              engine.setLanguage(Locale.US)
            }
            isSpeakingAnswer = true
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "cameraaccess-reply")
          } else {
            Log.w(TAG, "TextToSpeech initialization failed: $status")
            isSpeakingAnswer = false
          }
        }
  }

  private fun ensureTtsProgressListener(engine: TextToSpeech) {
    engine.setOnUtteranceProgressListener(
        object : UtteranceProgressListener() {
          override fun onStart(utteranceId: String?) {
            isSpeakingAnswer = true
          }

          override fun onDone(utteranceId: String?) {
            isSpeakingAnswer = false
            if (_uiState.value.isHandsFreeModeEnabled) {
              restartHandsFreeListening(250L)
            }
          }

          @Deprecated("Deprecated in Java")
          override fun onError(utteranceId: String?) {
            isSpeakingAnswer = false
            if (_uiState.value.isHandsFreeModeEnabled) {
              restartHandsFreeListening(250L)
            }
          }

          override fun onError(utteranceId: String?, errorCode: Int) {
            isSpeakingAnswer = false
            if (_uiState.value.isHandsFreeModeEnabled) {
              restartHandsFreeListening(250L)
            }
          }
        }
    )
  }

  private fun handleVideoFrame(videoFrame: VideoFrame) {
    // VideoFrame contains raw I420 video data in a ByteBuffer
    val buffer = videoFrame.buffer
    val dataSize = buffer.remaining()
    val byteArray = ByteArray(dataSize)

    // Save current position
    val originalPosition = buffer.position()
    buffer.get(byteArray)
    // Restore position
    buffer.position(originalPosition)

    // Convert I420 to NV21 format which is supported by Android's YuvImage
    val nv21 = convertI420toNV21(byteArray, videoFrame.width, videoFrame.height)
    val image = YuvImage(nv21, ImageFormat.NV21, videoFrame.width, videoFrame.height, null)
    val out =
        ByteArrayOutputStream().use { stream ->
          image.compressToJpeg(
              Rect(0, 0, videoFrame.width, videoFrame.height),
              PREVIEW_JPEG_QUALITY,
              stream,
          )
          stream.toByteArray()
        }

    val bitmap = BitmapFactory.decodeByteArray(out, 0, out.size)
    _uiState.update { it.copy(videoFrame = bitmap) }
  }

  private fun encodeJpegBase64(bitmap: Bitmap, quality: Int): String =
      ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
      }

  // Convert I420 (YYYYYYYY:UUVV) to NV21 (YYYYYYYY:VUVU)
  private fun convertI420toNV21(input: ByteArray, width: Int, height: Int): ByteArray {
    val output = ByteArray(input.size)
    val size = width * height
    val quarter = size / 4

    input.copyInto(output, 0, 0, size) // Y is the same

    for (n in 0 until quarter) {
      output[size + n * 2] = input[size + quarter + n] // V first
      output[size + n * 2 + 1] = input[size + n] // U second
    }
    return output
  }

  private fun handlePhotoData(photo: PhotoData) {
    val capturedPhoto =
        when (photo) {
          is PhotoData.Bitmap -> photo.bitmap
          is PhotoData.HEIC -> {
            val byteArray = ByteArray(photo.data.remaining())
            photo.data.get(byteArray)

            // Extract EXIF transformation matrix and apply to bitmap
            val exifInfo = getExifInfo(byteArray)
            val transform = getTransform(exifInfo)
            decodeHeic(byteArray, transform)
          }
        }
    _uiState.update { it.copy(capturedPhoto = capturedPhoto, isShareDialogVisible = true) }
  }

  // HEIC Decoding with EXIF transformation
  private fun decodeHeic(heicBytes: ByteArray, transform: Matrix): Bitmap {
    val bitmap = BitmapFactory.decodeByteArray(heicBytes, 0, heicBytes.size)
    return applyTransform(bitmap, transform)
  }

  private fun getExifInfo(heicBytes: ByteArray): ExifInterface? {
    return try {
      ByteArrayInputStream(heicBytes).use { inputStream -> ExifInterface(inputStream) }
    } catch (e: IOException) {
      Log.w(TAG, "Failed to read EXIF from HEIC", e)
      null
    }
  }

  private fun getTransform(exifInfo: ExifInterface?): Matrix {
    val matrix = Matrix()

    if (exifInfo == null) {
      return matrix // Identity matrix (no transformation)
    }

    when (
        exifInfo.getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    ) {
      ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> {
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_180 -> {
        matrix.postRotate(180f)
      }
      ExifInterface.ORIENTATION_FLIP_VERTICAL -> {
        matrix.postScale(1f, -1f)
      }
      ExifInterface.ORIENTATION_TRANSPOSE -> {
        matrix.postRotate(90f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_90 -> {
        matrix.postRotate(90f)
      }
      ExifInterface.ORIENTATION_TRANSVERSE -> {
        matrix.postRotate(270f)
        matrix.postScale(-1f, 1f)
      }
      ExifInterface.ORIENTATION_ROTATE_270 -> {
        matrix.postRotate(270f)
      }
      ExifInterface.ORIENTATION_NORMAL,
      ExifInterface.ORIENTATION_UNDEFINED -> {
        // No transformation needed
      }
    }

    return matrix
  }

  private fun applyTransform(bitmap: Bitmap, matrix: Matrix): Bitmap {
    if (matrix.isIdentity) {
      return bitmap
    }

    return try {
      val transformed = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
      if (transformed != bitmap) {
        bitmap.recycle()
      }
      transformed
    } catch (e: OutOfMemoryError) {
      Log.e(TAG, "Failed to apply transformation due to memory", e)
      bitmap
    }
  }

  override fun onCleared() {
    super.onCleared()
    stopStream()
    stateJob?.cancel()
    timerJob?.cancel()
    streamTimer.cleanup()
    speechRecognizer?.destroy()
    speechRecognizer = null
    clearVoiceAudioRoute()
    textToSpeech?.stop()
    textToSpeech?.shutdown()
    textToSpeech = null
  }

  class Factory(
      private val application: Application,
      private val wearablesViewModel: WearablesViewModel,
  ) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
      if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
        @Suppress("UNCHECKED_CAST", "KotlinGenericsCast")
        return StreamViewModel(
            application = application,
            wearablesViewModel = wearablesViewModel,
        )
            as T
      }
      throw IllegalArgumentException("Unknown ViewModel class")
    }
  }
}
