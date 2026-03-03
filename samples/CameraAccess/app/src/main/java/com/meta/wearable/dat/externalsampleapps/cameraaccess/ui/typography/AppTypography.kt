/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.typography

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontFamily.Companion.SansSerif

object AppTypography {
  val Title =
      TextStyle(
          fontFamily = SansSerif,
          fontWeight = FontWeight.SemiBold,
          fontSize = 20.sp,
      )

  val Body =
      TextStyle(
          fontFamily = SansSerif,
          fontWeight = FontWeight.Normal,
          fontSize = 16.sp,
      )

  val Button =
      TextStyle(
          fontFamily = SansSerif,
          fontWeight = FontWeight.SemiBold,
          fontSize = 15.sp,
      )
}
