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

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.util.Log
import android.util.Base64
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.content.ContextCompat
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
import com.meta.wearable.dat.externalsampleapps.cameraaccess.webrtc.WebRtcClient
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Locale
import java.util.ArrayDeque
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.conflate
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

  private enum class AssistantIntent {
    VISION,
    AIRPORT_INFO,
    FLIGHT_INFO,
    GATE_INFO,
    ORG_INFO,
    MUSTERING_INFO,
  }

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
    private const val MAX_VOICE_RETRIES = 1
    private const val AI_REQUEST_JPEG_QUALITY = 50
    private const val AI_READ_TIMEOUT_MS = 45_000
    private const val STREAM_FPS = 15
    private const val PREVIEW_JPEG_QUALITY = 55
    private const val PREVIEW_MAX_FPS = 6L
    private const val LIVE_POV_MAX_FPS = 6L
    private const val PREVIEW_MIN_FRAME_INTERVAL_MS = 1000L / PREVIEW_MAX_FPS
    private const val LIVE_POV_MIN_FRAME_INTERVAL_MS = 1000L / LIVE_POV_MAX_FPS
    // Direct I420->RGB conversion is CPU-heavy in Kotlin; keep preview small for smooth UI.
    private const val PREVIEW_MAX_DIM_PX = 720
    private const val LIVE_POV_MAX_DIM_PX = 960
    private const val HANDS_FREE_RESTART_DELAY_MS = 450L
    private const val HANDS_FREE_RECONNECT_DELAY_MS = 1_200L
    private const val HANDS_FREE_STOP_MESSAGE = "Hands-free mode stopped."
    private const val PATROL_SCAN_INTERVAL_MS = 4_000L
    private const val PATROL_ALERT_COOLDOWN_MS = 20_000L
    private const val PATROL_START_MESSAGE = "Patrol mode started. I will alert you if I see a possible unattended item."
    private const val PATROL_STOP_MESSAGE = "Patrol mode stopped."
    private const val LIVE_POV_START_MESSAGE = "Live P O V sharing started."
    private const val LIVE_POV_STOP_MESSAGE = "Live P O V sharing stopped."
    private const val COMMAND_CENTER_SUCCESS = "Sent to command centre"
    private const val COMMAND_CENTER_DISABLED =
        "Set COMMAND_CENTER_URL to forward responses to command centre"
    private const val PEOPLE_DETECT_INTERVAL_MS = 650L
    private const val PEOPLE_DETECT_MAX_DIM_PX = 320
    private const val QA_EVENT_TYPE = "qa"
    private const val LOST_FOUND_EVENT_TYPE = "lost_found"
    private const val MAX_CHAT_MESSAGES = 24
    private const val CIOC_PHONE_NUMBER = "91002365"
    private val COUNTRY_SHORTFORM_SPEECH_MAP =
        mapOf(
            "UAE" to "United Arab Emirates",
            "USA" to "United States",
            "US" to "United States",
            "UK" to "United Kingdom",
            "KSA" to "Kingdom of Saudi Arabia",
            "PRC" to "People's Republic of China",
            "SGP" to "Singapore",
            "SG" to "Singapore",
            "AUS" to "Australia",
            "NZ" to "New Zealand",
        )
    private val AIRPORT_IATA_COUNTRY_SPEECH_MAP =
        mapOf(
            "DPS" to "Indonesia",
            "SIN" to "Singapore",
            "KUL" to "Malaysia",
            "CGK" to "Indonesia",
            "BKK" to "Thailand",
            "HKT" to "Thailand",
            "HKG" to "Hong Kong",
            "TPE" to "Taiwan",
            "ICN" to "South Korea",
            "NRT" to "Japan",
            "HND" to "Japan",
            "PVG" to "China",
            "PEK" to "China",
            "CAN" to "China",
            "MNL" to "Philippines",
            "SYD" to "Australia",
            "MEL" to "Australia",
        )
    private val TERMINAL_PATTERN = Regex("\\bT([1-4])\\b", RegexOption.IGNORE_CASE)
    private val FLIGHT_PATTERN = Regex("\\b([A-Z]{2,3}\\s?\\d{1,4}[A-Z]?)\\b", RegexOption.IGNORE_CASE)
    private val GATE_PATTERN = Regex("\\b([A-Z]\\d{1,2}[A-Z]?)\\b", RegexOption.IGNORE_CASE)
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
  private var patrolJob: Job? = null
  private var lastPreviewFrameAtMs: Long = 0L
  private var lastLivePovFrameAtMs: Long = 0L
  private var speechRecognizer: SpeechRecognizer? = null
  private var latestVoiceContext: Context? = null
  private var audioManager: AudioManager? = null
  private var previousAudioMode: Int? = null
  private var hasForcedBluetoothVoiceRoute = false
  private var preferBluetoothVoiceRoute = true
  private var bluetoothNoSpeechFailureCount = 0
  private var suppressNextVoiceError = false
  private var voiceRetryCount: Int = 0
  private var isWakeWordListening = false
  private var textToSpeech: TextToSpeech? = null
  private var isSpeakingAnswer = false
  private var lastPatrolAlertAtMs: Long = 0L
  private var lastPatrolAlertText: String? = null
  private var livePovClient: WebRtcClient? = null
  private var i420FrameBuffer = ByteArray(0)
  private var previewBitmapA: Bitmap? = null
  private var previewBitmapB: Bitmap? = null
  private var previewBitmapWriteIsA = true
  private var previewArgbBuffer: ByteBuffer? = null
  private var peopleCounter: PeopleCounter? = null
  private var peopleDetectJob: Job? = null
  private var lastPeopleDetectAtMs: Long = 0L
  private var peopleDetectBitmap: Bitmap? = null
  private var peopleDetectCanvas: Canvas? = null
  private val peopleDetectPaint = Paint(Paint.FILTER_BITMAP_FLAG)
  private val peopleDetectDstRect = Rect()
  private val pendingDescribeQuestions = ArrayDeque<String>()
  private var describeJob: Job? = null

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
    videoJob =
        viewModelScope.launch(Dispatchers.Default) {
          streamSession.videoStream.conflate().collect { handleVideoFrame(it) }
        }
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
    peopleDetectJob?.cancel()
    peopleDetectJob = null
    stopPatrolMode()
    stopLivePovSharing()
    stopVoiceDescribe(disableHandsFreeMode = true)
    streamSession?.close()
    streamSession = null
    lastPreviewFrameAtMs = 0L
    streamTimer.stopTimer()
    previewBitmapA?.recycle()
    previewBitmapA = null
    previewBitmapB?.recycle()
    previewBitmapB = null
    peopleDetectBitmap?.recycle()
    peopleDetectBitmap = null
    peopleDetectCanvas = null
    _uiState.value.peopleCountSnapshot?.recycle()
    _uiState.update { INITIAL_STATE }
  }

  fun togglePeopleCounting() {
    val next = !_uiState.value.isPeopleCountingEnabled
    _uiState.update {
      it.copy(
          isPeopleCountingEnabled = next,
          peopleCount = if (next) it.peopleCount else null,
          livePeopleBoxes = if (next || it.isLiveBoxesEnabled) it.livePeopleBoxes else emptyList(),
      )
    }
  }

  fun toggleLiveBoxes() {
    val next = !_uiState.value.isLiveBoxesEnabled
    _uiState.update { it.copy(isLiveBoxesEnabled = next, livePeopleBoxes = if (next) it.livePeopleBoxes else emptyList()) }
  }

  fun showPeopleCountPage() {
    _uiState.update { it.copy(isPeopleCountPageVisible = true) }
  }

  fun hidePeopleCountPage() {
    _uiState.update { it.copy(isPeopleCountPageVisible = false) }
  }

  fun capturePeopleCountSnapshot() {
    val frame = _uiState.value.videoFrame ?: return
    viewModelScope.launch(Dispatchers.Default) {
      val counter = peopleCounter ?: PeopleCounter(getApplication()).also { peopleCounter = it }
      val maxDim = maxOf(frame.width, frame.height).coerceAtLeast(1)
      val preferredMaxDimPx = counter.preferredInputMaxDimPx
      val scale = minOf(1f, preferredMaxDimPx.toFloat() / maxDim.toFloat())
      val targetWidth = (frame.width * scale).toInt().coerceAtLeast(1)
      val targetHeight = (frame.height * scale).toInt().coerceAtLeast(1)

      val snapshot = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(snapshot)
      peopleDetectDstRect.set(0, 0, targetWidth, targetHeight)
      canvas.drawBitmap(frame, null, peopleDetectDstRect, peopleDetectPaint)

      val detections = runCatching { counter.detectPeople(snapshot) }.getOrDefault(emptyList())

      val normalized =
          detections.map { det ->
            val box = det.boundingBox
            NormalizedBox(
                left = (box.left / targetWidth.toFloat()).coerceIn(0f, 1f),
                top = (box.top / targetHeight.toFloat()).coerceIn(0f, 1f),
                right = (box.right / targetWidth.toFloat()).coerceIn(0f, 1f),
                bottom = (box.bottom / targetHeight.toFloat()).coerceIn(0f, 1f),
                score = det.score,
            )
          }

      _uiState.update {
        it.copy(
            peopleCountSnapshot = snapshot,
            peopleCountSnapshotBoxes = normalized,
            peopleCountSnapshotCount = normalized.size,
        )
      }
    }
  }

  fun toggleLivePovSharing() {
    if (_uiState.value.isLivePovSharingEnabled) {
      stopLivePovSharing(question = null)
    } else {
      startLivePovSharing(question = "start live pov")
    }
  }

  fun togglePatrolMode() {
    if (_uiState.value.isPatrolModeEnabled) {
      stopPatrolMode(question = null)
    } else {
      startPatrolMode(question = "start patrol")
    }
  }

  fun capturePhoto() {
    if (uiState.value.streamSessionState == StreamSessionState.STREAMING) {
      viewModelScope.launch { streamSession?.capturePhoto()?.onSuccess { handlePhotoData(it) } }
    }
  }

  fun describeCurrentFrame(question: String? = null) {
    val normalizedQuestion = question?.trim().orEmpty()
    if (normalizedQuestion.isEmpty()) {
      val missingQuestionError = "Please type or speak your question first."
      _uiState.update {
        it.copy(
            isDescribeLoading = false,
            describeResult = null,
            describeError = missingQuestionError,
            commandCenterStatus = null,
            commandCenterError = null,
        )
      }
      appendChatMessage(ChatRole.ASSISTANT, "Error: $missingQuestionError")
      return
    }
    if (handleLocalPhoneCommand(normalizedQuestion)) {
      return
    }
    appendChatMessage(ChatRole.USER, normalizedQuestion)

    if (_uiState.value.isDescribeLoading || describeJob?.isActive == true) {
      pendingDescribeQuestions.addLast(normalizedQuestion)
      appendChatMessage(ChatRole.ASSISTANT, "Queued. I’ll answer after the current question.")
      return
    }

    startDescribe(normalizedQuestion, appendUserMessage = false)
  }

  private fun startDescribe(question: String, appendUserMessage: Boolean) {
    if (appendUserMessage) {
      appendChatMessage(ChatRole.USER, question)
    }
    describeJob =
        viewModelScope.launch {
          try {
            val intent = routeQuestion(question)
            val frame = _uiState.value.videoFrame
            if (intent == AssistantIntent.VISION && frame == null) {
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
              return@launch
            }

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
                runCatching {
                      when (intent) {
                        AssistantIntent.AIRPORT_INFO -> answerAirportInfo(question)
                        AssistantIntent.FLIGHT_INFO -> answerFlightInfo(question)
                        AssistantIntent.GATE_INFO -> answerGateInfo(question)
                        AssistantIntent.ORG_INFO -> answerOrgInfo(question)
                        AssistantIntent.MUSTERING_INFO -> answerMusteringInfo(question)
                        AssistantIntent.VISION -> queryOpenAi(checkNotNull(frame), question)
                      }
                    }
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
            runCatching { sendToCommandCenter(question, describeResult, describeError, frame) }
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
          } finally {
            describeJob = null
            startNextQueuedDescribeIfAny()
            if (_uiState.value.isHeyCasEnabled && !_uiState.value.isHandsFreeModeEnabled && !isSpeakingAnswer) {
              restartHeyCasListening(250L)
            }
          }
        }
  }

  private fun startNextQueuedDescribeIfAny() {
    if (_uiState.value.isDescribeLoading || describeJob?.isActive == true) return
    if (pendingDescribeQuestions.isEmpty()) return
    val next = pendingDescribeQuestions.removeFirst()
    startDescribe(next, appendUserMessage = false)
  }

  private fun routeQuestion(question: String): AssistantIntent {
    val normalized = question.lowercase(Locale.ROOT)
    val isAirportInfoQuestion =
        listOf(
                "skytrain",
                "shuttle bus",
                "transfer",
                "transit",
                "terminal",
                "how do i go",
                "how to go",
                "how do i get",
                "how to get",
                "where is t1",
                "where is t2",
                "where is t3",
                "where is t4",
            )
            .any { normalized.contains(it) } ||
            TERMINAL_PATTERN.containsMatchIn(question)
    if (isAirportInfoQuestion &&
        !normalized.contains("gate ") &&
        !FLIGHT_PATTERN.containsMatchIn(question)) {
      return AssistantIntent.AIRPORT_INFO
    }
    if (
        listOf(
                "vp",
                "vice president",
                "head ops",
                "head of ops",
                "who is my vp",
                "who is my head ops",
                "alvin lim",
                "jeremin",
                "ho wai san",
                "charles lim yik min",
                "tian beng",
                "ng tian beng",
                "goh soo lim",
                "dr jaclyn lee",
                "jaclyn lee",
                "laura low",
                "yeo teck guan",
                "ronald poon",
                "ng boon gay",
                "lee hock heng",
                "leonard oh",
                "brett pickens",
                "raahul kumar",
                "group ceo",
                "chief financial officer",
                "chief human resources officer",
                "group chief technology officer",
            )
            .any { normalized.contains(it) }
    ) {
      return AssistantIntent.ORG_INFO
    }
    if (
        listOf(
                "mustering",
                "safety hazard",
                "apd rt",
                "lost and found",
                "efof",
                "x-ray",
                "xray",
                "lag",
                "100ml",
                "mbs check",
                "passenger permission",
                "search procedure",
                "lll",
                "lock look leave",
                "personal devices",
                "active duty",
                "ppe",
                "ied",
            )
            .any { normalized.contains(it) }
    ) {
      return AssistantIntent.MUSTERING_INFO
    }
    if (FLIGHT_PATTERN.containsMatchIn(question) ||
        listOf("flight", "boarding", "departure", "arrival", "airline").any { normalized.contains(it) }) {
      return AssistantIntent.FLIGHT_INFO
    }
    if (GATE_PATTERN.containsMatchIn(question) &&
        listOf("gate", "busy", "crowd", "queue", "status").any { normalized.contains(it) }) {
      return AssistantIntent.GATE_INFO
    }
    return AssistantIntent.VISION
  }

  private fun answerAirportInfo(question: String): String {
    val normalized = question.lowercase(Locale.ROOT)
    val terminals = TERMINAL_PATTERN.findAll(question).mapNotNull { it.groupValues.getOrNull(1) }.toList()
    val fromTerminal = terminals.getOrNull(0)
    val toTerminal = terminals.getOrNull(1)

    if ((fromTerminal == "1" && toTerminal == "4") || (fromTerminal == "4" && toTerminal == "1")) {
      return "Use the shuttle bus between Terminal 1 and Terminal 4. In transit, the bus departs from T1 Gate C21 and runs 24 hours daily."
    }
    if ((fromTerminal == "3" && toTerminal == "4") || (fromTerminal == "4" && toTerminal == "3")) {
      return "Use the shuttle bus between Terminal 3 and Terminal 4. In transit, the bus departs from T3 Immigration Hall A and runs 24 hours daily."
    }
    if ((fromTerminal == "2" && toTerminal == "4") || (fromTerminal == "4" && toTerminal == "2")) {
      return "Terminal 4 is reached by shuttle bus, not Skytrain. Go via the T1 or T3 transit shuttle bus connection. The T1, T2 and T3 side connects to T4 by bus 24 hours daily."
    }
    if ((fromTerminal == "1" && toTerminal == "2") || (fromTerminal == "2" && toTerminal == "1")) {
      return "For Terminal 1 to Terminal 2 in transit, use the Skytrain between transfer zones D and E. It operates daily from 0430 to 0130."
    }
    if ((fromTerminal == "1" && toTerminal == "3") || (fromTerminal == "3" && toTerminal == "1")) {
      return "For Terminal 1 to Terminal 3 in transit, use the Skytrain between transfer zones C and B. It operates daily from 0430 to 0130."
    }
    if ((fromTerminal == "2" && toTerminal == "3") || (fromTerminal == "3" && toTerminal == "2")) {
      return "For Terminal 2 to Terminal 3 in transit, you can use Skytrain between transfer zones E and B from 0430 to 0130 daily, or between F and A from 0500 to 0200 daily."
    }
    if (normalized.contains("t4") && (normalized.contains("bus") || normalized.contains("shuttle"))) {
      return "Terminal 4 is connected to the other terminals by shuttle bus in the transit area. The bus departs from T1 Gate C21 and T3 Immigration Hall A, and it runs 24 hours daily."
    }
    if (normalized.contains("skytrain")) {
      return "Within the transit area, Terminals 1, 2 and 3 are connected by Skytrain. T1 to T2 uses D to E, T1 to T3 uses C to B, and T2 to T3 uses E to B or F to A depending on the link."
    }
    if (normalized.contains("transfer") || normalized.contains("transit")) {
      return "In transit, Terminals 1, 2 and 3 connect by Skytrain, while Terminal 4 connects by shuttle bus. Tell me your from and to terminals, for example T3 to T4, and I can give the exact transfer route."
    }
    return "I can answer terminal transfer questions without using the camera. Ask me things like how to go from T1 to T4, where the Skytrain link is, or how to transfer between terminals in transit."
  }

  private suspend fun answerFlightInfo(question: String): String = withContext(Dispatchers.IO) {
    val flightNo =
        FLIGHT_PATTERN.find(question)?.groupValues?.getOrNull(1)?.replace(" ", "")?.uppercase(Locale.ROOT)
            ?: throw IOException("Please tell me the flight number, for example SQ318.")
    val endpoint = assistantBackendBaseUrl()?.let { "$it/api/assistant/flight?flightno=$flightNo" }
        ?: throw IOException("Backend assistant is not configured. Set COMMAND_CENTER_URL first.")
    val root = fetchJson(endpoint)
    if (!root.optBoolean("ok", false)) {
      throw IOException(root.optString("error").ifBlank { "Flight lookup failed." })
    }
    if (!root.optBoolean("found", false)) {
      val cagUrl = root.optString("cagUrl")
      val message = root.optString("message").ifBlank { "I could not confirm that flight." }
      return@withContext if (cagUrl.isNotBlank()) "$message Official CAG page: $cagUrl" else message
    }
    val flight = root.optJSONObject("flight") ?: throw IOException("Flight lookup returned no flight details.")
    val gate = flight.optString("gate").ifBlank { "gate not available" }
    val status = flight.optString("status").ifBlank { "status unavailable" }
    val destination = flight.optString("destination").ifBlank { "destination unavailable" }
    val timing = flight.optString("timing").ifBlank { "time unavailable" }
    return@withContext "$flightNo to $destination is $status. Gate: $gate. Time: $timing."
  }

  private suspend fun answerGateInfo(question: String): String = withContext(Dispatchers.IO) {
    val gate =
        GATE_PATTERN.find(question)?.groupValues?.getOrNull(1)?.uppercase(Locale.ROOT)
            ?: throw IOException("Please tell me the gate code, for example C17L.")
    val endpoint = assistantBackendBaseUrl()?.let { "$it/api/assistant/gate?gate=$gate" }
        ?: throw IOException("Backend assistant is not configured. Set COMMAND_CENTER_URL first.")
    val root = fetchJson(endpoint)
    if (!root.optBoolean("ok", false)) {
      throw IOException(root.optString("error").ifBlank { "Gate lookup failed." })
    }
    if (!root.optBoolean("found", false)) {
      return@withContext "I could not find live or snapshot information for gate $gate."
    }
    val live = root.optJSONObject("live")
    val snapshot = root.optJSONObject("snapshot")
    val flight = cleanBackendText(snapshot?.optString("flightNo"))
    val destination = cleanBackendText(snapshot?.optString("destination"))
    val status = cleanBackendText(snapshot?.optString("status"))
    val latestQuestion = cleanBackendText(live?.optString("question"))
    val latestAnswer = cleanBackendText(live?.optString("answer"))
    val latestError = cleanBackendText(live?.optString("aiError"))
    return@withContext when {
      latestError.isNotBlank() ->
          "Gate $gate has a recent alert but the last AI check failed: $latestError"
      latestAnswer.isNotBlank() ->
          "Latest update for gate $gate: $latestAnswer"
      latestQuestion.isNotBlank() ->
          "Latest gate $gate note: $latestQuestion"
      flight.isNotBlank() || destination.isNotBlank() || status.isNotBlank() ->
          "Gate $gate is associated with ${if (flight.isNotBlank()) flight else "a scheduled flight"}${if (destination.isNotBlank()) " to $destination" else ""}. Current status: ${if (status.isNotBlank()) status else "unavailable"}."
      else -> "I found gate $gate, but there is no recent live activity."
    }
  }

  private fun cleanBackendText(value: String?): String {
    if (value == null) return ""
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    return if (trimmed.equals("null", ignoreCase = true)) "" else trimmed
  }

  private fun answerOrgInfo(question: String): String {
    val normalized = question.lowercase(Locale.ROOT)
    return when {
      normalized.contains("ng tian beng") || normalized.contains("tian beng") ->
          "Ng Tian Beng is the President and Group C E O."
      normalized.contains("alvin lim") ->
          "Alvin Lim is the Vice President and Head of Certis Aviation Security."
      normalized.contains("ho wai san") ->
          "Ho Wai San is the Assistant Vice President and Head of Development."
      normalized.contains("charles lim yik min") ->
          "Charles Lim Yik Min is the Manager and Acting Head of Operation Technology."
      normalized.contains("goh soo lim") ->
          "Goh Soo Lim is the Group Chief Financial Officer."
      normalized.contains("dr jaclyn lee") || normalized.contains("jaclyn lee") ->
          "Dr Jaclyn Lee is the Chief Human Resources Officer and Chief Executive of Certis Corporate University."
      normalized.contains("laura low") ->
          "Laura Low is the General Counsel and Group Head of Legal and Corporate Affairs."
      normalized.contains("yeo teck guan") ->
          "Yeo Teck Guan is the Group Chief Technology Officer."
      normalized.contains("ronald poon") ->
          "Ronald Poon is the Chief Executive, Singapore."
      normalized.contains("ng boon gay") ->
          "Ng Boon Gay is the Deputy Chief Executive, Singapore Operations."
      normalized.contains("lee hock heng") ->
          "Lee Hock Heng is the Deputy Chief Executive, Singapore Commercial, and Chairman E X C O, Qatar."
      normalized.contains("leonard oh") ->
          "Leonard Oh is the Chief Executive of the Technology Services Business."
      normalized.contains("brett pickens") ->
          "Brett Pickens is the Chief Executive, Australia."
      normalized.contains("raahul kumar") ->
          "Raahul Kumar is the Chief Executive, International and Robotics."
      normalized.contains("group ceo") || normalized.contains("president") ->
          "Ng Tian Beng is the President and Group C E O."
      normalized.contains("chief financial officer") || normalized.contains("cfo") ->
          "Goh Soo Lim is the Group Chief Financial Officer."
      normalized.contains("chief human resources officer") || normalized.contains("human resources officer") ->
          "Dr Jaclyn Lee is the Chief Human Resources Officer and Chief Executive of Certis Corporate University."
      normalized.contains("group chief technology officer") || normalized.contains("cto") ->
          "Yeo Teck Guan is the Group Chief Technology Officer."
      normalized.contains("jeremin") ->
          "Jeremin is your Head Ops."
      normalized.contains("head ops") || normalized.contains("head of ops") ->
          "Your Head Ops is Jeremin."
      normalized.contains("vp") || normalized.contains("vice president") ->
          "Your VP is Alvin Lim, Vice President and Head of Certis Aviation Security."
      else ->
          "Your VP is Alvin Lim, and your Head Ops is Jeremin."
    }
  }

  private fun answerMusteringInfo(question: String): String {
    val normalized = question.lowercase(Locale.ROOT)
    return when {
      normalized.contains("safety hazard") ->
          "Today's mustering says officers must look out for any safety hazards and report them immediately."
      normalized.contains("apd rt") || normalized.contains("rt modules") ->
          "Today's mustering includes sharing of APD RT modules operandi, including recent pass and failure RT."
      normalized.contains("airport facilities") ->
          "Today's reminder is that airport facilities are meant for passengers."
      normalized.contains("lost and found") ->
          "Today's mustering includes a reminder on lost and found procedure and directive."
      normalized.contains("efof") ->
          "Today's mustering says to submit all EFOF for all flights."
      normalized.contains("lag") || normalized.contains("100ml") || normalized.contains("mbs check") ->
          "Today's mustering says X-ray operators must not assume LAG or bottles less than 100ml are safe. Refer every LAG detected for MBS check."
      normalized.contains("passenger permission") || normalized.contains("conducting search") || normalized.contains("search procedure") ->
          "Today's mustering says officers must seek passenger permission before conducting a search."
      normalized.contains("lll") || normalized.contains("lock look leave") ->
          "Today's mustering says to practise the LLL steps: Lock, Look, Leave."
      normalized.contains("personal devices") || normalized.contains("active duty") || normalized.contains("official call") ->
          "Today's mustering says there is strictly no use of personal devices during active duty. If an official call must be answered, the officer must stop all activities, remain stationary, and finish the call before continuing with duty."
      normalized.contains("ppe") ->
          "Today's mustering says to strictly adhere to PPE requirements and ensure PPE is in good condition."
      normalized.contains("ied") || normalized.contains("organic mass") || normalized.contains("x-ray") || normalized.contains("xray") ->
          "Today's mustering says X-ray operators must look for other possible IED components, not just organic mass. Stop and refer if unsure."
      else ->
          "Today's PBSU T3 PM shift mustering covers safety hazards, APD RT sharing, lost and found, EFOF submission, LAG and MBS referral, passenger permission before search, LLL steps, no personal device use during active duty, PPE compliance, and IED awareness for X-ray operators."
    }
  }

  private fun assistantBackendBaseUrl(): String? {
    val endpoint = BuildConfig.COMMAND_CENTER_URL.trim().trimEnd('/')
    if (endpoint.isBlank()) return null
    return if (endpoint.endsWith("/api/events")) endpoint.removeSuffix("/api/events") else endpoint.substringBefore("/api/")
  }

  private fun fetchJson(urlString: String): JSONObject {
    val url = URL(urlString)
    val connection =
        (url.openConnection() as HttpURLConnection).apply {
          requestMethod = "GET"
          setRequestProperty("Accept", "application/json")
          connectTimeout = 10_000
          readTimeout = 20_000
        }
    val code = connection.responseCode
    val responseStream = if (code in 200..299) connection.inputStream else connection.errorStream
    val body = responseStream?.bufferedReader()?.use { it.readText() }.orEmpty()
    if (code !in 200..299) {
      throw IOException("Backend error $code: $body")
    }
    return try {
      JSONObject(body)
    } catch (e: JSONException) {
      throw IOException("Backend returned invalid JSON: $body", e)
    }
  }

  private fun handleLocalPhoneCommand(question: String): Boolean {
    val normalized = question.lowercase(Locale.ROOT)
    if (isStartLivePovCommand(normalized)) {
      return startLivePovSharing(question)
    }
    if (isStopLivePovCommand(normalized)) {
      return stopLivePovSharing(question)
    }
    if (isLostFoundCommand(normalized)) {
      captureLostFoundReport(question)
      return true
    }
    if (isStartPatrolCommand(normalized)) {
      return startPatrolMode(question)
    }
    if (isStopPatrolCommand(normalized)) {
      return stopPatrolMode(question)
    }
    val hasCiocTarget = normalized.contains("cioc") || normalized.contains("c i o c")
    val wantsCall = normalized.contains("call") || normalized.contains("connect")
    if (!hasCiocTarget || !wantsCall) {
      return false
    }

    appendChatMessage(ChatRole.USER, question)
    val appContext = getApplication<Application>().applicationContext
    val hasCallPermission =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
    val action = if (hasCallPermission) Intent.ACTION_CALL else Intent.ACTION_DIAL
    val callIntent =
        Intent(action, Uri.parse("tel:$CIOC_PHONE_NUMBER")).apply {
          flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

    return runCatching { appContext.startActivity(callIntent) }
        .map {
          val resultText =
              if (hasCallPermission) {
                "Connecting CIOC now ($CIOC_PHONE_NUMBER)."
              } else {
                "Opening CIOC dialer now ($CIOC_PHONE_NUMBER)."
              }
          _uiState.update {
            it.copy(
                isDescribeLoading = false,
                describeResult = resultText,
                describeError = null,
                commandCenterStatus = null,
                commandCenterError = null,
            )
          }
          appendChatMessage(ChatRole.ASSISTANT, resultText)
          speakText("Connecting you to C I O C now.")
          true
        }
        .getOrElse {
          val err = "Could not start CIOC call."
          _uiState.update {
            state ->
              state.copy(
                  isDescribeLoading = false,
                  describeResult = null,
                  describeError = err,
                  commandCenterStatus = null,
                  commandCenterError = null,
              )
          }
          appendChatMessage(ChatRole.ASSISTANT, "Error: $err")
          false
        }
  }

  private fun isLostFoundCommand(text: String): Boolean {
    return text == "lost and found" ||
        text == "lost and found report" ||
        text == "report lost and found" ||
        text == "lost found"
  }

  private fun isStartLivePovCommand(text: String): Boolean {
    return listOf(
            "share live pov",
            "start live pov",
            "start live p o v",
            "start pov sharing",
            "share live view",
            "share what i see",
        )
        .any { text.contains(it) }
  }

  private fun isStopLivePovCommand(text: String): Boolean {
    return listOf(
            "stop live pov",
            "stop live p o v",
            "stop pov sharing",
            "stop sharing pov",
            "stop share live view",
        )
        .any { text.contains(it) }
  }

  private fun startLivePovSharing(question: String): Boolean {
    appendChatMessage(ChatRole.USER, question)
    if (_uiState.value.isLivePovSharingEnabled) {
      val alreadyRunning = "Live P O V sharing is already running."
      _uiState.update {
        it.copy(
            isDescribeLoading = false,
            describeResult = alreadyRunning,
            describeError = null,
            commandCenterStatus = null,
            commandCenterError = null,
        )
      }
      appendChatMessage(ChatRole.ASSISTANT, alreadyRunning)
      speakText(alreadyRunning)
      return true
    }

    val frame = _uiState.value.videoFrame
    if (frame == null) {
      val noFrameError = "No frame available yet for live P O V sharing."
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
      return true
    }

    livePovClient?.stop()
    livePovClient = null
    lastLivePovFrameAtMs = 0L
    _uiState.update {
      it.copy(
          isLivePovSharingEnabled = true,
          isDescribeLoading = false,
          describeResult = LIVE_POV_START_MESSAGE,
          describeError = null,
          commandCenterStatus = null,
          commandCenterError = null,
      )
    }
    appendChatMessage(ChatRole.ASSISTANT, LIVE_POV_START_MESSAGE)
    speakText(LIVE_POV_START_MESSAGE)
    val appContext = getApplication<Application>().applicationContext
    viewModelScope.launch(Dispatchers.IO) {
      runCatching {
            WebRtcClient(
                context = appContext,
                signalingUrl = BuildConfig.LIVE_POV_SIGNALING_URL,
                room = BuildConfig.LIVE_POV_ROOM,
                listener =
                    object : WebRtcClient.Listener {
                      override fun onStatusChanged(message: String) {
                        _uiState.update {
                          it.copy(
                              commandCenterStatus = message,
                              commandCenterError = null,
                          )
                        }
                      }

                      override fun onError(message: String) {
                        _uiState.update {
                          it.copy(
                              commandCenterError = message,
                              commandCenterStatus = null,
                          )
                        }
                      }
                    },
            )
          }
          .onSuccess { client ->
            if (!_uiState.value.isLivePovSharingEnabled) {
              client.stop()
              return@onSuccess
            }
            livePovClient = client
            client.start()
          }
          .onFailure { error ->
            Log.e(TAG, "Failed to start Live POV client", error)
            val message = error.message ?: "Unknown error while starting Live P O V sharing."
            _uiState.update {
              it.copy(
                  isLivePovSharingEnabled = false,
                  describeError = message,
                  commandCenterError = message,
                  commandCenterStatus = null,
              )
            }
          }
    }
    return true
  }

  private fun stopLivePovSharing(question: String? = null): Boolean {
    if (question != null) {
      appendChatMessage(ChatRole.USER, question)
    }
    livePovClient?.stop()
    livePovClient = null
    lastLivePovFrameAtMs = 0L
    val wasEnabled = _uiState.value.isLivePovSharingEnabled
    _uiState.update {
      it.copy(
          isLivePovSharingEnabled = false,
          isDescribeLoading = false,
          describeResult = if (question != null || wasEnabled) LIVE_POV_STOP_MESSAGE else it.describeResult,
          describeError = null,
          commandCenterStatus = null,
          commandCenterError = null,
      )
    }
    if (question != null || wasEnabled) {
      appendChatMessage(ChatRole.ASSISTANT, LIVE_POV_STOP_MESSAGE)
      if (question != null) {
        speakText(LIVE_POV_STOP_MESSAGE)
      }
    }
    return wasEnabled || question != null
  }

  private fun captureLostFoundReport(question: String) {
    appendChatMessage(ChatRole.USER, question)
    val frame = _uiState.value.videoFrame
    if (frame == null) {
      val noFrameError = "No frame available yet for lost and found."
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
          runCatching { describeLostFoundItem(frame) }
              .onFailure { Log.e(TAG, "Lost and found capture failed", it) }

      val describeResult = result.getOrNull()
      val describeError = result.exceptionOrNull()?.message
      when {
        !describeResult.isNullOrBlank() -> appendChatMessage(ChatRole.ASSISTANT, describeResult)
        !describeError.isNullOrBlank() -> appendChatMessage(ChatRole.ASSISTANT, "Error: $describeError")
      }
      if (!describeResult.isNullOrBlank()) {
        speakText(describeResult)
      } else if (!describeError.isNullOrBlank()) {
        speakText("I could not capture the lost item clearly. Please try again.")
      }

      var commandCenterStatus: String? = null
      var commandCenterError: String? = null
      runCatching {
            sendToCommandCenter(
                question = "lost and found",
                answer = describeResult,
                aiError = describeError,
                frame = frame,
                eventType = LOST_FOUND_EVENT_TYPE,
            )
          }
          .onSuccess { commandCenterStatus = it }
          .onFailure {
            commandCenterError = it.message
            Log.e(TAG, "Lost and found send failed", it)
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
    }
  }

  private fun isStartPatrolCommand(text: String): Boolean {
    return listOf(
            "patroling start",
            "patrolling start",
            "start patrol",
            "start patrolling",
            "start patrol mode",
            "patrol mode start",
            "begin patrol",
        )
        .any { text.contains(it) }
  }

  private fun isStopPatrolCommand(text: String): Boolean {
    return listOf(
            "patroling stop",
            "patrolling stop",
            "stop patrol",
            "stop patrolling",
            "stop patrol mode",
            "patrol mode stop",
            "end patrol",
        )
        .any { text.contains(it) }
  }

  private fun startPatrolMode(question: String): Boolean {
    appendChatMessage(ChatRole.USER, question)
    if (_uiState.value.isPatrolModeEnabled) {
      val alreadyRunning = "Patrol mode is already running."
      _uiState.update {
        it.copy(
            isDescribeLoading = false,
            describeResult = alreadyRunning,
            describeError = null,
            commandCenterStatus = null,
            commandCenterError = null,
        )
      }
      appendChatMessage(ChatRole.ASSISTANT, alreadyRunning)
      speakText(alreadyRunning)
      return true
    }

    patrolJob?.cancel()
    lastPatrolAlertAtMs = 0L
    lastPatrolAlertText = null
    _uiState.update {
      it.copy(
          isPatrolModeEnabled = true,
          isDescribeLoading = false,
          describeResult = PATROL_START_MESSAGE,
          describeError = null,
          commandCenterStatus = null,
          commandCenterError = null,
      )
    }
    appendChatMessage(ChatRole.ASSISTANT, PATROL_START_MESSAGE)
    speakText("Patrol mode started.")
    patrolJob =
        viewModelScope.launch {
          while (_uiState.value.isPatrolModeEnabled) {
            runCatching { performPatrolScan() }
                .onFailure { Log.w(TAG, "Patrol scan failed", it) }
            delay(PATROL_SCAN_INTERVAL_MS)
          }
        }
    return true
  }

  private fun stopPatrolMode(question: String? = null): Boolean {
    if (question != null) {
      appendChatMessage(ChatRole.USER, question)
    }
    patrolJob?.cancel()
    patrolJob = null
    lastPatrolAlertAtMs = 0L
    lastPatrolAlertText = null
    val wasEnabled = _uiState.value.isPatrolModeEnabled
    _uiState.update {
      it.copy(
          isPatrolModeEnabled = false,
          isDescribeLoading = false,
          describeResult = if (question != null || wasEnabled) PATROL_STOP_MESSAGE else it.describeResult,
          describeError = null,
          commandCenterStatus = null,
          commandCenterError = null,
      )
    }
    if (question != null || wasEnabled) {
      appendChatMessage(ChatRole.ASSISTANT, PATROL_STOP_MESSAGE)
      if (question != null) {
        speakText("Patrol mode stopped.")
      }
    }
    return wasEnabled || question != null
  }

  private suspend fun performPatrolScan() {
    if (_uiState.value.isDescribeLoading || isSpeakingAnswer) {
      return
    }
    val frame = _uiState.value.videoFrame ?: return
    val patrolResult = queryPatrolForUnattendedItem(frame)
    if (patrolResult == "CLEAR") {
      return
    }

    val nowMs = System.currentTimeMillis()
    if (patrolResult == lastPatrolAlertText && nowMs - lastPatrolAlertAtMs < PATROL_ALERT_COOLDOWN_MS) {
      return
    }

    lastPatrolAlertText = patrolResult
    lastPatrolAlertAtMs = nowMs
    appendChatMessage(ChatRole.ASSISTANT, patrolResult)
    _uiState.update {
      it.copy(
          describeResult = patrolResult,
          describeError = null,
      )
    }
    speakText(patrolResult)

    var commandCenterStatus: String? = null
    var commandCenterError: String? = null
    runCatching {
          sendToCommandCenter(
              question = "Patrol auto-check for unattended items",
              answer = patrolResult,
              aiError = null,
              frame = frame,
          )
        }
        .onSuccess { commandCenterStatus = it }
        .onFailure {
          commandCenterError = it.message
          Log.e(TAG, "Patrol command centre send failed", it)
        }

    _uiState.update {
      it.copy(
          commandCenterStatus = commandCenterStatus,
          commandCenterError = commandCenterError,
      )
    }
  }

  private suspend fun queryPatrolForUnattendedItem(bitmap: Bitmap): String =
      withContext(Dispatchers.IO) {
        val patrolPrompt =
            "Check only for unattended personal items. If you can see a bag, luggage, suitcase, backpack, purse, or wallet with no person beside it, holding it, or clearly attending to it, reply exactly as ALERT: Possible unattended item detected: <item>. <brief reason>. If no such item is visible, reply exactly CLEAR."
        queryOpenAi(bitmap, patrolPrompt)
            .trim()
            .replace('\n', ' ')
            .replace(Regex("\\s+"), " ")
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

    // Hands-free and wake-word both use SpeechRecognizer; keep them mutually exclusive.
    if (_uiState.value.isHeyCasEnabled) {
      toggleHeyCas(context)
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

  fun toggleHeyCas(context: Context) {
    val next = !_uiState.value.isHeyCasEnabled
    if (!next) {
      stopHeyCasListening()
      _uiState.update { it.copy(isHeyCasEnabled = false) }
      return
    }

    // Wake-word and hands-free both use SpeechRecognizer; keep them mutually exclusive.
    if (_uiState.value.isHandsFreeModeEnabled) {
      stopVoiceDescribe(disableHandsFreeMode = true)
    }
    _uiState.update { it.copy(isHeyCasEnabled = true, describeError = null, commandCenterStatus = null, commandCenterError = null) }
    startHeyCasListening(context)
  }

  private fun stopHeyCasListening() {
    isWakeWordListening = false
    suppressNextVoiceError = true
    speechRecognizer?.stopListening()
    speechRecognizer?.cancel()
    clearVoiceAudioRoute()
    _uiState.update { it.copy(isListening = false) }
  }

  private fun restartHeyCasListening(delayMs: Long = HANDS_FREE_RESTART_DELAY_MS) {
    if (!_uiState.value.isHeyCasEnabled) return
    if (_uiState.value.isHandsFreeModeEnabled) return
    if (_uiState.value.isDescribeLoading) return
    if (_uiState.value.isListening) return
    val context = latestVoiceContext ?: getApplication<Application>().applicationContext
    viewModelScope.launch {
      delay(delayMs)
      if (_uiState.value.isHeyCasEnabled && !_uiState.value.isHandsFreeModeEnabled && !_uiState.value.isListening && !_uiState.value.isDescribeLoading) {
        startHeyCasListening(context)
      }
    }
  }

  private fun startHeyCasListening(context: Context) {
    latestVoiceContext = context.applicationContext
    prepareVoiceAudioRoute(context, preferBluetooth = preferBluetoothVoiceRoute)
    if (!SpeechRecognizer.isRecognitionAvailable(context)) {
      _uiState.update {
        it.copy(
            voiceHeardText = null,
            isListening = false,
            isHeyCasEnabled = false,
            describeError = "Speech recognition not available",
        )
      }
      return
    }

    speechRecognizer?.destroy()
    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
    suppressNextVoiceError = false
    voiceRetryCount = 0
    isWakeWordListening = true
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
      putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
      putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, Locale.getDefault())
      putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
      putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
      putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, false)
      // Shorter times so we can loop quickly if it doesn't hear the wake word.
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 900L)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1600L)
      putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1100L)
    }

    val wakeRegex = Regex("\\bhey\\s*c\\s*a\\s*s\\b", RegexOption.IGNORE_CASE)
    val wakeRegexCompact = Regex("\\bhey\\s*cas\\b", RegexOption.IGNORE_CASE)

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
            if (!isWakeWordListening) return
            if (suppressNextVoiceError) {
              suppressNextVoiceError = false
              _uiState.update { it.copy(isListening = false) }
              return
            }

            val isNoSpeechError =
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

            _uiState.update { it.copy(isListening = false) }
            if (_uiState.value.isHeyCasEnabled && isNoSpeechError) {
              restartHeyCasListening(250L)
            } else if (_uiState.value.isHeyCasEnabled) {
              restartHeyCasListening(HANDS_FREE_RECONNECT_DELAY_MS)
            }
          }

          override fun onResults(results: Bundle?) {
            if (!isWakeWordListening) return
            val text =
                results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?: ""
            val normalizedText = text.trim()
            _uiState.update { it.copy(isListening = false, voiceHeardText = normalizedText.takeIf { it.isNotBlank() }) }

            if (!_uiState.value.isHeyCasEnabled) return
            if (normalizedText.isBlank()) {
              restartHeyCasListening(250L)
              return
            }

            val hasWake =
                wakeRegex.containsMatchIn(normalizedText) ||
                    wakeRegexCompact.containsMatchIn(normalizedText) ||
                    normalizedText.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "").contains("heycas")

            if (!hasWake) {
              restartHeyCasListening(200L)
              return
            }

            // Remove wake phrase and treat the remainder as the question.
            val stripped =
                normalizedText
                    .replace(wakeRegex, "")
                    .replace(wakeRegexCompact, "")
                    .trim()
                    .trimStart(',', '.', '-', ':', ';')
                    .trim()

            if (stripped.isNotBlank()) {
              describeCurrentFrame(stripped)
            } else {
              appendChatMessage(ChatRole.ASSISTANT, "Yes?")
              speakText("Yes?")
            }

            restartHeyCasListening(450L)
          }

          override fun onPartialResults(partialResults: Bundle?) {}
          override fun onEvent(eventType: Int, params: Bundle?) {}
        }

    speechRecognizer?.setRecognitionListener(listener)
    speechRecognizer?.startListening(intent)
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

    isWakeWordListening = false
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
    isWakeWordListening = false
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
      eventType: String = QA_EVENT_TYPE,
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

        val gate = extractGateCode(question)
        val payload =
            JSONObject()
                .put("timestampEpochMs", System.currentTimeMillis())
                .put("eventType", eventType)
                .put("question", question)
                .put("answer", answer ?: JSONObject.NULL)
                .put("aiError", aiError ?: JSONObject.NULL)
                .put("intent", commandCard?.intent ?: JSONObject.NULL)
                .put("commandCard", commandCard?.let { commandCardToJson(it) } ?: JSONObject.NULL)
                .put("gate", gate ?: JSONObject.NULL)
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

  private suspend fun describeLostFoundItem(bitmap: Bitmap): String = withContext(Dispatchers.IO) {
    val prompt =
        "Identify the main lost property item visible in this image. Answer in one short sentence starting with 'Lost item:' and include the item type, visible color, and any obvious brand or distinctive feature only if clearly visible."
    queryOpenAi(bitmap, prompt)
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
            "unattended",
            "unattended bag",
            "luggage",
            "wallet",
            "suitcase",
            "backpack",
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
    // Matches gates like B1, E27R, C17L.
    val gateRegex = Regex("\\b([A-Z]\\d{1,2}[A-Z]?)\\b")
    return gateRegex.find(text.uppercase(Locale.ROOT))?.groupValues?.getOrNull(1)
  }

  private fun containsAny(haystack: String, vararg needles: String): Boolean {
    return needles.any { haystack.contains(it) }
  }

  private fun speakText(text: String) {
    val app = getApplication<Application>()
    val speechText = expandCountryShortformsForSpeech(text)
    Log.d(TAG, "TTS input='$text' normalized='$speechText'")
    val existing = textToSpeech
    if (existing != null) {
      ensureTtsProgressListener(existing)
      isSpeakingAnswer = true
      existing.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, "cameraaccess-reply")
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
            engine.speak(speechText, TextToSpeech.QUEUE_FLUSH, null, "cameraaccess-reply")
          } else {
            Log.w(TAG, "TextToSpeech initialization failed: $status")
            isSpeakingAnswer = false
          }
        }
  }

  private fun expandCountryShortformsForSpeech(input: String): String {
    var normalized =
        input
            .replace(Regex("\\bU\\.S\\.A\\.?\\b"), "United States")
            .replace(Regex("\\bU\\.S\\.?\\b"), "United States")
            .replace(Regex("\\bU\\.K\\.?\\b"), "United Kingdom")
    AIRPORT_IATA_COUNTRY_SPEECH_MAP.forEach { (iata, country) ->
      normalized =
          normalized
              .replace(Regex("(?i)(?<![A-Za-z])$iata(?![A-Za-z])"), country)
              .replace(Regex("(?i)(?<![A-Za-z])${iata[0]}\\s*\\.?\\s*${iata[1]}\\s*\\.?\\s*${iata[2]}(?![A-Za-z])"), country)
    }
    return Regex("\\b[A-Za-z]{2,4}\\b").replace(normalized) { match ->
      val token = match.value.uppercase(Locale.ROOT)
      COUNTRY_SHORTFORM_SPEECH_MAP[token] ?: match.value
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
    val nowMs = System.currentTimeMillis()
    val livePovEnabled = _uiState.value.isLivePovSharingEnabled
    val minFrameIntervalMs =
        if (livePovEnabled) LIVE_POV_MIN_FRAME_INTERVAL_MS else PREVIEW_MIN_FRAME_INTERVAL_MS
    if (nowMs - lastPreviewFrameAtMs < minFrameIntervalMs) {
      return
    }
    lastPreviewFrameAtMs = nowMs

    // VideoFrame contains raw I420 video data in a ByteBuffer
    val buffer = videoFrame.buffer
    val dataSize = buffer.remaining()
    if (i420FrameBuffer.size < dataSize) {
      i420FrameBuffer = ByteArray(dataSize)
    }

    // Save current position
    val originalPosition = buffer.position()
    buffer.get(i420FrameBuffer, 0, dataSize)
    // Restore position
    buffer.position(originalPosition)

    val maxDimPx = if (livePovEnabled) LIVE_POV_MAX_DIM_PX else PREVIEW_MAX_DIM_PX
    val maxDim = maxOf(videoFrame.width, videoFrame.height).coerceAtLeast(1)
    val scale = minOf(1f, maxDimPx.toFloat() / maxDim.toFloat())
    val outWidth = (videoFrame.width * scale).toInt().coerceAtLeast(1)
    val outHeight = (videoFrame.height * scale).toInt().coerceAtLeast(1)

    val bitmap =
        if (previewBitmapWriteIsA) {
          val current = previewBitmapA
          if (current?.width == outWidth && current.height == outHeight) current
          else {
            current?.recycle()
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also { previewBitmapA = it }
          }
        } else {
          val current = previewBitmapB
          if (current?.width == outWidth && current.height == outHeight) current
          else {
            current?.recycle()
            Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888).also { previewBitmapB = it }
          }
        }

    val neededBytes = outWidth * outHeight * 4
    val argbBuffer =
        previewArgbBuffer?.takeIf { it.capacity() == neededBytes }
            ?: ByteBuffer.allocateDirect(neededBytes).also { previewArgbBuffer = it }
    argbBuffer.clear()
    NativeYuv.i420ToArgb(
        i420 = i420FrameBuffer,
        width = videoFrame.width,
        height = videoFrame.height,
        outBuffer = argbBuffer,
        outWidth = outWidth,
        outHeight = outHeight,
    )
    argbBuffer.rewind()
    bitmap.copyPixelsFromBuffer(argbBuffer)

    previewBitmapWriteIsA = !previewBitmapWriteIsA
    _uiState.update { it.copy(videoFrame = bitmap) }
    if (livePovEnabled && nowMs - lastLivePovFrameAtMs >= LIVE_POV_MIN_FRAME_INTERVAL_MS) {
      lastLivePovFrameAtMs = nowMs
      livePovClient?.sendFrame(bitmap)
    }

    val peopleCountingEnabled = _uiState.value.isPeopleCountingEnabled
    val liveBoxesEnabled = _uiState.value.isLiveBoxesEnabled
    if ((peopleCountingEnabled || liveBoxesEnabled) &&
        nowMs - lastPeopleDetectAtMs >= PEOPLE_DETECT_INTERVAL_MS &&
        (peopleDetectJob?.isActive != true)
    ) {
      lastPeopleDetectAtMs = nowMs
      peopleDetectJob =
          viewModelScope.launch(Dispatchers.Default) {
            val counter = peopleCounter ?: PeopleCounter(getApplication()).also { peopleCounter = it }
            val preferredMaxDimPx = counter.preferredInputMaxDimPx
            val maxDim = maxOf(bitmap.width, bitmap.height).coerceAtLeast(1)
            val scale = minOf(1f, preferredMaxDimPx.toFloat() / maxDim.toFloat())
            val targetWidth = (bitmap.width * scale).toInt().coerceAtLeast(1)
            val targetHeight = (bitmap.height * scale).toInt().coerceAtLeast(1)

            val detectBitmap =
                peopleDetectBitmap?.takeIf { it.width == targetWidth && it.height == targetHeight }
                    ?: Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also {
                      peopleDetectBitmap?.recycle()
                      peopleDetectBitmap = it
                      peopleDetectCanvas = Canvas(it)
                    }

            val canvas = peopleDetectCanvas ?: Canvas(detectBitmap).also { peopleDetectCanvas = it }
            peopleDetectDstRect.set(0, 0, targetWidth, targetHeight)
            canvas.drawBitmap(bitmap, null, peopleDetectDstRect, peopleDetectPaint)

            val objectDetections =
                if (peopleCountingEnabled || liveBoxesEnabled) {
                  runCatching { counter.detectObjects(detectBitmap) }.getOrDefault(emptyList())
                } else {
                  emptyList()
                }

            val countInFront =
                if (!peopleCountingEnabled) {
                  null
                } else {
                  val w = targetWidth.toFloat().coerceAtLeast(1f)
                  val h = targetHeight.toFloat().coerceAtLeast(1f)
                  val centerMinX = w * 0.25f
                  val centerMaxX = w * 0.75f
                  val centerMinY = h * 0.20f
                  val centerMaxY = h * 0.90f
                  objectDetections.count { det ->
                    val label = det.label?.lowercase().orEmpty()
                    val isPerson = label == "person" || label.contains("person") || (label.isBlank() && det.index == 0)
                    if (!isPerson) return@count false
                    val box = det.boundingBox
                    val cx = (box.left + box.right) / 2f
                    val cy = (box.top + box.bottom) / 2f
                    if (cx < centerMinX || cx > centerMaxX || cy < centerMinY || cy > centerMaxY) return@count false
                    val area = (box.width() * box.height()) / (w * h)
                    area >= 0.015f
                  }
                }
            val boxes =
                if (liveBoxesEnabled) {
                  objectDetections
                      .asSequence()
                      .map { det ->
                    val box = det.boundingBox
                    NormalizedBox(
                        left = (box.left / targetWidth.toFloat()).coerceIn(0f, 1f),
                        top = (box.top / targetHeight.toFloat()).coerceIn(0f, 1f),
                        right = (box.right / targetWidth.toFloat()).coerceIn(0f, 1f),
                        bottom = (box.bottom / targetHeight.toFloat()).coerceIn(0f, 1f),
                        score = det.score,
                        label = det.label,
                    )
                  }
                      .toList()
                } else {
                  emptyList()
                }
            _uiState.update {
              it.copy(
                  peopleCount = if (peopleCountingEnabled) countInFront else it.peopleCount,
                  livePeopleBoxes = boxes,
              )
            }
          }
    }
  }

  private fun encodeJpegBase64(bitmap: Bitmap, quality: Int): String =
      ByteArrayOutputStream().use { stream ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
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
