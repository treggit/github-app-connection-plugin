package jetbrains.buildServer.teamcity.github.connection

import jetbrains.buildServer.teamcity.github.api.GithubApiFacade
import jetbrains.buildServer.teamcity.github.constants.GithubAppConstants
import jetbrains.buildServer.teamcity.github.roots.GithubRootDescription
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.github.benmanes.caffeine.cache.Caffeine
import com.github.benmanes.caffeine.cache.LoadingCache
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.RootUrlHolder
import jetbrains.buildServer.serverSide.*
import jetbrains.buildServer.serverSide.crypt.EncryptionManager
import jetbrains.buildServer.serverSide.executors.ExecutorServices
import jetbrains.buildServer.teamcity.github.connection.domain.ApplicationConnectionDescriptor
import jetbrains.buildServer.teamcity.github.connection.domain.GithubTokenDescription
import jetbrains.buildServer.teamcity.github.connection.exceptions.GithubTokenGenerationException
import jetbrains.buildServer.teamcity.github.parameters.ParametersUtils
import jetbrains.buildServer.teamcity.github.roots.GithubRepositoriesUtils
import jetbrains.buildServer.teamcity.github.utils.PEMUtils
import jetbrains.buildServer.util.FileUtil
import org.springframework.web.util.UriComponentsBuilder
import java.io.File
import java.net.URI
import java.security.PrivateKey
import java.security.interfaces.RSAPrivateKey
import java.time.temporal.ChronoUnit
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit


