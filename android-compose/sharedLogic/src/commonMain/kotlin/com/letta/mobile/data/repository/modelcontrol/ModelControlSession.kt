package com.letta.mobile.data.repository.modelcontrol

import kotlinx.coroutines.CoroutineScope

/**
 * One host's provider and catalog repositories, shared by every model-control
 * surface of a client (picker, Models sheet, Providers settings) so a change in
 * one shows in the others at once. Android gets the same sharing from Hilt
 * singletons; desktop holds one of these per data binding.
 */
class ModelControlSession(rpc: AdminRpcInvoker) {
    val providers: ProviderConnectionRepository = ProviderConnectionRepository(rpc)
    val catalog: ModelCatalogRepository = ModelCatalogRepository(rpc)

    fun pickerSource(): ModelPickerSource = ModelPickerSource.catalog(providers, catalog)

    fun managementController(scope: CoroutineScope): ProviderManagementController =
        ProviderManagementController(scope, providers, catalog)
}
