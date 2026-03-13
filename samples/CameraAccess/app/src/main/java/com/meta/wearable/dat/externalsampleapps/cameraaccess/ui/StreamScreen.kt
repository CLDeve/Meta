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
import androidx.compose.ui.platform.LocalContext
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.StopCircle
import androidx.compose.material3.Icon
import androidx.compose.foundation.BorderStroke
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
    autoStartVoiceNonce: Long = 0L,
    onAutoStartVoiceConsumed: (Long) -> Unit = {},
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
  var isChatExpanded by rememberSaveable { mutableStateOf(false) }
  val videoFrame = streamUiState.videoFrame
  val chatMessages = streamUiState.chatMessages
  val isDescribeLoading = streamUiState.isDescribeLoading

  LaunchedEffect(Unit) { streamViewModel.startStream() }
  LaunchedEffect(autoStartVoiceNonce) {
    if (autoStartVoiceNonce > 0L) {
      streamViewModel.startVoiceDescribe(context)
      onAutoStartVoiceConsumed(autoStartVoiceNonce)
    }
  }

  val backgroundBrush =
      Brush.linearGradient(listOf(Color(0xFF02060C), Color(0xFF07101F), Color(0xFF0A1830)))

  Box(modifier = modifier.fillMaxSize().background(brush = backgroundBrush)) {
    val previewShape = RoundedCornerShape(0.dp)
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
      videoFrame?.let { currentFrame ->
        Image(
            bitmap = currentFrame.asImageBitmap(),
            contentDescription = stringResource(R.string.live_stream),
            modifier =
                Modifier.fillMaxSize()
                    .clip(previewShape),
            contentScale = ContentScale.Crop,
        )
      } ?: Surface(
          modifier =
              Modifier.fillMaxSize()
                  .clip(previewShape),
          color = Color(0xFF0E1D36),
      ) {
        Box(contentAlignment = Alignment.Center) {
          Text(
              text = stringResource(id = R.string.live_stream),
              style = AppTypography.Body,
              color = Color.White.copy(alpha = 0.7f),
          )
        }
      }
      // Reduce harsh glare from bright scenes and improve overlay readability.
      Box(
          modifier =
              Modifier.fillMaxSize()
                  .clip(previewShape)
                  .background(
                      Brush.verticalGradient(
                          listOf(
                              Color.Black.copy(alpha = 0.48f),
                              Color.Transparent,
                              Color.Black.copy(alpha = 0.44f),
                          )
                      )
                  )
      )
    }
    if (streamUiState.streamSessionState == StreamSessionState.STARTING) {
      CircularProgressIndicator(
          modifier = Modifier.align(Alignment.Center),
      )
    }

    StatusOverlay(
        isListening = streamUiState.isListening,
        isDescribeLoading = streamUiState.isDescribeLoading,
        isHandsFreeModeEnabled = streamUiState.isHandsFreeModeEnabled,
        isPatrolModeEnabled = streamUiState.isPatrolModeEnabled,
        isLivePovSharingEnabled = streamUiState.isLivePovSharingEnabled,
        isPeopleCountingEnabled = streamUiState.isPeopleCountingEnabled,
        peopleCount = streamUiState.peopleCount,
        describeResult = streamUiState.describeResult,
        describeError = streamUiState.describeError,
        commandCenterStatus = streamUiState.commandCenterStatus,
        commandCenterError = streamUiState.commandCenterError,
        voiceHeardText = streamUiState.voiceHeardText,
        modifier =
            Modifier.align(Alignment.TopStart)
                .padding(horizontal = 14.dp, vertical = 16.dp),
    )

    ChatOverlay(
        isDescribeLoading = isDescribeLoading,
        chatMessages = chatMessages,
        isExpanded = isChatExpanded,
        onToggleExpanded = { isChatExpanded = !isChatExpanded },
        onSendQuestion = { streamViewModel.describeCurrentFrame(it) },
        modifier =
            Modifier.align(Alignment.BottomStart)
                .widthIn(max = 420.dp)
                .padding(start = 14.dp, end = 14.dp, bottom = 106.dp)
                .imePadding(),
    )

    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
      Surface(
          modifier =
              Modifier.align(Alignment.BottomCenter)
                  .navigationBarsPadding()
                  .fillMaxWidth(),
          color = Color(0xFF071424).copy(alpha = 0.82f),
          shape = RoundedCornerShape(28.dp),
          border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
      ) {
        Row(
            modifier =
                Modifier.fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
          MiniActionButton(
              icon = Icons.Filled.StopCircle,
              label = stringResource(R.string.stop_stream_button_short_title),
              onClick = {
                streamViewModel.stopStream()
                wearablesViewModel.navigateToDeviceSelection()
              },
              tint = Color(0xFFFFC1C7),
              modifier = Modifier.weight(1f),
          )

          MiniActionButton(
              icon = Icons.Filled.GraphicEq,
              label = stringResource(R.string.describe_button_short_title),
              onClick = { streamViewModel.startVoiceDescribe(context) },
              enabled = !isDescribeLoading,
              tint = Color(0xFF9ED3FF),
              modifier = Modifier.weight(1f),
          )

          MiniActionButton(
              icon = Icons.AutoMirrored.Filled.Chat,
              label = if (isChatExpanded) stringResource(R.string.chat_hide_button) else stringResource(R.string.chat_show_button),
              onClick = { isChatExpanded = !isChatExpanded },
              tint = Color(0xFFFFD97C),
              modifier = Modifier.weight(1f),
          )

          MiniActionButton(
              icon = Icons.Filled.People,
              label = if (streamUiState.isPeopleCountingEnabled) "Counting" else "Count",
              onClick = { streamViewModel.togglePeopleCounting() },
              tint = Color(0xFFB9F6CA),
              modifier = Modifier.weight(1f),
          )

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
    isDescribeLoading: Boolean,
    chatMessages: List<ChatMessage>,
    isExpanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSendQuestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  var draft by rememberSaveable { mutableStateOf("") }
  val canSend = draft.isNotBlank() && !isDescribeLoading

  fun submitDraft() {
    val normalized = draft.trim()
    if (normalized.isEmpty() || isDescribeLoading) {
      return
    }
    onSendQuestion(normalized)
    draft = ""
  }

  if (!isExpanded) {
    Surface(
        modifier = modifier,
        color = Color(0xFF071424).copy(alpha = 0.68f),
        shape = RoundedCornerShape(999.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
      Row(
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Surface(
            color = Color(0xFFFFD97C).copy(alpha = 0.14f),
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, Color(0xFFFFD97C).copy(alpha = 0.26f)),
        ) {
          Text(
              text = "Chat",
              modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
              color = Color(0xFFFFD97C),
              style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
          )
        }
        Text(
            text = chatMessages.lastOrNull()?.text ?: stringResource(id = R.string.chat_collapsed_hint),
            color = Color(0xFFD7E7FF),
            style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onToggleExpanded) {
          Text(text = stringResource(id = R.string.chat_show_button), style = AppTypography.Button)
        }
      }
    }
    return
  }

  Surface(
      modifier = modifier,
      color = Color(0xFF06111E).copy(alpha = 0.92f),
      shape = RoundedCornerShape(22.dp),
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
  ) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Surface(
            color = Color.White.copy(alpha = 0.08f),
            shape = RoundedCornerShape(999.dp),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.1f)),
        ) {
          Text(
              text = "Assistant",
              modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
              color = Color.White,
              style = AppTypography.Body.copy(fontWeight = FontWeight.SemiBold),
          )
        }
        Spacer(modifier = Modifier.weight(1f))
        if (isDescribeLoading) {
          Text(
              text = stringResource(id = R.string.describe_loading),
              color = Color(0xFFB9F6CA),
              style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
          )
        }
        Spacer(modifier = Modifier.width(6.dp))
        TextButton(onClick = onToggleExpanded) {
          Text(
              text = if (isExpanded) stringResource(id = R.string.chat_hide_button) else stringResource(id = R.string.chat_show_button),
              style = AppTypography.Button,
          )
        }
      }

      if (chatMessages.isNotEmpty()) {
        val recentMessages = chatMessages.takeLast(3)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().heightIn(max = 108.dp),
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
                  style = AppTypography.Body.copy(color = Color(0xFF8EA5C7)),
              )
            },
            singleLine = true,
            enabled = !isDescribeLoading,
            textStyle = AppTypography.Body.copy(color = Color.White),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { submitDraft() }),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Button(
            onClick = { submitDraft() },
            enabled = canSend,
            modifier = Modifier.widthIn(min = 76.dp),
        ) {
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
        Color(0xFF2C77E6).copy(alpha = 0.9f)
      } else {
        Color(0xFF10233E).copy(alpha = 0.9f)
      }

  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
  ) {
    Surface(
        color = bubbleColor,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
    ) {
      Text(
          text = message.text,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
          color = Color.White,
          style = AppTypography.Body.copy(color = Color.White, fontSize = MaterialTheme.typography.bodyMedium.fontSize),
          maxLines = 3,
      )
    }
  }
}

