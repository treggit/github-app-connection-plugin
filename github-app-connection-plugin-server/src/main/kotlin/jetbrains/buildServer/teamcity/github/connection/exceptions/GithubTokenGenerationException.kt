package jetbrains.buildServer.teamcity.github.connection.exceptions

import java.lang.Exception

class GithubTokenGenerationException(
        message: String = "Failed to generate github auth token",
        reason: Throwable? = null
) : Exception(message, reason)