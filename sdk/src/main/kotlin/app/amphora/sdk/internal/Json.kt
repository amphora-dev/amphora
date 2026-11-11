package app.amphora.sdk.internal

import kotlinx.serialization.json.Json

/** One configuration for every JSON file the SDK reads or writes. */
internal val AmphoraJson: Json =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
        prettyPrint = true
        prettyPrintIndent = "  "
    }
