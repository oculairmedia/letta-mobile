package com.letta.mobile.appservercli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.main
import com.github.ajalt.clikt.core.subcommands

class AppServerCli : CliktCommand(name = "meridian-app-server") {
    override fun run() = Unit
}

object Main {
    @JvmStatic
    fun main(args: Array<String>) {
        com.letta.mobile.util.Telemetry.installRecorder(com.letta.mobile.util.TelemetryRecorder()).use {
            AppServerCli()
                .subcommands(
                    AppServerServeCommand(),
                    AppServerSmokeCommand(),
                )
                .main(args)
        }
    }
}
