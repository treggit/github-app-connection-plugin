package jetbrains.buildServer.teamcity.github.connection

import com.google.gson.Gson
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.controllers.ActionMessages
import jetbrains.buildServer.controllers.MultipartFormController
import jetbrains.buildServer.teamcity.github.api.GithubApiFacade
import jetbrains.buildServer.teamcity.github.connection.exceptions.ApplicationConnectionException
import jetbrains.buildServer.teamcity.github.connection.domain.ApplicationConnectionDescriptor
import jetbrains.buildServer.teamcity.github.connection.exceptions.GithubTokenGenerationException
import jetbrains.buildServer.teamcity.github.connection.exceptions.PrivateKeyContentParseException
import jetbrains.buildServer.teamcity.github.roots.GithubRepositoriesUtils
import jetbrains.buildServer.web.openapi.WebControllerManager
import org.apache.http.entity.ContentType
import org.springframework.http.HttpStatus
import org.springframework.web.servlet.ModelAndView
import org.springframework.web.servlet.View
import org.springframework.web.servlet.view.RedirectView
import java.net.URL
import javax.servlet.http.HttpServletRequest
import javax.servlet.http.HttpServletResponse

class GithubApplicationConnectionController(
        private val connectionManager: GithubApplicationConnectionManager,
        private val githubApiFacade: GithubApiFacade,
        webControllerManager: WebControllerManager
) : MultipartFormController() {

    companion object {
        const val PATH = "/app/github/application/connection"
    }

    private val log = Logger.getInstance(GithubApplicationConnectionController::class.java.name)

    init {
        webControllerManager.registerController(PATH, this)
        webControllerManager.registerController("$PATH/**", this)
    }

    private fun HttpServletRequest.requireParameter(name: String): String {
        return getParameter(name) ?: throw ApplicationConnectionException("Missing \"$name\" parameter")
    }

    override fun doGet(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        try {
            writeAsJson(response, findConnection(request.requireParameter("appId")))
        } catch (e: ApplicationConnectionException) {
            response.sendError(400, e.message)
        }

        return null
    }

    private fun writeAsJson(response: HttpServletResponse, result: Any?) {
        response.contentType = ContentType.APPLICATION_JSON.mimeType

        if (result != null) {
            response.writer.println(Gson().toJson(result))
        }
    }

    private fun writeAsPlainText(response: HttpServletResponse, result: String) {
        response.contentType = ContentType.TEXT_PLAIN.mimeType
        response.writer.println(result)
    }

    override fun doPost(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        try {
            with(request) {
                when(getParameter("action")) {
                    "addConnection" -> {
                        connectionAdded(addConnection(
                                requireParameter("ownerUrl"),
                                requireParameter("appId"),
                                requireParameter("webhookSecret"),
                                getMultipartFileOrFail(request, "file:fileToUpload")?.bytes
                                        ?: throw ApplicationConnectionException("Failed to upload a private key"), ),
                                request
                        )
                    }
                    "removeConnection" -> {
                        val appId = requireParameter("appId")
                        removeConnection(appId)
                        connectionRemoved(appId, request)
                    }
                    "generateLink" -> {
                        val link = getAppGenerationLink(
                                requireParameter("ownerUrl"),
                                getParameter("isOrganizationUrl") != null
                        )
                        // enable redirect for POST
                        request.setAttribute(View.RESPONSE_STATUS_ATTRIBUTE, HttpStatus.TEMPORARY_REDIRECT)
                        return ModelAndView(RedirectView(link))
                    }
                    else -> {}
                }
            }

        } catch (e: ApplicationConnectionException) {
            response.sendError(400, e.message)
        }

        return null
    }

    override fun doHandle(request: HttpServletRequest, response: HttpServletResponse): ModelAndView? {
        try {
            val path = request.pathInfo.split("/")
            if (path.size > 1 && path[path.size - 2] == "finishConnection") {
                val appId = path[path.size - 1]
                val connection = finishConnection(appId, request.requireParameter("code"))
                connectionAdded(connection, request)
                return ModelAndView(RedirectView("${request.contextPath}/admin/editProject.html?projectId=_Root&tab=${ApplicationConnectionTab.PLUGIN_NAME}"))
            }

            if (isPost(request)) {
                return doPost(request, response)
            }
            if (isGet(request)) {
                return doGet(request, response)
            }
        } catch (e: ApplicationConnectionException) {
            response.sendError(400, e.message)
        }

        return null
    }

    private fun connectionAdded(connection: ApplicationConnectionDescriptor, request: HttpServletRequest) {
        ActionMessages.getOrCreateMessages(request).addMessage("appConnected",
                "GitHub App with id ${connection.appId} was connected successfully")
    }

    private fun connectionRemoved(appId: String, request: HttpServletRequest) {
        ActionMessages.getOrCreateMessages(request).addMessage("connectionDeleted",
                "Connection to the GitHub App with id $appId was deleted")
    }

    private fun findConnection(appId: String): ApplicationConnectionDescriptor? {
        return connectionManager.findConnectionDescriptor(appId)
    }

    private fun removeConnection(appId: String) {
        try {
            connectionManager.removeConnection(appId)
        } catch (th: Throwable) {
            throw ApplicationConnectionException("Failed to remove connection for the application $appId", th)
        }
    }

    private fun addConnection(
            ownerUrl: String,
            appId: String,
            secret: String,
            key: ByteArray
    ): ApplicationConnectionDescriptor {
        val (githubUrl, owner) = getHostAndOwner(ownerUrl)
//        if (authManager.findConnectionDescriptor(githubUrl, owner) != null) {
//            throw ApplicationConnectionException("Application for the owner $owner already exists")
//        }

        try {
            val connection = ApplicationConnectionDescriptor(githubUrl, owner, appId, secret)
            connectionManager.addApplicationConnection(connection, key)
            return connection
        } catch (e: PrivateKeyContentParseException) {
            throw ApplicationConnectionException("Failed to parse PEM content")
        } catch (e: GithubTokenGenerationException) {
            throw ApplicationConnectionException("Failed to generate jwt for github api")
        } catch (th: Throwable) {
            throw ApplicationConnectionException(reason = th)
        }
    }

    private fun finishConnection(appId: String, code: String): ApplicationConnectionDescriptor {
        try {
            val reservedConnection = connectionManager.findConnectionDescriptor(appId)
                    ?: throw ApplicationConnectionException("Cannot find reserved connection descriptor with temporary id $appId")

            val appConfiguration = githubApiFacade.getAppConfiguration(reservedConnection.githubUrl, code)
            val connection = ApplicationConnectionDescriptor(
                    reservedConnection.githubUrl,
                    reservedConnection.owner,
                    appConfiguration.id,
                    appConfiguration.webhookSecret,
                    appName = appConfiguration.slug,
                    ownerId = appConfiguration.owner.id
            )
            connectionManager.addApplicationConnection(connection, appConfiguration.pem.toByteArray())
            connectionManager.removeConnection(reservedConnection.appId)

            return connection
        } catch (th: Throwable) {
            log.error("Failed to finish connecting a github app", th)
            throw th
        }
    }

    private fun getAppGenerationLink(targetUrl: String, isOrganization: Boolean): String {
        val (githubUrl, owner) = getHostAndOwner(targetUrl)

        return connectionManager.getAppGenerationLink(githubUrl, owner, isOrganization)
    }

    private fun getHostAndOwner(link: String): Pair<String, String> {
        try {
            val url = URL(link)

            return "${url.protocol}://${url.host}" to url.path.removePrefix("/").split("/")[0]
        } catch (th: Throwable) {
            throw ApplicationConnectionException("Owner url is malformed")
        }
    }
}