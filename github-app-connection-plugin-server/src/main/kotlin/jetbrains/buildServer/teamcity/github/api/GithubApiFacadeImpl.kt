package jetbrains.buildServer.teamcity.github.api

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import jetbrains.buildServer.teamcity.github.connection.domain.ApplicationConnectionDescriptor
import jetbrains.buildServer.teamcity.github.connection.domain.GithubAppConfiguration
import jetbrains.buildServer.teamcity.github.connection.domain.GithubTokenDescription
import jetbrains.buildServer.util.ssl.SSLContextUtil
import jetbrains.buildServer.util.ssl.SSLTrustStoreProvider
import org.apache.http.HttpResponse
import org.apache.http.client.methods.HttpUriRequest
import org.apache.http.client.methods.RequestBuilder
import org.apache.http.client.utils.URIBuilder
import org.apache.http.entity.ContentType
import org.apache.http.entity.StringEntity
import org.apache.http.impl.client.HttpClientBuilder
import org.apache.http.impl.client.LaxRedirectStrategy
import org.apache.http.message.BasicHeader
import java.net.URI

class GithubApiFacadeImpl(
        sslTrustStoreProvider: SSLTrustStoreProvider,
) : GithubApiFacade {

    private val httpClient = HttpClientBuilder.create()
            .setSslcontext(SSLContextUtil.createUserSSLContext(sslTrustStoreProvider.trustStore))
            .setRedirectStrategy(LaxRedirectStrategy())
            .build()

    private val objectMapper = ObjectMapper().apply {
        registerKotlinModule()
    }

    companion object {
        private const val AUTHORIZATION_HEADER = "Authorization"
    }

    private fun apiUrl(serverUrl: String): String {
        val host = "api.${URI(serverUrl).host}"
        return URIBuilder(serverUrl).setHost(host).build().toString()
    }

    private fun <T> withExceptionsWrapper(action: () -> T): T {
        try {
            return action()
        } catch (e: GithubRequestException) {
            throw e
        }
        catch (th: Throwable) {
            throw GithubRequestException(reason = th)
        }
    }

    override fun listInstallations(connectionDescriptor: ApplicationConnectionDescriptor, token: String): JsonNode = withExceptionsWrapper {
        RequestBuilder.get()
                .setUri("${apiUrl(connectionDescriptor.githubUrl)}/app/installations")
                .build()
                .executeRequest(token)
                .parse()
    }

    override fun getAppConfiguration(githubUrl: String, code: String): GithubAppConfiguration = withExceptionsWrapper {
        RequestBuilder.post()
                .setUri("${apiUrl(githubUrl)}/app-manifests/$code/conversions")
                .build()
                .executeRequest()
                .parse(GithubAppConfiguration::class.java)
    }

    override fun createInstallationToken(
            connectionDescriptor: ApplicationConnectionDescriptor,
            installationId: String,
            repository: String,
            token: String,
            permissions: Map<String, String>
    ) = withExceptionsWrapper {
        val body = HashMap<String, Any>().apply {
            put("repositories", listOf(repository))
            if (permissions.isNotEmpty()) {
                put("permissions", permissions)
            }
        }
        val token = RequestBuilder.post()
                .setUri("${apiUrl(connectionDescriptor.githubUrl)}/app/installations/$installationId/access_tokens")
                .setEntity(StringEntity(objectMapper.writeValueAsString(body), ContentType.APPLICATION_JSON))
                .build()
                .executeRequest(token)
                .getField("token")
                ?: throw GithubRequestException("API response does not contain token")
        /* return */ GithubTokenDescription(connectionDescriptor.githubUrl, connectionDescriptor.appId, token)
    }

    override fun revokeInstallationToken(token: GithubTokenDescription) {
        withExceptionsWrapper {
            RequestBuilder.delete()
                    .setUri("${apiUrl(token.githubUrl)}/installation/token")
                    .setHeader(BasicHeader(AUTHORIZATION_HEADER, "token ${token.token}"))
                    .build()
                    .executeRequest()
        }
    }

    private fun HttpUriRequest.executeRequest(token: String? = null, expectSuccessCode: Boolean = true): HttpResponse {
        if (token != null) {
            addHeader(BasicHeader(AUTHORIZATION_HEADER, "Bearer $token"))
        }
        return httpClient.execute(this).also {
            if (it.statusLine.statusCode >= 300 && expectSuccessCode) {
                throw GithubRequestException("API method ${this.uri} responded with code ${it.statusLine.statusCode}")
            }
        }
    }

    private fun HttpResponse.getField(vararg fields: String): String? {
        var node: JsonNode? = parse()
        for (field in fields) {
            node = node?.get(field)
        }
        return node?.asText()
    }

    private fun HttpResponse.parse(): JsonNode = objectMapper.readTree(entity.content)

    private fun <T> HttpResponse.parse(cl: Class<T>): T = objectMapper.readValue(entity.content, cl)
}