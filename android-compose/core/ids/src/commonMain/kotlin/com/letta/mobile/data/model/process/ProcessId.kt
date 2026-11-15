package com.letta.mobile.data.model.process

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Opaque identity of a host-owned background process, distinct from run and conversation identities. */
@JvmInline
@Serializable
value class ProcessId(val value: String)
