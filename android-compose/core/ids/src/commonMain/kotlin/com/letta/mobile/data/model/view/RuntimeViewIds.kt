package com.letta.mobile.data.model.view

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Stable view implementation identity. [value] serializes as a bare string, independent of any object schema. */
@JvmInline
@Serializable
value class RuntimeViewId(val value: String) {
    init { require(value.isNotBlank()) }

    override fun toString(): String = value
}

/** Host-owned mount identity, distinct from view selection and canonical object identity. Serializes as [value]. */
@JvmInline
@Serializable
value class RuntimeViewMountId(val value: String) {
    init { require(value.isNotBlank()) }

    override fun toString(): String = value
}
