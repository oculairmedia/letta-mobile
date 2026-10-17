@file:OptIn(ExperimentalUuidApi::class)

package com.letta.mobile.web.data

import com.letta.mobile.data.transport.appserver.AppServerTerminalConsumerId
import kotlinx.browser.sessionStorage
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * letta-mobile-zxk2n.2: this tab's durable terminal consumer id. Each tab is its own consumer, so
 * one tab's `turn_finished_ack` never retires a terminal another tab still has to see. The id lives
 * in sessionStorage, so a reload of the tab acks what the host kept for it; where storage is
 * unavailable it lasts the page.
 */
internal fun webTabTerminalConsumerId(): AppServerTerminalConsumerId {
    val stored = runCatching { sessionStorage.getItem(STORAGE_KEY) }.getOrNull()
        ?.takeIf(AppServerTerminalConsumerId::isValid)
    if (stored != null) return AppServerTerminalConsumerId(stored)
    val minted = AppServerTerminalConsumerId.forClient(WEB_SURFACE, Uuid.random().toString())
    runCatching { sessionStorage.setItem(STORAGE_KEY, minted.value) }
    return minted
}

private const val STORAGE_KEY = "letta.web.terminalConsumerId"
private const val WEB_SURFACE = "letta-web"
