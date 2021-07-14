package jetbrains.buildServer.teamcity.github.connection

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.google.common.util.concurrent.Striped
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.serverSide.*
import jetbrains.buildServer.serverSide.crypt.EncryptionManager
import jetbrains.buildServer.serverSide.executors.ExecutorServices
import jetbrains.buildServer.teamcity.github.connection.domain.GithubTokenDescription
import jetbrains.buildServer.util.EventDispatcher
import java.util.concurrent.TimeUnit

class GithubTokensRegistry(
        private val projectManager: ProjectManager,
        private val buildsManager: BuildsManager,
        private val encryptionManager: EncryptionManager,
        private val githubApplicationConnectionManager: GithubApplicationConnectionManager,
        eventDispatcher: EventDispatcher<BuildServerListener>,
        executorServices: ExecutorServices
) : BuildServerAdapter() {

    companion object {
        const val STORAGE_ID = "github_token"

        private val LOG = Logger.getInstance(GithubTokensRegistry::class.java.name)
    }

    private val objectMapper = ObjectMapper().also { it.registerKotlinModule() }
    private val tokenLock = Striped.lock(8)

    init {
        executorServices.normalExecutorService.scheduleWithFixedDelay( { checkAllTokens() }, 0, 20, TimeUnit.MINUTES)

        eventDispatcher.addListener(this)
    }

    private fun <T> withLock(buildId: Long, action: () -> T): T {
        with(tokenLock.get(buildId)) {
            tryLock(30, TimeUnit.SECONDS)
            try {
                return action()
            } finally {
                unlock()
            }
        }
    }

    fun getTokenForBuild(build: SBuild): String? {
        if (build.isFinished) {
            return null
        }

        return withLock(build.buildId) {
            try {
                getPersistedToken(build.buildId)?.let { return@withLock it.token }
                return@withLock githubApplicationConnectionManager.getInstallationTokenForBuild(build).also { persistToken(build.buildId, it) }.token
            } catch (th: Throwable) {
                LOG.error("Failed to generate github.token for build with id ${build.buildId}", th)
                return@withLock null
            }
        }
    }

    override fun buildFinished(build: SRunningBuild) {
        revokeTokenForBuild(build.buildId)
    }

    fun checkAllTokens() {
        for (buildId in getStorage().values?.keys ?: emptyList()) {
            try {
                val build = buildsManager.findRunningBuildById(buildId.toLong())
                if (build == null) {
                    revokeTokenForBuild(buildId.toLong())
                }
            } catch (th: Throwable) {
                LOG.error("Failed to revoke github token for a build with id $buildId", th)
            }
        }
    }

    fun revokeTokenForBuild(buildId: Long) {
        withLock(buildId) {
            val token = getPersistedToken(buildId) ?: return@withLock

            try {
                githubApplicationConnectionManager.revokeInstallationToken(token)
                removePersistedToken(buildId)
            } catch (th: Throwable) {
                LOG.error("Failed to revoke github.token for a build with id $buildId", th)
            }
        }
    }

    private fun getPersistedToken(buildId: Long): GithubTokenDescription? {
        val token = getStorage().getValue(buildId.toString()) ?: return null

        return try {
            with(objectMapper.readValue(token, GithubTokenDescription::class.java)) {
                GithubTokenDescription(githubUrl, clientId, encryptionManager.decrypt(token))
            }
        } catch (th: Throwable) {
            null
        }
    }

    private fun removePersistedToken(buildId: Long) = getStorage().putValue(buildId.toString(), null)

    private fun persistToken(buildId: Long, token: GithubTokenDescription) {
        with(token) {
            getStorage().putValue(buildId.toString(),
                    objectMapper.writeValueAsString(GithubTokenDescription(githubUrl, clientId, encryptionManager.encrypt(this.token)))
            )
        }
    }

    private fun getStorage()
            = projectManager.rootProject.getCustomDataStorage(STORAGE_ID)

}