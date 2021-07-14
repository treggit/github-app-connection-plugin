package jetbrains.buildServer.teamcity.github.connection.domain

data class GithubTokenDescription(
        val githubUrl: String,
        val clientId: String,
        val token: String
)