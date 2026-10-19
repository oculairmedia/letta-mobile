package com.letta.mobile.data.repository.modelcontrol

import com.letta.mobile.data.timeline.timelineCurrentTimeMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Where a subscription sign-in is. Platform-neutral:
 * the phone and desktop sheets render it, neither re-implements a step.
 * No state carries a token or a device secret; the user code is shown by
 * design.
 */
sealed interface ProviderSignInState {
    /** Null only for [Idle]. */
    val provider: ConnectableProvider?

    data object Idle : ProviderSignInState {
        override val provider: ConnectableProvider? = null
    }

    /** Asking the provider for a code or building the sign-in address. */
    data class Starting(override val provider: ConnectableProvider) : ProviderSignInState

    /** Device code: the user enters [userCode] on [verificationUrl]; the app polls until [expiresAtMillis]. */
    data class AwaitingApproval(
        override val provider: ConnectableProvider,
        val userCode: String,
        val verificationUrl: String,
        val expiresAtMillis: Long,
        /** True once the provider asked the app to poll less often. */
        val slowedDown: Boolean = false,
    ) : ProviderSignInState

    /** Authorization code: the user approves on [authorizeUrl] and pastes back what it shows. */
    data class AwaitingCode(
        override val provider: ConnectableProvider,
        val authorizeUrl: String,
        val input: String = "",
        /** A pasted code the provider or the parser refused; the user can paste again. */
        val error: String? = null,
    ) : ProviderSignInState {
        val canSubmit: Boolean get() = input.isNotBlank()

        override fun toString(): String = "AwaitingCode(provider=${provider.id}, hasInput=${input.isNotBlank()}, error=$error)"
    }

    /** Exchanging the code or handing the tokens to the App Server. */
    data class Connecting(override val provider: ConnectableProvider) : ProviderSignInState

    data class Connected(override val provider: ConnectableProvider) : ProviderSignInState

    data class Failed(
        override val provider: ConnectableProvider,
        val failure: SignInFailure,
        val message: String,
    ) : ProviderSignInState
}

/**
 * Runs one sign-in at a time: start → (approve and poll | paste a code) →
 * `provider.connect` with the finished tokens → refreshed providers and
 * models. Cancel stops polling at once. The tokens exist only in the call
 * that hands them to the host; nothing is persisted or logged here.
 */
class ProviderSignInController(
    private val scope: CoroutineScope,
    private val http: ProviderOAuthHttp,
    private val providers: ProviderConnectionRepository,
    private val catalog: ModelCatalogRepository,
    private val clock: () -> Long = ::timelineCurrentTimeMillis,
    private val random: (Int) -> ByteArray = ::secureRandomBytes,
) {
    private val _state = MutableStateFlow<ProviderSignInState>(ProviderSignInState.Idle)
    val state: StateFlow<ProviderSignInState> = _state.asStateFlow()

    private var job: Job? = null
    private var codeLogin: Pair<ProviderOAuthLogin.AuthCode, AuthCodeGrant>? = null

    /** True when the app can run [provider]'s sign-in itself. */
    fun supports(provider: ConnectableProvider): Boolean = ProviderOAuthKind.of(provider) != null

    fun start(provider: ConnectableProvider) {
        cancelWork()
        val login = ProviderOAuthLogin.create(provider, providers.providers.value, http, clock, random)
        if (login == null) {
            _state.value = ProviderSignInState.Failed(provider, SignInFailure.PROVIDER_ERROR, "${provider.displayName} can't be signed in from the app")
            return
        }
        _state.value = ProviderSignInState.Starting(provider)
        when (login) {
            is ProviderOAuthLogin.DeviceCode -> launch(provider) { runDeviceCode(provider, login) }
            is ProviderOAuthLogin.AuthCode -> beginAuthCode(provider, login)
        }
    }

    fun updateCode(input: String) = _state.update { current ->
        if (current is ProviderSignInState.AwaitingCode) current.copy(input = input, error = null) else current
    }

    /** Exchanges the pasted code; a refused code keeps the sheet open for another paste. */
    fun submitCode() {
        val current = _state.value as? ProviderSignInState.AwaitingCode ?: return
        val (login, grant) = codeLogin ?: return
        if (!current.canSubmit) return
        _state.value = ProviderSignInState.Connecting(current.provider)
        launch(current.provider) {
            val request = try {
                login.exchange(grant, current.input)
            } catch (e: ProviderSignInException) {
                if (e.failure != SignInFailure.BAD_CODE) throw e
                _state.value = current.copy(error = e.message)
                return@launch
            }
            handOff(current.provider, request)
        }
    }

    /** Try the same provider again after a failure. */
    fun retry() {
        val provider = (_state.value as? ProviderSignInState.Failed)?.provider ?: return
        start(provider)
    }

    /**
     * Stops any polling or code exchange and closes the sheet. Ignored while
     * the tokens are being handed to the host: that call may already have
     * landed, and the sheet must report how it ended.
     */
    fun cancel() {
        if (_state.value is ProviderSignInState.Connecting) return
        cancelWork()
        _state.value = ProviderSignInState.Idle
    }

    private fun cancelWork() {
        job?.cancel()
        job = null
        codeLogin = null
    }

    private fun beginAuthCode(provider: ConnectableProvider, login: ProviderOAuthLogin.AuthCode) {
        val grant = login.begin()
        codeLogin = login to grant
        _state.value = ProviderSignInState.AwaitingCode(provider, grant.authorizeUrl)
    }

    private suspend fun runDeviceCode(provider: ConnectableProvider, login: ProviderOAuthLogin.DeviceCode) {
        val grant = login.start()
        val poller = DeviceCodePoller(grant, clock)
        _state.value = ProviderSignInState.AwaitingApproval(provider, grant.userCode, grant.verificationUrl, poller.deadline)
        var approved: ProviderConnectRequest? = null
        while (approved == null) {
            delay(poller.nextWaitMillis() ?: throw poller.timedOut())
            if (poller.expired()) throw poller.timedOut()
            approved = settle(login.poll(grant), poller, login.kind)
        }
        handOff(provider, approved)
    }

    /** One poll's outcome: the request once approved, null to keep polling; a refusal throws. */
    private fun settle(result: DeviceCodePoll, poller: DeviceCodePoller, kind: ProviderOAuthKind): ProviderConnectRequest? {
        if (result is DeviceCodePoll.SlowDown) {
            poller.slowDown(result.intervalSeconds)
            _state.update { (it as? ProviderSignInState.AwaitingApproval)?.copy(slowedDown = true) ?: it }
        }
        result.refusal(kind)?.let { throw it }
        return (result as? DeviceCodePoll.Approved)?.request
    }

    private suspend fun handOff(provider: ConnectableProvider, request: ProviderConnectRequest) {
        _state.value = ProviderSignInState.Connecting(provider)
        val result = try {
            providers.connect(request)
        } catch (e: ModelControlException) {
            throw ProviderSignInException("The host didn't accept the sign-in: ${e.message}", SignInFailure.HOST_REJECTED)
        }
        codeLogin = null
        _state.value = ProviderSignInState.Connected(provider)
        if (result.modelsMayHaveChanged) runCatching { catalog.refresh(force = true) }.onFailure { if (it is CancellationException) throw it }
    }

    private fun launch(provider: ConnectableProvider, block: suspend () -> Unit) {
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ProviderSignInException) {
                _state.value = ProviderSignInState.Failed(provider, e.failure, e.message.orEmpty())
            } catch (e: Exception) {
                // Network errors: report the kind, never a body.
                val reason = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                _state.value = ProviderSignInState.Failed(provider, SignInFailure.PROVIDER_ERROR, "Couldn't reach ${provider.displayName}: $reason")
            }
        }
    }
}

