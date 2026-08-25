package com.letta.mobile.data.model.provider.rpc

/**
 * Standard method names for the provider_management_v1 Admin RPC contract.
 */
object ProviderRpcMethods {
    const val CONTRACT_NAME = "provider_management_v1"
    const val CONTRACT_VERSION = 1

    const val PROVIDER_DEFINITION_LIST = "provider.definition.list"
    const val PROVIDER_INSTANCE_LIST = "provider.instance.list"
    const val PROVIDER_INSTANCE_GET = "provider.instance.get"
    const val PROVIDER_INSTANCE_CREATE = "provider.instance.create"
    const val PROVIDER_INSTANCE_UPDATE = "provider.instance.update"
    const val PROVIDER_INSTANCE_SET_ENABLED = "provider.instance.set_enabled"
    const val PROVIDER_INSTANCE_DELETE = "provider.instance.delete"
    const val PROVIDER_INSTANCE_VALIDATE = "provider.instance.validate"
    const val PROVIDER_CREDENTIAL_REPLACE = "provider.credential.replace"
    const val PROVIDER_CREDENTIAL_CLEAR = "provider.credential.clear"
    const val MODEL_ROUTE_LIST = "model.route.list"
    const val MODEL_VISIBILITY_SET = "model.visibility.set"
    const val MODEL_VISIBILITY_RESET = "model.visibility.reset"

    val READ_METHODS: Set<String> = setOf(
        PROVIDER_DEFINITION_LIST,
        PROVIDER_INSTANCE_LIST,
        PROVIDER_INSTANCE_GET,
        MODEL_ROUTE_LIST,
    )

    val WRITE_METHODS: Set<String> = setOf(
        PROVIDER_INSTANCE_CREATE,
        PROVIDER_INSTANCE_UPDATE,
        PROVIDER_INSTANCE_SET_ENABLED,
        PROVIDER_INSTANCE_DELETE,
        PROVIDER_INSTANCE_VALIDATE,
        PROVIDER_CREDENTIAL_REPLACE,
        PROVIDER_CREDENTIAL_CLEAR,
        MODEL_VISIBILITY_SET,
        MODEL_VISIBILITY_RESET,
    )

    val ALL_METHODS: Set<String> = READ_METHODS + WRITE_METHODS

    fun isReadMethod(method: String): Boolean = READ_METHODS.contains(method)
    fun isWriteMethod(method: String): Boolean = WRITE_METHODS.contains(method)
}
