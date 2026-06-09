package com.letta.mobile.feature.chat.util

import android.net.Uri
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.AndroidFile

val PlatformFile.uri: Uri
    get() = when (val wrapped = this.androidFile) {
        is AndroidFile.UriWrapper -> wrapped.uri
        is AndroidFile.FileWrapper -> Uri.fromFile(wrapped.file)
        else -> error("Unknown AndroidFile type")
    }
