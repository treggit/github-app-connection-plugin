package jetbrains.buildServer.teamcity.github.connection.domain

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

@JsonIgnoreProperties(ignoreUnknown = true)
data class GithubAppConfiguration(
        val id: String,
        val pem: String,
        @JsonProperty("webhook_secret") val webhookSecret: String,
        val slug: String,
        val owner: GithubAppOwner
)

