package com.letta.mobile.cli.commands.records

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.option

/** Related wrapper store paths, registered together by the Iroh serving command. */
internal class WrapperStoreOptions : OptionGroup() {
    val modelExposureFile by option(
        "--model-exposure-file",
        envvar = "LETTA_MODEL_EXPOSURE_FILE",
        help = "JSON file holding which App Server models are exposed to app model pickers " +
            "(model.exposure.*; default: model-exposure.json next to host-canvases.json).",
    )

    val runRecordsFile by option(
        "--run-records-file",
        envvar = "LETTA_RUN_RECORDS_STORE",
        help = "Wrapper-owned run records and leases JSON file (default: run-records.json beside model-exposure.json).",
    )
}
