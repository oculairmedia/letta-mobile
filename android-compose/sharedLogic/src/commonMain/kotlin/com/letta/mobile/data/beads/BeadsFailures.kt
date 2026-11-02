package com.letta.mobile.data.beads

import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.CancellationException

internal object BeadsFailures {
    suspend inline fun <T> translate(block: () -> T): T = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: BeadsHttpException) {
        throw e
    } catch (e: BeadsProtocolException) {
        throw e
    } catch (_: Exception) {
        throw BeadsTransportException()
    }

    fun checkStatus(response: HttpResponse) {
        if (response.status.value !in 200..299) throw BeadsHttpException(response.status.value)
    }
}
