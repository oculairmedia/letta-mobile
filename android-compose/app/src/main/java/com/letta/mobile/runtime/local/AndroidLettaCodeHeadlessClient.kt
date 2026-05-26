package com.letta.mobile.runtime.local

import com.letta.mobile.runtime.RuntimeEventDraft
import com.letta.mobile.runtime.TurnCommand
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

@Singleton
class AndroidLettaCodeHeadlessClient @Inject constructor(
    private val runtimeController: LettaCodeRuntimeController,
    private val streamJsonMapper: LettaCodeStreamJsonMapper,
) : LettaCodeHeadlessClient {
    override fun runTurn(command: TurnCommand): Flow<RuntimeEventDraft> = flow {
        runtimeController.submit(command).collect { line ->
            streamJsonMapper.mapLine(line, command).forEach { draft ->
                emit(draft)
            }
        }
    }
}
