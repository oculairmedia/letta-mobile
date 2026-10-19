package com.letta.mobile.data.repository.modelcontrol

import io.ktor.client.HttpClient
import io.ktor.client.request.accept
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.Parameters
import io.ktor.http.content.TextContent
import kotlinx.serialization.json.JsonObject

/**
 * The few HTTPS calls a subscription sign-in makes to the provider's own
 * authorization server. The App Server takes only the
 * finished tokens (`connect_provider.oauth_config`), so the app runs the
 * device-code or code exchange itself. Kept to one POST so the flows are
 * tested against a fake token endpoint; hosts supply a client without auth
 * headers or request logging, since bodies carry codes and tokens.
 */
fun interface ProviderOAuthHttp {
    suspend fun post(url: String, body: OAuthRequestBody): OAuthHttpResponse
}

/** A request body; [toString] names keys only. */
sealed interface OAuthRequestBody {
    data class Form(val fields: Map<String, String>) : OAuthRequestBody {
        override fun toString(): String = "Form(keys=${fields.keys.sorted()})"
    }

    data class Json(val body: JsonObject) : OAuthRequestBody {
        override fun toString(): String = "Json(keys=${body.keys.sorted()})"
    }
}

/** Status and raw body; [toString] leaves the body out (a token response is a credential). */
class OAuthHttpResponse(val status: Int, val body: String) {
    val ok: Boolean get() = status in HTTP_OK_RANGE

    override fun toString(): String = "OAuthHttpResponse(status=$status)"

    private companion object {
        val HTTP_OK_RANGE = 200..299
    }
}

/** [ProviderOAuthHttp] over a Ktor client the host built for this purpose. */
class KtorProviderOAuthHttp(private val client: HttpClient) : ProviderOAuthHttp {
    override suspend fun post(url: String, body: OAuthRequestBody): OAuthHttpResponse {
        val response = client.post(url) {
            accept(ContentType.Application.Json)
            when (body) {
                is OAuthRequestBody.Form -> setBody(FormDataContent(Parameters.build { body.fields.forEach { (k, v) -> append(k, v) } }))
                is OAuthRequestBody.Json -> setBody(TextContent(body.body.toString(), ContentType.Application.Json))
            }
        }
        return OAuthHttpResponse(response.status.value, response.bodyAsText())
    }
}

/**
 * The platform's sign-in client, or null where the app cannot reach a
 * provider's authorization server (the browser build); OAuth rows then fall
 * back to the host terminal steps.
 */
expect fun defaultProviderOAuthHttp(): ProviderOAuthHttp?
