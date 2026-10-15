package com.letta.mobile.data.session

import javax.inject.Qualifier

/**
 * letta-mobile-by4vm.89: the process-lifetime `CoroutineScope`, bound by the app module. The data
 * layer receives it rather than reaching for `ProcessLifecycleOwner` itself, so it stays free of
 * the Android lifecycle and tests can hand it any scope.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ProcessScope
