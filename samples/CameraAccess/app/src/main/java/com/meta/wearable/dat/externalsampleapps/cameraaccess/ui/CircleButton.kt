/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.TimerMode

@Composable
fun CircleButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
  Button(
      modifier = modifier.size(44.dp),
      onClick = onClick,
      colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF183357), contentColor = Color.White),
      shape = CircleShape,
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.2f)),
      contentPadding = PaddingValues(0.dp),
      content = content,
  )
}

@Composable
fun TimerButton(
    timerMode: TimerMode,
    onClick: () -> Unit,
) {
  CircleButton(onClick = onClick) {
    if (timerMode == TimerMode.UNLIMITED) {
      Icon(
          imageVector = Icons.Filled.Timer,
          contentDescription = stringResource(timerMode.labelId),
          tint = Color.White,
      )
    } else {
      Text(
          text = stringResource(timerMode.labelId),
          color = Color.White,
      )
    }
  }
}

@Composable
fun CaptureButton(onClick: () -> Unit) {
  CircleButton(onClick = onClick) {
    Icon(
        imageVector = Icons.Filled.PhotoCamera,
        contentDescription = stringResource(R.string.capture_photo),
        tint = Color.White,
    )
  }
}
