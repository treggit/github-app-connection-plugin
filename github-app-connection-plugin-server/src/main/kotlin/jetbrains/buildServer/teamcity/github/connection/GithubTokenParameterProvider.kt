package jetbrains.buildServer.teamcity.github.connection

import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.parameters.AbstractBuildParametersProvider
import jetbrains.buildServer.teamcity.github.constants.GithubAppConstants.GITHUB_TOKEN
import jetbrains.buildServer.teamcity.github.parameters.ParametersUtils

class GithubTokenParameterProvider(
        private val githubTokensRegistry: GithubTokensRegistry
): AbstractBuildParametersProvider() {

    override fun getParameters(build: SBuild, emulationMode: Boolean): Map<String, String> {
        if (emulationMode) {
            return emptyMap()
        }

        val token = githubTokensRegistry.getTokenForBuild(build) ?: return emptyMap()

        val envName = ParametersUtils.makeEnvVariable(GITHUB_TOKEN)
        return mapOf(
                GITHUB_TOKEN to token,
                envName to token,
                "secrets.$envName" to token
        )
    }
}