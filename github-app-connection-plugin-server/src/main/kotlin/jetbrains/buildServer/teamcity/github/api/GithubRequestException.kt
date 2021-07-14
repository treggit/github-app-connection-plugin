package jetbrains.buildServer.teamcity.github.api

class GithubRequestException(message: String = "", reason: Throwable? = null) : Exception(message, reason)