class GithubApplicationConnectionManager(
        private val serverPaths: ServerPaths,
        private val projectManager: ProjectManager,
        private val githubApiFacade: GithubApiFacade,
        private val encryptionManager: EncryptionManager,
        private val rootUrlHolder: RootUrlHolder,
        executorServices: ExecutorServices
) {

    companion object {
        const val KEYS_DIRECTORY = "applicationKeys"
        const val KEY_FILE_EXTENSION = "pem"
        const val JWT_EXPIRATION_PERIOD = 10L
        val JWT_EXPIRATION_TIME_UNIT = TimeUnit.MINUTES
        const val FEATURE_TYPE = "githubActionAuth"

        private val LOG = Logger.getInstance(GithubApplicationConnectionManager::class.java.name)

        // Including commented permissions leads to a failure of an app generation for some reason
        private val DEFAULT_SUPPORTED_APP_SCOPES = listOf(
                "actions",
                "metadata",
                "checks",
                "contents",
                "deployments",
                "issues",
                "packages",
//                "pull-requests",
//                "repository-projects",
//                "security-events",
                "statuses"
        )

        private val DEFAULT_SUPPORTED_EVENTS = listOf("check_run", "check_suite", "delete",
                "deployment", "deployment_status", "fork", "gollum", "issue_comment", "issues", "label", "milestone",
                "public", "registry_package", "release", "status",
                "watch", "workflow_run", "create", "repository_dispatch"
        )

        private val SUPPORTED_SCOPES_PROPERTY = "github.app.token.available.permissions.scope"

        private const val WRITE_PERMISSION = "write"
        private const val READ_PERMISSION = "read"
    }


    private val jwt = ConcurrentHashMap<String, String>()

    private val objectMapper = ObjectMapper().also { it.registerKotlinModule() }
    
    private val installationsCache: LoadingCache<ApplicationConnectionDescriptor, List<InstallationDescription>> = Caffeine.newBuilder()
            .maximumSize(10000)
            .expireAfterWrite(30, TimeUnit.MINUTES)
            .build { listInstallations(it) }


    init {
        executorServices.normalExecutorService.scheduleWithFixedDelay(
                { regenerateAllJwt() },
                0,
                (JWT_EXPIRATION_PERIOD * 0.75).toLong(),
                JWT_EXPIRATION_TIME_UNIT
        )
    }

    fun addApplicationConnection(descriptor: ApplicationConnectionDescriptor, privateKey: ByteArray) {
        regenerateToken(PEMUtils.getPrivateKey(privateKey), descriptor.appId)
        writeKeyOnDisk(privateKey, descriptor.appId)
        persistDescriptor(descriptor)
    }

    fun getInstallationTokenForBuild(build: SBuild): GithubTokenDescription {
        val root = GithubRepositoriesUtils.getMigratedRootUrlDescription(build)
        val connection = root?.let { findConnectionDescriptor(it.serverUrl, it.owner) }
                ?: throw GithubTokenGenerationException("Failed to find connection to the github app for build with id ${build.buildId}")
        val token = jwt[connection.appId]
                ?: throw GithubTokenGenerationException("Cannot query github api because of a missing jwt for the client ${connection.appId}")
        try {
            val installationId = getTargetInstallationId(root, listInstallationsFromCache(connection))
                    ?: throw GithubTokenGenerationException("Application with id ${connection.appId} is not installed for the repository " +
                            root.repository)

            return githubApiFacade.createInstallationToken(connection, installationId, root.repositoryName, token, getTokenPermissionsForBuild(build))
        } catch (e: GithubTokenGenerationException) {
            throw e
        } catch (th: Throwable) {
            throw GithubTokenGenerationException(reason = th)
        }
    }

    // Gets permissions from a build paramater.
    // The parameter format is similar to an analogue in Github Actions, see https://docs.github.com/en/actions/reference/workflow-syntax-for-github-actions#permissions
    private fun getTokenPermissionsForBuild(build: SBuild): Map<String, String> {
        val allScopes = build.buildOwnParameters[tokenPermissionsParameter(GithubAppConstants.ALL_PERMISSIONS)]
        val scopes = getSupportedScopes()

        if (allScopes != null) {
            return scopes.associateWith { allScopes }
        }

        return scopes
                .associateWith { (build.buildOwnParameters[tokenPermissionsParameter(it)] ?: "") }
                .filter { it.value.isNotEmpty() }
    }

    private fun tokenPermissionsParameter(scope: String) = "${GithubAppConstants.TOKEN_PERMISSIONS_PARAMETER_PREFIX}.$scope"

    private fun getSupportedScopes(): List<String> {
        val custom = ParametersUtils.getPropertiesList(TeamCityProperties.getProperty(SUPPORTED_SCOPES_PROPERTY))
        if (custom.isEmpty()) {
            return DEFAULT_SUPPORTED_APP_SCOPES
        }
        return custom
    }

    private fun getTargetInstallationId(root: GithubRootDescription, installations: List<InstallationDescription>)
            = installations.firstOrNull { it.owner == root.owner }?.id

    fun revokeInstallationToken(token: GithubTokenDescription) {
        githubApiFacade.revokeInstallationToken(token)
    }

    private fun listInstallations(connection: ApplicationConnectionDescriptor): List<InstallationDescription> {
        val token = jwt[connection.appId]
                ?: throw GithubTokenGenerationException("Cannot query github api because of a missing jwt for the client ${connection.appId}")

        return githubApiFacade.listInstallations(connection, token).map { InstallationDescription(it["account"]["login"].asText(), it["id"].asText()) }
    }

    private fun listInstallationsFromCache(connection: ApplicationConnectionDescriptor)
            = installationsCache[connection] ?: emptyList()

    private fun regenerateToken(keyFile: File): String {
        return regenerateToken(
                PEMUtils.getPrivateKey(keyFile),
                getAppIdFromFilename(keyFile)
        )
    }

    private fun regenerateToken(privateKey: PrivateKey, appId: String): String {
        try {
            val issuedAt = Date()
            val expiresAt = Date(issuedAt.toInstant().plus(JWT_EXPIRATION_PERIOD, ChronoUnit.MINUTES).toEpochMilli())
            return JWT.create()
                    .withIssuedAt(issuedAt)
                    .withExpiresAt(expiresAt)
                    .withIssuer(appId)
                    .sign(Algorithm.RSA256(privateKey as RSAPrivateKey))
                    .also {
                        jwt[appId] = it
                    }
        } catch (th: Throwable) {
            throw GithubTokenGenerationException(reason = th)
        }
    }

    private fun regenerateAllJwt() {
        try {
            var exception: Throwable? = null
            for (file in getAllKeysFiles()) {
                try {
                    withRetry { regenerateToken(file) }
                } catch (th: Throwable) {
                    if (exception == null) {
                        exception = th
                    } else {
                        exception.addSuppressed(th)
                    }
                }
            }
            if (exception != null) {
                throw exception
            }
        } catch (th: Throwable) {
            LOG.error("Failed to regenerate some of the jwt for github app access. " +
                    "It could lead to builds being not able to authorize in Github", th)
        }
    }

    private fun writeKeyOnDisk(content: ByteArray, appId: String) {
        FileUtil.writeToFile(getPathForAppId(appId), content)
    }

    private fun getPathForAppId(appId: String): File {
        return getKeysDirectory().resolve("$appId.$KEY_FILE_EXTENSION")
    }

    private fun getAppIdFromFilename(file: File)
            = file.nameWithoutExtension

    private fun getKeysDirectory() = serverPaths.pluginDataDirectory
            .resolve(GithubAppConstants.PLUGIN_DATA_DIRECTORY)
            .resolve(KEYS_DIRECTORY)

    private fun getAllKeysFiles() = withRetry {
        return@withRetry getKeysDirectory().listFiles { file ->
            file.extension == KEY_FILE_EXTENSION
        } ?: emptyArray()
    }

    private fun <T> withRetry(attempts: Int = 5, action: () -> T): T {
        var left = attempts
        while (left > 0) {
            left--
            try {
                return action()
            } catch (th: Throwable) {
                if (left == 0) {
                    throw th
                }
            }
        }

        throw Exception()
    }

    private fun getHost(url: String) = try {
        URI(url).host
    } catch (_: Throwable) {
        null
    }

    fun findConnectionDescriptor(githubUrl: String, owner: String) = findConnection {
        (it.githubUrl == githubUrl || getHost(it.githubUrl) == githubUrl) && it.owner == owner
    }?.second

    fun findConnectionDescriptor(appId: String)
            = findConnection { it.appId == appId }?.second

    fun findConnectionDescriptorByInstallationId(installationId: String)
            = findConnection { listInstallationsFromCache(it).map { it.id }.contains(installationId) }?.second

    private fun findConnection(predicate: (ApplicationConnectionDescriptor) -> Boolean)
            = projectManager.rootProject.getAvailableFeaturesOfType(FEATURE_TYPE)
            .asSequence()
            .map { it to connectionFromParameters(it.parameters) }
            .firstOrNull {
                try {
                    it.second != null && predicate(it.second!!)
                } catch (_: Throwable) {
                    false
                }
            }

    fun getAllConnections()
            = projectManager.rootProject.getAvailableFeaturesOfType(FEATURE_TYPE)
            .asSequence()
            .mapNotNull { connectionFromParameters(it.parameters) }
            .toList()

    fun removeConnection(appId: String) {
        val (feature, connection) = findConnection { it.appId == appId } ?: return
        connection ?: return

        FileUtil.delete(getPathForAppId(connection.appId))
        projectManager.rootProject.removeFeature(feature.id)
        projectManager.rootProject.schedulePersisting("Github Application connection removed")
    }

    private fun persistDescriptor(descriptor: ApplicationConnectionDescriptor) {
        projectManager.rootProject.addFeature(FEATURE_TYPE, descriptor.toJsonMap())
        projectManager.rootProject.schedulePersisting("Github Application connection added")
    }

    private fun ApplicationConnectionDescriptor.toJsonMap() = mutableMapOf(
            "githubUrl" to githubUrl,
            "clientId" to appId,
            "owner" to owner,
            "webhookSecret" to encryptionManager.encrypt(webhookSecret),
            "reserved" to reserved.toString(),
    ).also { map ->
        appName?.let { map["applicationName"] = it }
        ownerId?.let { map["ownerId"] = it }
    }

    private fun connectionFromParameters(map: Map<String, String>): ApplicationConnectionDescriptor? {
        val url = map["githubUrl"] ?: return null
        val clientId = map["clientId"] ?: return null
        val owner = map["owner"] ?: return null
        val webhookSecret = map["webhookSecret"]?.let { encryptionManager.decrypt(it) } ?: return null
        val reserved = map["reserved"]?.toBoolean() ?: false
        val appName = map["applicationName"]
        val ownerId = map["ownerId"]

        return ApplicationConnectionDescriptor(url, owner, clientId, webhookSecret, reserved, appName, ownerId)
    }

    private fun getDefaultAppPermissions(): Map<String, String> {
        return getSupportedScopes().associateWith { WRITE_PERMISSION }
                .toMutableMap()
                .also {
                    it["metadata"] = READ_PERMISSION
                }
    }

    fun getAppGenerationLink(githubUrl: String, owner: String, isOrganisation: Boolean): String {
        val tempId = UUID.randomUUID().toString()

        // reserve in db a fake connection to persist some data,
        // see jetbrains.buildServer.teamcity.github.connection.GithubApplicationConnectionController.finishConnection
        val reservedConnection = ApplicationConnectionDescriptor(githubUrl, owner, tempId, "", true)
        val manifest = AppManifest(
                "$owner-teamcity-integration",
                "$githubUrl/$owner",
//                todo: add real webhook endpoint here
//                HookAttributes("${rootUrlHolder.rootUrl}${GithubEventsController.PATH}", true),
                HookAttributes(rootUrlHolder.rootUrl, true),
                "${rootUrlHolder.rootUrl}${GithubApplicationConnectionController.PATH}/finishConnection/$tempId",
                false,
                getDefaultAppPermissions(),
                DEFAULT_SUPPORTED_EVENTS
        )


        return UriComponentsBuilder.fromHttpUrl(getBaseAppGenerationLink(githubUrl, owner, isOrganisation))
                .queryParam("manifest", objectMapper.writeValueAsString(manifest))
                .toUriString().also {
                    persistDescriptor(reservedConnection)
                }
    }

    private fun getBaseAppGenerationLink(githubUrl: String, owner: String, isOrganisation: Boolean): String {
        return if (isOrganisation) {
            "$githubUrl/organizations/$owner/settings/apps/new"
        } else {
            "$githubUrl/settings/apps/new"
        }
    }

    data class InstallationDescription(
            val owner: String,
            val id: String
    )

    data class AppManifest(
            val name: String,
            val url: String,
            @JsonProperty("hook_attributes") val hookAttributes: HookAttributes,
            @JsonProperty("redirect_url") val redirectUrl: String,
            val public: Boolean,
            @JsonProperty("default_permissions") val permissions: Map<String, String>,
            @JsonProperty("default_events") val events: List<String>
    )

    data class HookAttributes(
            val url: String,
            val active: Boolean
    )
}