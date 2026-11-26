package com.letta.mobile.feature.chat.screen.shared

import com.letta.mobile.data.chat.recompile.ConversationRecompileController
import com.letta.mobile.data.chat.recompile.ConversationRecompileRequest
import com.letta.mobile.data.chat.routing.SlashCommandSessionPort
import com.letta.mobile.data.commands.ClientSlashCommand
import com.letta.mobile.data.voice.session.VoiceChatSessions
import com.letta.mobile.data.workspace.WorkspaceFileOpener
import com.letta.mobile.feature.chat.screen.AdminChatViewModel
import com.letta.mobile.feature.chat.screen.AndroidClientCommands
import com.letta.mobile.feature.chat.screen.ChatScreenNavigationCallbacks
import com.letta.mobile.ui.chat.session.ChatSessionPort
import com.letta.mobile.ui.chat.session.ClientCommandRouting
import com.letta.mobile.ui.chat.session.ClientCommandSessionPort
import com.letta.mobile.ui.shell.pages.workspace.WorkspaceFiles
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.flowOf

/**
 * The shared chat page's [ChatSessionPort] over [AdminChatViewModel]: the view model's session port
 * with the scaffold's client-local `/` commands (`/recompile` runs here so its status reaches the
 * page) and the host's advertised commands; voice mode's sessions over the same session port; and
 * the workspace files the page's chips open and its `@` search reads.
 *
 * Built in the page's scope, never the view model's (letta-mobile-09m53): it holds the screen's
 * navigation and command callbacks, which a rotation must free.
 */
internal class AdminChatPageBindings private constructor(
    surface: ChatSessionPort,
    /** Voice mode's chat, over the session port without the composer's command layers. */
    val voiceSessions: VoiceChatSessions,
    /** Null without workspace files: no file links and no `@` file suggestions. */
    val workspaceFiles: ChatWorkspaceFiles?,
) : ChatSessionPort by surface {
    /** The page's session on [viewModel]'s conversation as it is now. */
    fun session(viewModel: AdminChatViewModel): SharedChatPageSession {
        val target = ChatCanvasTarget(viewModel.agentId.value, viewModel.conversationId?.value)
        return SharedChatPageSession(this, voiceSessions, workspaceFiles, target)
    }

    /**
     * What every binding is built over: the screen's [viewModel], the page's [scope] (which ends on
     * rotation) and the Hilt host's workspace [files], null in previews and tests.
     */
    class Host(
        val viewModel: AdminChatViewModel,
        val scope: CoroutineScope,
        val files: WorkspaceFilesViewModel?,
    )

    companion object {
        /** The view model's session port; [navigation] is read when used, so the latest screen's callbacks answer. */
        fun sessionPort(
            host: Host,
            navigation: () -> ChatScreenNavigationCallbacks,
            canOpenConversation: Boolean,
        ): AdminChatSessionPort = AdminChatSessionPort(
            viewModel = host.viewModel,
            scope = host.scope,
            onOpenBugReport = { navigation().onBugCommand?.invoke() },
            onOpenConversation = if (canOpenConversation) {
                { agentId, conversationId -> navigation().onOpenConversation?.invoke(agentId, conversationId) }
            } else {
                null
            },
            fileMentions = host.files?.mentionablesFor(host.viewModel.agentId.value) ?: flowOf(emptyList()),
        )

        /** [port] with the composer's command layers; [commands] is read when a command runs. */
        fun create(
            host: Host,
            port: AdminChatSessionPort,
            commands: () -> AndroidClientCommands?,
            offered: Set<ClientSlashCommand>?,
        ): AdminChatPageBindings = with(host) {
            val commandPort = offered?.let { ClientCommandSessionPort(port, clientRouting(viewModel, scope, commands, it), scope) } ?: port
            val surface = SlashCommandSessionPort(commandPort, scope, AdminSlashCommandBinding(viewModel))
            AdminChatPageBindings(surface, AndroidVoiceChatSessions(viewModel, port), files?.let(::workspaceFiles))
        }

        /** The phone knows no working directory: a relative path cannot be opened (memory entries open in the Memory view). */
        private fun workspaceFiles(files: WorkspaceFilesViewModel): ChatWorkspaceFiles {
            val opener = WorkspaceFileOpener { request -> files.viewer.open(request.path, cwd = null, lines = request.lines) }
            return ChatWorkspaceFiles(WorkspaceFiles(opener, files.viewer.state, files.viewer), files::onDraftChanged)
        }

        private fun clientRouting(
            viewModel: AdminChatViewModel,
            scope: CoroutineScope,
            commands: () -> AndroidClientCommands?,
            offered: Set<ClientSlashCommand>,
        ): ClientCommandRouting {
            val recompile = ConversationRecompileController(
                scope = scope,
                gateway = { commands()?.recompileGateway },
                unavailableReason = { commands()?.recompileUnavailableReason ?: ConversationRecompileController.UNAVAILABLE_DEFAULT },
            )
            return ClientCommandRouting(
                offered = offered,
                run = { command ->
                    if (command == ClientSlashCommand.Recompile) {
                        viewModel.conversationId?.let { id ->
                            recompile.recompile(ConversationRecompileRequest(id.value, viewModel.agentId.value))
                        }
                    } else {
                        commands()?.route?.invoke(command)
                    }
                },
                recompile = recompile,
            )
        }
    }
}
