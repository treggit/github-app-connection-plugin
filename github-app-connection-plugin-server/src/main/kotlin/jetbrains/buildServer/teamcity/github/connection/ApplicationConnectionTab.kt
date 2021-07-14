package jetbrains.buildServer.teamcity.github.connection

import jetbrains.buildServer.controllers.admin.projects.EditProjectTab
import jetbrains.buildServer.web.openapi.PagePlaces
import jetbrains.buildServer.web.openapi.PluginDescriptor
import javax.servlet.http.HttpServletRequest

class ApplicationConnectionTab(
        pagePlaces: PagePlaces,
        pluginDescriptor: PluginDescriptor,
        private val applicationConnectionManager: GithubApplicationConnectionManager
) : EditProjectTab(
        pagePlaces,
        PLUGIN_NAME,
        pluginDescriptor.getPluginResourcesPath("editGithubApplicationConnection.jsp"),
        "GitHub App"
) {

    companion object {
        const val PLUGIN_NAME = "githubApp"
    }

    override fun isAvailable(request: HttpServletRequest) = getProject(request)?.isRootProject ?: false

    override fun fillModel(model: MutableMap<String, Any>, request: HttpServletRequest) {
        model["connections"] = applicationConnectionManager.getAllConnections()
    }
}