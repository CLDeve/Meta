/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// StreamScreen - DAT Camera Streaming UI
//
// This composable demonstrates the main streaming UI for DAT camera functionality. It shows how to
// display live video from wearable devices and handle photo capture.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.ChatMessage
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.ChatRole
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamUiState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.typography.AppTypography

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory =
                StreamViewModel.Factory(
                    application = (LocalActivity.current as ComponentActivity).application,
                    wearablesViewModel = wearablesViewModel,
                ),
        ),
) {
  val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()
  val context = LocalContext.current

  LaunchedEffect(Unit) { streamViewModel.startStream() }

  val backgroundBrush =
      Brush.linearGradient(listOf(Color(0xFF0B1224), Color(0xFF0D1F3C), Color(0xFF0F274A)))

  Box(modifier = modifier.fillMaxSize().background(brush = backgroundBrush)) {
    streamUiState.videoFrame?.let { videoFrame ->
      val previewShape = RoundedCornerShape(28.dp)
      Box(
          modifier = Modifier.fillMaxSize().padding(16.dp),
          contentAlignment = Alignment.Center,
      ) {
        Image(
            bitmap = videoFrame.asImageBitmap(),
            contentDescription = stringResource(R.string.live_stream),
            modifier =
                Modifier.fillMaxSize()
                    .shadow(elevation = 12.dp, shape = previewShape, clip = true)
                    .clip(previewShape)
                    .border(
                        width = 1.dp,
                        color = Color.White.copy(alpha = 0.08f),
                        shape = previewShape,
                    ),
            contentScale = ContentScale.Crop,
        )
      }
    }
    if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
      )
    }

    DescribeOverlay(
        streamUiState = streamUiState,
        modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
    )

    ChatOverlay(
        streamUiState = streamUiState,
        onSendQuestion = { streamViewModel.describeCurrentFrame(it) },
        modifier =
            Modifier.align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 124.dp)
                .imePadding(),
    )

    Box(modifier = Modifier.fillMaxSize().padding(all = 24.dp)) {
      Surface(
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .fillMaxWidth(),
          color = Color.White.copy(alpha = 0.08f),
          shape = RoundedCornerShape(18.dp),
      ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 12.dp)
                    .height(64.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          SwitchButton(
              label = stringResource(R.string.stop_stream_button_short_title),
              onClick = {
                streamViewModel.stopStream()
                wearablesViewModel.navigateToDeviceSelection()
              },
              isDestructive = true,
              modifier = Modifier.weight(1f),
          )

          SwitchButton(
              label = stringResource(R.string.describe_button_short_title),
              onClick = { streamViewModel.describeCurrentFrame() },
              enabled = !streamUiState.isDescribeLoading,
              modifier = Modifier.weight(1f),
          )

          SwitchButton(
              label =
                  when {
                    streamUiState.isListening ->
                        stringResource(id = R.string.voice_listening_button_short)
                    streamUiState.isHandsFreeModeEnabled ->
                        stringResource(id = R.string.hands_free_button_on_short_title)
                    else -> stringResource(id = R.string.hands_free_button_short_title)
                  },
              onClick = { streamViewModel.toggleHandsFreeVoice(context) },
              enabled = !streamUiState.isDescribeLoading,
              modifier = Modifier.weight(1f),
          )

          // Timer button
          TimerButton(
              timerMode = streamUiState.timerMode,
              onClick = { streamViewModel.cycleTimerMode() },
          )
          // Photo capture button
          CaptureButton(
              onClick = { streamViewModel.capturePhoto() },
          )
        }
      }
    }

    // Countdown timer display
    streamUiState.remainingTimeSeconds?.let { seconds ->
      val minutes = seconds / 60
      val remainingSeconds = seconds % 60
      Text(
          text = stringResource(id = R.string.time_remaining, minutes, remainingSeconds),
          color = Color.White,
          modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
          textAlign = TextAlign.Center,
          style = AppTypography.Body,
      )
    }
  }

  streamUiState.capturedPhoto?.let { photo ->
    if (streamUiState.isShareDialogVisible) {
      SharePhotoDialog(
          photo = photo,
          onDismiss = { streamViewModel.hideShareDialog() },
          onShare = { bitmap ->
            streamViewModel.sharePhoto(bitmap)
            streamViewModel.hideShareDialog()
          },
      )
    }
  }
}

