package jetbrains.buildServer.teamcity.github.connection.domain

data class ApplicationConnectionDescriptor(
        val githubUrl: String,
        val owner: String,
        val appId: String,
        val webhookSecret: String,
        val reserved: Boolean = false,
        val appName: String? = null,
        val ownerId: String? = null
) {
    val description: String
        get() = "Owner: ${githubUrl}/$owner" + (appName?.let { ", slug: $appName" } ?: "")
}