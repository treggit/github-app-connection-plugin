package jetbrains.buildServer.teamcity.github.connection.exceptions

import java.lang.Exception

class ApplicationConnectionException(message: String = "", reason: Throwable? = null) : Exception(message, reason)