@Composable
private fun ChatOverlay(
    streamUiState: StreamUiState,
    onSendQuestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  var draft by rememberSaveable { mutableStateOf("") }
  val canSend = draft.isNotBlank() && !streamUiState.isDescribeLoading

  fun submitDraft() {
    val normalized = draft.trim()
    if (normalized.isEmpty() || streamUiState.isDescribeLoading) {
      return
    }
    onSendQuestion(normalized)
    draft = ""
  }

  Surface(
      modifier = modifier,
      color = Color.White.copy(alpha = 0.08f),
      shape = RoundedCornerShape(16.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text(
          text = stringResource(id = R.string.chat_title),
          color = Color.White,
          style = AppTypography.Body,
      )

      if (streamUiState.chatMessages.isNotEmpty()) {
        val recentMessages = streamUiState.chatMessages.takeLast(6)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          items(recentMessages) { message -> ChatBubble(message = message) }
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.weight(1f),
            placeholder = {
              Text(
                  text = stringResource(id = R.string.chat_input_placeholder),
                  style = AppTypography.Body.copy(color = Color.LightGray),
              )
            },
            singleLine = true,
            enabled = !streamUiState.isDescribeLoading,
            textStyle = AppTypography.Body.copy(color = Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submitDraft() }),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Button(onClick = { submitDraft() }, enabled = canSend) {
          Text(text = stringResource(id = R.string.chat_send_button), style = AppTypography.Button)
        }
      }
    }
  }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
  val isUser = message.role == ChatRole.USER
  val bubbleColor =
      if (isUser) {
        Color(0xFF2A5EA8).copy(alpha = 0.72f)
      } else {
        Color(0xFF15233D).copy(alpha = 0.82f)
      }

  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    Surface(
        color = bubbleColor,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
      Text(
          text = message.text,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
          color = Color.White,
          style = AppTypography.Body.copy(color = Color.White),
      )
    }
  }
}

@Composable
private fun DescribeOverlay(
    streamUiState: StreamUiState,
    modifier: Modifier = Modifier,
) {
  if (
      !streamUiState.isDescribeLoading &&
          !streamUiState.isListening &&
          !streamUiState.isHandsFreeModeEnabled &&
          streamUiState.describeResult.isNullOrEmpty() &&
          streamUiState.describeError.isNullOrEmpty() &&
          streamUiState.commandCenterStatus.isNullOrEmpty() &&
          streamUiState.commandCenterError.isNullOrEmpty() &&
          streamUiState.voiceHeardText.isNullOrEmpty()
  ) {
    return
  }

  Surface(
      modifier = modifier.fillMaxWidth(),
      color = Color.White.copy(alpha = 0.08f),
      shape = RoundedCornerShape(16.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
  ) {
    Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text(
          text = stringResource(id = R.string.describe_result_label),
          color = Color.White,
          style = AppTypography.Body,
      )

      if (streamUiState.isHandsFreeModeEnabled) {
        Text(
            text = stringResource(id = R.string.hands_free_mode_active),
            color = Color(0xFFB9F6CA),
            style = AppTypography.Body,
        )
      }

      when {
        streamUiState.isDescribeLoading -> {
          Text(
              text = stringResource(id = R.string.describe_loading),
              color = Color.White,
              style = AppTypography.Body,
          )
        }
        streamUiState.isListening -> {
          Text(
              text = stringResource(id = R.string.voice_listening),
              color = Color.White,
              style = AppTypography.Body,
          )
        }
        !streamUiState.describeResult.isNullOrEmpty() -> {
          Text(
              text = streamUiState.describeResult,
              color = Color.White,
              style = AppTypography.Body,
          )
        }
        !streamUiState.describeError.isNullOrEmpty() -> {
          Text(
              text = "${stringResource(id = R.string.describe_error_prefix)} ${streamUiState.describeError}",
              color = Color(0xFFFFB4A9),
              style = AppTypography.Body,
          )
        }
      }

      streamUiState.voiceHeardText?.let { heard ->
        Text(
            text = "${stringResource(id = R.string.voice_heard_label)} $heard",
            color = Color.LightGray,
            style = AppTypography.Body.copy(color = Color.LightGray),
        )
      }

      streamUiState.commandCenterStatus?.let { status ->
        Text(
            text = status,
            color = Color(0xFFB9F6CA),
            style = AppTypography.Body,
        )
      }

      streamUiState.commandCenterError?.let { error ->
        Text(
            text = "${stringResource(id = R.string.command_center_error_prefix)} $error",
            color = Color(0xFFFFB4A9),
            style = AppTypography.Body,
        )
      }
    }
  }
}
