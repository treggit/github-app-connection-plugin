package jetbrains.buildServer.teamcity.github.roots

data class GithubRootDescription(
        val protocol: String,
        val host: String,
        val owner: String,
        val repositoryName: String
) {
    val repository: String
        get() = "$owner/$repositoryName"

    val serverUrl: String
        get() = "$protocol://$host"

    val apiUrl: String
        get() = "$protocol://api.$host"
}