/** Why a poll ends the sign-in, or null when polling goes on (pending, slow down) or it was approved. */
internal fun DeviceCodePoll.refusal(kind: ProviderOAuthKind): ProviderSignInException? = when (this) {
    DeviceCodePoll.Denied -> ProviderSignInException("Sign-in was declined on the ${kind.label} page", SignInFailure.DENIED)
    DeviceCodePoll.Expired -> ProviderSignInException("The code expired before it was approved", SignInFailure.EXPIRED)
    is DeviceCodePoll.Failed -> ProviderSignInException(message)
    DeviceCodePoll.Pending, is DeviceCodePoll.SlowDown, is DeviceCodePoll.Approved -> null
}

/**
 * The polling clock of one device code: the provider's interval (at least a
 * second, five by default), slowed on `slow_down`, until the code's lifetime
 * runs out.
 */
internal class DeviceCodePoller(grant: DeviceCodeGrant, private val clock: () -> Long) {
    val deadline: Long = clock() + grant.expiresInSeconds * OAuthWire.MS_PER_SECOND
    private var intervalMs: Long = toMillis(grant.intervalSeconds) ?: DEFAULT_INTERVAL_MS
    private var slowDowns = 0

    /** How long to wait before the next poll, or null when the code has expired. */
    fun nextWaitMillis(): Long? {
        val remaining = deadline - clock()
        return if (remaining <= 0) null else minOf(intervalMs, remaining)
    }

    fun expired(): Boolean = clock() >= deadline

    fun slowDown(intervalSeconds: Double?) {
        slowDowns++
        intervalMs = toMillis(intervalSeconds) ?: (intervalMs + SLOW_DOWN_STEP_MS)
    }

    fun timedOut(): ProviderSignInException {
        val hint = if (slowDowns > 0) " The provider asked to slow down; a wrong system clock can cause this." else ""
        return ProviderSignInException("The code expired before it was approved.$hint", SignInFailure.TIMED_OUT)
    }

    private fun toMillis(seconds: Double?): Long? =
        seconds?.takeIf { it > 0 && it.isFinite() }?.let { maxOf(MIN_INTERVAL_MS, (it * OAuthWire.MS_PER_SECOND).toLong()) }

    private companion object {
        const val MIN_INTERVAL_MS = 1_000L
        const val DEFAULT_INTERVAL_MS = 5_000L
        const val SLOW_DOWN_STEP_MS = 5_000L
    }
}
