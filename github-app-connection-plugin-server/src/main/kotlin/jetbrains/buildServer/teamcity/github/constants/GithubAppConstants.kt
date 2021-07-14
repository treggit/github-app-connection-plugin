package jetbrains.buildServer.teamcity.github.constants

import java.util.regex.Pattern

object GithubAppConstants {

    const val RUNNER_TYPE_NAME = "github_actions"
    const val GITHUB_ACTIONS_PARAMETER = "github.actions.runner.internal.isRunnerEnabled"
    const val GITHUB_ACTIONS_TEMP_DIRECTORY = "github_actions"
    const val METADATA_FILE_PATH = "metadataFile"
    const val ACTION_INPUTS = "actionInputs"
    const val ACTION_SPECIFICATION = "actionSpec"
    const val COMPOSITE_ACTION_TYPE = "composite"
    const val JAVA_SCRIPT_ACTION_TYPE = "node12"
    const val JAVA_SCRIPT_ACTION_EXECUTABLE = "node"
    const val COMPOSITE_ACTION_STEP_NAME_PROPERTY = "teamcity.githubActions.runner.composite.step.name"
    const val ACTIONS_WORKING_DIRECTORY = ".github_actions"
    const val WRAPPER_ACTION_TYPE = "wrapperActionType"
    const val WRAPPED_ACTION_ID = "wrappedActionId"
    const val DEFAULT_ACTION_METADATA_FILE = "action.yml"
    const val GIT_VCS_NAME = "jetbrains.git"
    const val JOB_STATUS_PARAMETER = "job.status"
    const val MIGRATED_ROOT_MARK = "migrated.root"
    const val PLUGIN_DATA_DIRECTORY = RUNNER_TYPE_NAME
    const val TOKEN_PERMISSIONS_PARAMETER_PREFIX = "github.token.permissions"
    const val TRUE_CONSTANT_PARAMETER_NAME = "TRUE"

    val EXPRESSIONS_PATTERN = Pattern.compile("\\$\\{\\{(.+?)}}")

    const val ALL_PERMISSIONS = "all"
    const val GITHUB_TOKEN = "github.token"
}