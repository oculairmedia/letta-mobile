package com.letta.mobile.ui.image

import androidx.compose.ui.graphics.ImageBitmap

expect fun decodeImageBitmap(bytes: ByteArray): ImageBitmap?
