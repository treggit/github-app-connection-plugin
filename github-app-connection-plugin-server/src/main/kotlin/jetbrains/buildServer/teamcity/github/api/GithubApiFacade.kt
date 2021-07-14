package jetbrains.buildServer.teamcity.github.api

import com.fasterxml.jackson.databind.JsonNode
import jetbrains.buildServer.teamcity.github.connection.domain.ApplicationConnectionDescriptor
import jetbrains.buildServer.teamcity.github.connection.domain.GithubAppConfiguration
import jetbrains.buildServer.teamcity.github.connection.domain.GithubTokenDescription

interface GithubApiFacade {

    fun listInstallations(connectionDescriptor: ApplicationConnectionDescriptor, token: String): JsonNode

    fun createInstallationToken(
            connectionDescriptor: ApplicationConnectionDescriptor,
            installationId: String,
            repository: String,
            token: String,
            permissions: Map<String, String>
    ): GithubTokenDescription

    fun revokeInstallationToken(token: GithubTokenDescription)

    fun getAppConfiguration(githubUrl: String, code: String): GithubAppConfiguration
}