@Composable
private fun StatusOverlay(
    isListening: Boolean,
    isDescribeLoading: Boolean,
    isHandsFreeModeEnabled: Boolean,
    isPatrolModeEnabled: Boolean,
    isLivePovSharingEnabled: Boolean,
    isPeopleCountingEnabled: Boolean,
    peopleCount: Int?,
    describeResult: String?,
    describeError: String?,
    commandCenterStatus: String?,
    commandCenterError: String?,
    voiceHeardText: String?,
    modifier: Modifier = Modifier,
) {
  if (
      !isDescribeLoading &&
          !isListening &&
          !isHandsFreeModeEnabled &&
          !isPatrolModeEnabled &&
          !isLivePovSharingEnabled &&
          !isPeopleCountingEnabled &&
          describeResult.isNullOrEmpty() &&
          describeError.isNullOrEmpty() &&
          commandCenterStatus.isNullOrEmpty() &&
          commandCenterError.isNullOrEmpty() &&
          voiceHeardText.isNullOrEmpty()
  ) {
    return
  }

  Surface(
      modifier = modifier.fillMaxWidth().widthIn(max = 420.dp),
      color = Color(0xFF071424).copy(alpha = 0.74f),
      shape = RoundedCornerShape(22.dp),
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
      shadowElevation = 14.dp,
  ) {
    Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      val primaryStatus =
          when {
            isListening -> stringResource(id = R.string.voice_listening)
            isDescribeLoading -> stringResource(id = R.string.describe_loading)
            !describeError.isNullOrEmpty() ->
                "${stringResource(id = R.string.describe_error_prefix)} $describeError"
            !describeResult.isNullOrEmpty() -> describeResult
            else -> null
          }

      primaryStatus?.let { text ->
        Text(
            text = text,
            color = Color.White,
            style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodyMedium.fontSize, fontWeight = FontWeight.SemiBold),
            maxLines = 3,
        )
      }

      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (isHandsFreeModeEnabled) {
          StatusChip(
              label = stringResource(id = R.string.hands_free_mode_active),
              tint = Color(0xFFB9F6CA),
          )
        }
        if (isPatrolModeEnabled) {
          StatusChip(
              label = "Patrol mode active",
              tint = Color(0xFFFFE082),
          )
        }
        if (isLivePovSharingEnabled) {
          StatusChip(
              label = "Live POV sharing",
              tint = Color(0xFF9ED3FF),
          )
        }
        if (isPeopleCountingEnabled) {
          StatusChip(
              label = "People: ${peopleCount ?: "…"}",
              tint = Color(0xFFB9F6CA),
          )
        }
      }

      voiceHeardText?.let { heard ->
        Text(
            text = "${stringResource(id = R.string.voice_heard_label)} $heard",
            color = Color.LightGray,
            style = AppTypography.Body.copy(color = Color.LightGray, fontSize = MaterialTheme.typography.bodySmall.fontSize),
        )
      }

      commandCenterStatus?.let { status ->
        Text(
            text = status,
            color = Color(0xFFB9F6CA),
            style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
        )
      }

      commandCenterError?.let { error ->
        Text(
            text = "${stringResource(id = R.string.command_center_error_prefix)} $error",
            color = Color(0xFFFFB4A9),
            style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
        )
      }
    }
  }
}

@Composable
private fun StatusChip(label: String, tint: Color) {
  Surface(
      color = tint.copy(alpha = 0.12f),
      shape = RoundedCornerShape(999.dp),
      border = BorderStroke(1.dp, tint.copy(alpha = 0.28f)),
  ) {
    Text(
        text = label,
        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        color = tint,
        style = AppTypography.Body.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
    )
  }
}

@Composable
private fun MiniActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Color.White,
) {
  Button(
      onClick = onClick,
      enabled = enabled,
      modifier = modifier.height(56.dp),
      colors =
          androidx.compose.material3.ButtonDefaults.buttonColors(
              containerColor = Color.White.copy(alpha = 0.08f),
              contentColor = Color.White,
              disabledContainerColor = Color.White.copy(alpha = 0.04f),
              disabledContentColor = Color.White.copy(alpha = 0.3f),
          ),
      shape = RoundedCornerShape(18.dp),
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.12f)),
  ) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
      Icon(
          imageVector = icon,
          contentDescription = label,
          tint = tint,
          modifier = Modifier.size(18.dp),
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
          text = label,
          style = AppTypography.Button.copy(fontSize = MaterialTheme.typography.labelSmall.fontSize),
          color = Color.White,
          maxLines = 1,
      )
    }
  }
}
