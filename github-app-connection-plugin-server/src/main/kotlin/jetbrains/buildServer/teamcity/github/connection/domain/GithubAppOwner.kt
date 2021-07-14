package jetbrains.buildServer.teamcity.github.connection.domain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubAppOwner(
        val id: String,
        val login: String
)