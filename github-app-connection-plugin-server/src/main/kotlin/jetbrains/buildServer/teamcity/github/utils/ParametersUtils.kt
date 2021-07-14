package jetbrains.buildServer.teamcity.github.parameters

object ParametersUtils {

    fun makeEnvVariables(parameters: Map<String, String>): Map<String, String> {
        val env = HashMap<String, String>()
        for (name in parameters.keys) {
            env[makeEnvVariable(name)] = "%$name%"
        }

        return env
    }

    fun makeEnvVariable(name: String) = "env.${name.replace(".", "_").toUpperCase()}"

    fun getPropertiesList(properties: String, caseInsensitive: Boolean = true) = properties
            .split(",")
            .filter { !it.isBlank() }
            .map { it.trim() }
            .also { list -> if (caseInsensitive) list.map { it.toLowerCase() } }
}