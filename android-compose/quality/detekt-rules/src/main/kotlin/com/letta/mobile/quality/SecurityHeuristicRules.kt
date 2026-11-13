package com.letta.mobile.quality

import io.gitlab.arturbosch.detekt.api.Config
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtBinaryExpression
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtClass
import org.jetbrains.kotlin.psi.KtExpression
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtLiteralStringTemplateEntry
import org.jetbrains.kotlin.psi.KtNameReferenceExpression
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtParameter
import org.jetbrains.kotlin.psi.KtParenthesizedExpression
import org.jetbrains.kotlin.psi.KtPostfixExpression
import org.jetbrains.kotlin.psi.KtQualifiedExpression
import org.jetbrains.kotlin.psi.KtStringTemplateExpression

/**
 * Flags string matching on a value whose name says it is a host or URL (`host`, `hostname`, `url`,
 * `uri`, `baseUrl`, `origin`, `endpoint`, or a name ending in `Url`, `Uri`, `Host`, `Origin`,
 * `Endpoint`): `startsWith`, `endsWith`, `contains`, `substringBefore/After`, `split`, `matches`, a
 * regex applied to it, or `"literal" in url`. Also flags building a URL by appending a path to such a
 * value (`"$baseUrl/v1"` or `baseUrl + "/v1"`). String checks on URLs mis-handle userinfo, ports,
 * case, encoding and look-alike hosts; parse the value and compare its components instead.
 *
 * Name-based and syntactic, production sources only.
 */
internal class HostOrUrlStringMatching(config: Config = Config.empty) : MobileRule(
    config,
    "HostOrUrlStringMatching",
    "Hosts and URLs must be parsed and compared by component, not matched or built as strings.",
) {
    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource() || file.isGenerated()) return
        super.visitKtFile(file)
    }

    override fun visitCallExpression(expression: KtCallExpression) {
        super.visitCallExpression(expression)
        val name = expression.calleeExpression?.text
        val target = when (name) {
            in STRING_MATCHERS -> expression.receiver()
            in REGEX_MATCHERS -> expression.valueArguments.firstOrNull()?.getArgumentExpression()
            else -> null
        }
        target?.let(::urlLikeName)?.let { reportMatching(expression, it) }
    }

    override fun visitBinaryExpression(expression: KtBinaryExpression) {
        super.visitBinaryExpression(expression)
        val left = expression.left ?: return
        val right = expression.right ?: return
        when (expression.operationToken) {
            KtTokens.IN_KEYWORD, KtTokens.NOT_IN ->
                if (left is KtStringTemplateExpression) urlLikeName(right)?.let { reportMatching(expression, it) }
            KtTokens.PLUS ->
                if (startsWithPath(right)) urlLikeName(left)?.let { reportBuilding(expression, it) }
            else -> Unit
        }
    }

    override fun visitStringTemplateExpression(expression: KtStringTemplateExpression) {
        super.visitStringTemplateExpression(expression)
        expression.entries.toList().zipWithNext()
            .firstOrNull { (value, next) ->
                next is KtLiteralStringTemplateEntry && next.text.startsWith("/") &&
                    value.expression?.let(::urlLikeName) != null
            }
            ?.let { (value, _) -> reportBuilding(expression, requireNotNull(value.expression).text) }
    }

    private fun KtCallExpression.receiver(): KtExpression? =
        (parent as? KtQualifiedExpression)?.takeIf { it.selectorExpression == this }?.receiverExpression

    private fun startsWithPath(expression: KtExpression): Boolean =
        (expression as? KtStringTemplateExpression)?.entries?.firstOrNull()
            ?.let { it is KtLiteralStringTemplateEntry && it.text.startsWith("/") } == true

    /** The host/URL-like name the [expression] reads, looking through `.toString()`-style wrappers. */
    private fun urlLikeName(expression: KtExpression): String? {
        val name = when (expression) {
            is KtParenthesizedExpression -> expression.expression?.let(::urlLikeName)
            is KtPostfixExpression -> expression.baseExpression?.let(::urlLikeName)
            is KtNameReferenceExpression -> expression.getReferencedName()
            is KtQualifiedExpression -> qualifiedName(expression)
            else -> null
        }
        return name?.takeIf(::isUrlLikeName)
    }

    private fun qualifiedName(expression: KtQualifiedExpression): String? {
        val selector = expression.selectorExpression
        return when {
            selector is KtNameReferenceExpression -> selector.getReferencedName()
            selector is KtCallExpression && selector.calleeExpression?.text in TRANSPARENT_CALLS ->
                urlLikeName(expression.receiverExpression)
            else -> null
        }
    }

    private fun reportMatching(element: KtExpression, name: String) = report(
        element,
        "Do not match '$name' as a string: parse it (io.ktor.http.Url, java.net.URI) and compare the " +
            "scheme, host, port or path component explicitly.",
    )

    private fun reportBuilding(element: KtExpression, name: String) = report(
        element,
        "Do not build a URL by appending a path to '$name': parse the base and use URLBuilder " +
            "(appendPathSegments) or URI.resolve so slashes, encoding and query strings stay correct.",
    )

    private companion object {
        val STRING_MATCHERS = setOf(
            "startsWith", "endsWith", "contains", "substringBefore", "substringAfter", "substringBeforeLast",
            "substringAfterLast", "split", "matches",
        )
        val REGEX_MATCHERS = setOf("containsMatchIn", "matchEntire", "find", "findAll")
        val TRANSPARENT_CALLS = setOf("toString", "lowercase", "uppercase", "trim", "orEmpty")
        val URL_NAME = Regex(
            "(?i:host|hostname|url|uri|baseurl|origin|endpoint)|" +
                "[a-z][A-Za-z0-9]*(Url|URL|Uri|URI|Host|Hostname|HostName|Origin|Endpoint)",
        )

        fun isUrlLikeName(name: String): Boolean = URL_NAME.matches(name)
    }
}

