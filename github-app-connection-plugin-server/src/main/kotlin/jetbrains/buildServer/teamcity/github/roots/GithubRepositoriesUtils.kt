package jetbrains.buildServer.teamcity.github.roots

import jetbrains.buildServer.serverSide.SBuild
import jetbrains.buildServer.serverSide.SBuildType
import java.net.URL

object GithubRepositoriesUtils {

    val supportedProtocols = setOf(
            "https",
            "http",
            "ssh",
            "git",
            "git",
            ""
    )

    fun getMigratedRootUrlDescription(build: SBuild): GithubRootDescription? {
        return build.buildType?.let { getMigratedRootUrlDescription(it) }
    }

    // In a context of migration tool this method was used to extract a migrated from GitHub root (see the commented line).
    // So now the name is obsolete and it returns the first root of a build configuration.
    // todo: this method should be fixed depending on how we're going to establish relation between an app connection and a vcs root
    fun getMigratedRootUrlDescription(buildType: SBuildType): GithubRootDescription? {
        val root = buildType.vcsRoots
                //.firstOrNull { it.isMigratedRoot() }
                .firstOrNull()
                ?.getProperty("url")
                ?: return null

        return getRepositoryInfo(root)
    }

    fun getRepositoryInfo(link: String): GithubRootDescription? {
        try {
            val url = URL(link)

            val protocol = url.protocol
            if (protocol !in supportedProtocols) {
                return null
            }

            val host = url.host
            val path = url.path.removePrefix("/").split("/")
            val owner = path[0]
            val name = path[1].removeSuffix(".git")
            return GithubRootDescription(protocol, host, owner, name)
        } catch (th: Throwable) {
            return null
        }
    }

}