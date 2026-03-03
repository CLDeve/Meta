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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamSessionState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
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
                  if (streamUiState.isListening) stringResource(id = R.string.voice_listening_button_short)
                  else stringResource(id = R.string.voice_button_short_title),
              onClick = {
                if (streamUiState.isListening) {
                  streamViewModel.stopVoiceDescribe()
                } else {
                  streamViewModel.startVoiceDescribe(context)
                }
              },
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
private fun DescribeOverlay(
    streamUiState: StreamUiState,
    modifier: Modifier = Modifier,
) {
  if (
      !streamUiState.isDescribeLoading &&
          !streamUiState.isListening &&
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