/**
 * Flags a `data class` whose generated `toString` would print a secret: a constructor property named
 * like a credential (last word `token`, `secret`, `password`, `passphrase`, `credential(s)`,
 * `bearer`, or `apiKey` / `privateKey` / `secretKey` / `accessKey` / `signingKey`) typed `String`,
 * `CharArray` or `ByteArray`, or, in a `secrets` / `keystore` package, such a property named `value`.
 * Logging, crash reports and string templates then leak it. Pagination and count names
 * (`nextPageToken`, `maxTokens`) are not credentials and are skipped.
 */
internal class SecretBearingDataClass(config: Config = Config.empty) : MobileRule(
    config,
    "SecretBearingDataClass",
    "Data classes holding secrets must override toString so the value is never printed.",
) {
    private var inSecretPackage = false

    override fun visitKtFile(file: KtFile) {
        if (file.isTestSource() || file.isGenerated()) return
        val segments = file.packageFqName.asString().split('.')
        inSecretPackage = segments.any { it == "secrets" || it == "keystore" }
        super.visitKtFile(file)
    }

    override fun visitClass(klass: KtClass) {
        super.visitClass(klass)
        if (!klass.isData() || klass.overridesToString()) return
        klass.primaryConstructorParameters
            .filter { it.hasValOrVar() && it.holdsSecretText() }
            .forEach { parameter ->
                report(
                    parameter,
                    "'${parameter.name}' is printed by the generated toString of data class '${klass.name}': " +
                        "override toString to redact it (for example \"${klass.name}(${parameter.name}=***)\") " +
                        "or wrap it in a type that does.",
                )
            }
    }

    private fun KtClass.overridesToString(): Boolean = declarations.filterIsInstance<KtNamedFunction>().any {
        it.name == "toString" && it.valueParameters.isEmpty() && it.hasModifier(KtTokens.OVERRIDE_KEYWORD)
    }

    private fun KtParameter.holdsSecretText(): Boolean {
        val type = typeReference?.text?.removeSuffix("?")?.substringAfterLast('.')
        val name = name.orEmpty()
        val secretValue = inSecretPackage && name == "value"
        return type in SECRET_CARRIERS && (secretValue || isSecretName(name))
    }

    private fun isSecretName(name: String): Boolean {
        val words = WORD_BOUNDARY.split(name).filter(String::isNotEmpty).map(String::lowercase)
        val last = words.lastOrNull()
        val lastTwo = words.takeLast(2).joinToString("")
        val isCredential = last in SECRET_WORDS || lastTwo in SECRET_PAIRS
        return isCredential && words.none { it in NON_SECRET_WORDS }
    }

    private companion object {
        val WORD_BOUNDARY = Regex("_|(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])")
        val SECRET_CARRIERS = setOf("String", "CharArray", "ByteArray")
        val SECRET_WORDS = setOf(
            "token", "secret", "password", "passphrase", "passwd", "credential", "credentials", "bearer",
            "apikey", "privatekey",
        )
        val SECRET_PAIRS = setOf("apikey", "privatekey", "secretkey", "accesskey", "signingkey")
        val NON_SECRET_WORDS = setOf("page", "cursor", "continuation", "next", "count", "max", "min", "limit")
    }
}
