package jetbrains.buildServer.teamcity.github.connection.exceptions

import java.lang.Exception

class PrivateKeyContentParseException(
        message: String = "Failed to parse the PEM file with a private key",
        reason: Throwable? = null
) : Exception(message, reason)