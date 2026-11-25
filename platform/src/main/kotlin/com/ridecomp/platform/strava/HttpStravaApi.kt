package com.ridecomp.platform.strava

import android.webkit.CookieManager
import com.ridecomp.core.config.StravaConfig
import com.ridecomp.core.model.StravaError
import com.ridecomp.core.model.StravaSetup
import com.ridecomp.core.model.StravaTokens
import com.ridecomp.core.source.Clock
import com.ridecomp.core.source.DispatcherProvider
import com.ridecomp.core.source.Logger
import com.ridecomp.core.source.StravaApi
import com.ridecomp.core.source.StravaGrant
import com.ridecomp.core.source.StravaUploadReply
import com.ridecomp.core.source.StravaUploadRequest
import com.ridecomp.core.util.Outcome
import com.ridecomp.core.util.asFailure
import com.ridecomp.core.util.asSuccess
import com.ridecomp.platform.system.TrafficTags
import com.ridecomp.platform.system.withTrafficTag
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class HttpStravaApi(
    @Suppress("UNUSED_PARAMETER") private val setup: StravaSetup,
    @Suppress("UNUSED_PARAMETER") private val clock: Clock,
    private val dispatchers: DispatcherProvider,
    private val logger: Logger,
) : StravaApi {

    override suspend fun exchangeCode(code: String): Outcome<StravaGrant, StravaError> = withContext(dispatchers.io) {
        withTrafficTag(TrafficTags.STRAVA) {
            when (val csrf = fetchCsrfTokenNow(code)) {
                is Outcome.Success -> {
                    val tokens = StravaTokens(
                        accessToken = code,
                        refreshToken = WEB_SESSION_REFRESH_MARKER,
                        expiresAtS = WEB_SESSION_EXPIRES_AT_S,
                    )
                    StravaGrant(tokens = tokens, athleteName = "Strava WebSession").asSuccess()
                }
                is Outcome.Failure -> csrf
            }
        }
    }

    override suspend fun refresh(refreshToken: String): Outcome<StravaGrant, StravaError> =
        StravaError.UNAUTHORIZED.asFailure()

    override suspend fun revoke(token: String): Outcome<Unit, StravaError> = withContext(dispatchers.io) {
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        } catch (error: Exception) {
            logger.warn(TAG, error) { "Failed to clear WebView cookies" }
        }
        Unit.asSuccess()
    }

    override suspend fun upload(
        accessToken: String,
        request: StravaUploadRequest,
    ): Outcome<StravaUploadReply, StravaError> = withContext(dispatchers.io) {
        withTrafficTag(TrafficTags.STRAVA) {
            val csrfToken = when (val csrf = fetchCsrfTokenNow(accessToken)) {
                is Outcome.Success -> csrf.value
                is Outcome.Failure -> return@withTrafficTag csrf
            }

            val boundary = BOUNDARY_PREFIX + UUID.randomUUID().toString().replace("-", "")
            val fields = listOf(
                "authenticity_token" to csrfToken,
                "_method" to "post",
                "name" to request.name,
                "activity_type" to StravaConfig.SPORT_TYPE,
            )

            val reply = sendNow(
                HttpRequest(
                    url = UPLOAD_FILES_URL,
                    headers = mapOf(
                        COOKIE to accessToken,
                        USER_AGENT to BROWSER_USER_AGENT,
                        X_CSRF_TOKEN to csrfToken,
                        X_REQUESTED_WITH to XML_HTTP_REQUEST,
                        ORIGIN to STRAVA_ORIGIN,
                        REFERER to UPLOAD_SELECT_URL,
                        ACCEPT to JSON_ACCEPT,
                    ),
                    contentType = "$MULTIPART_CONTENT_TYPE$boundary",
                    writeBody = { out -> writeMultipart(out, boundary, fields, request) },
                ),
            )
            reply.toWebUploadReply()
        }
    }

    override suspend fun uploadStatus(
        accessToken: String,
        uploadId: Long,
    ): Outcome<StravaUploadReply, StravaError> = withContext(dispatchers.io) {
        withTrafficTag(TrafficTags.STRAVA) {
            sendNow(
                HttpRequest(
                    url = "$UPLOAD_PROGRESS_URL?ids=$uploadId",
                    headers = mapOf(
                        COOKIE to accessToken,
                        USER_AGENT to BROWSER_USER_AGENT,
                        X_REQUESTED_WITH to XML_HTTP_REQUEST,
                        REFERER to UPLOAD_SELECT_URL,
                        ACCEPT to JSON_ACCEPT,
                    ),
                    method = GET,
                ),
            ).toWebUploadReply(fallbackUploadId = uploadId)
        }
    }

    private fun fetchCsrfTokenNow(cookie: String): Outcome<String, StravaError> {
        val connection = try {
            URL(UPLOAD_SELECT_URL).openConnection() as? HttpsURLConnection
        } catch (error: IOException) {
            logger.warn(TAG, error) { "Could not open Strava upload page" }
            null
        } ?: return StravaError.OFFLINE.asFailure()

        return try {
            connection.apply {
                requestMethod = GET
                connectTimeout = StravaConfig.CONNECT_TIMEOUT_MS
                readTimeout = StravaConfig.READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                setRequestProperty(COOKIE, cookie)
                setRequestProperty(USER_AGENT, BROWSER_USER_AGENT)
                setRequestProperty(ACCEPT, HTML_ACCEPT)
            }
            val status = connection.responseCode
            when {
                status in HTTP_OK_RANGE -> {
                    val token = extractCsrfToken(connection.inputStream)
                    if (!token.isNullOrBlank()) {
                        token.asSuccess()
                    } else {
                        StravaError.UNAUTHORIZED.asFailure()
                    }
                }
                status in 300..399 -> StravaError.UNAUTHORIZED.asFailure()
                else -> status.toError().asFailure()
            }
        } catch (error: IOException) {
            logger.warn(TAG, error) { "Failed to fetch Strava CSRF token" }
            StravaError.OFFLINE.asFailure()
        } finally {
            connection.disconnect()
        }
    }

    private fun extractCsrfToken(input: InputStream): String? =
        BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val match = CSRF_REGEX.find(line.orEmpty()) ?: CSRF_REGEX_ALT.find(line.orEmpty())
                if (match != null) return match.groupValues[1]
            }
            null
        }

    private fun Outcome<String, StravaError>.toWebUploadReply(
        fallbackUploadId: Long? = null,
    ): Outcome<StravaUploadReply, StravaError> = when (this) {
        is Outcome.Success -> parseWebUploadReply(value, fallbackUploadId)?.asSuccess()
            ?: StravaError.BAD_RESPONSE.asFailure()
        is Outcome.Failure -> this
    }

    private fun parseWebUploadReply(raw: String, fallbackUploadId: Long?): StravaUploadReply? {
        return try {
            val trimmed = raw.trim()
            val obj = when {
                trimmed.startsWith("[") -> {
                    val arr = JSONArray(trimmed)
                    if (arr.length() == 0) return null
                    arr.getJSONObject(0)
                }
                trimmed.startsWith("{") -> JSONObject(trimmed)
                else -> return null
            }
            val id = obj.optLong("id", 0L).takeIf { it > 0L } ?: fallbackUploadId ?: return null
            val activityId = obj.optLong("activity_id", 0L).takeIf { it > 0L }
                ?: obj.optJSONObject("activity")?.optLong("id", 0L)?.takeIf { it > 0L }
            val error = obj.optString("error").takeIf { it.isNotBlank() && it != "null" }
            StravaUploadReply(id = id, activityId = activityId, error = error)
        } catch (_: Exception) {
            null
        }
    }

    private fun writeMultipart(
        out: OutputStream,
        boundary: String,
        fields: List<Pair<String, String>>,
        request: StravaUploadRequest,
    ) {
        fun line(text: String = "") = out.write("$text$CRLF".toByteArray(StandardCharsets.UTF_8))
        fields.forEach { (name, value) ->
            line("--$boundary")
            line("$CONTENT_DISPOSITION: form-data; name=\"$name\"")
            line()
            line(value)
        }
        line("--$boundary")
        line("$CONTENT_DISPOSITION: form-data; name=\"$FILES_FIELD\"; filename=\"${request.fileName.safeFileName()}\"")
        line("$CONTENT_TYPE: $OCTET_STREAM")
        line()
        File(request.filePath).inputStream().use { it.copyTo(out, BUFFER_BYTES) }
        line()
        line("--$boundary--")
    }

    private fun String.safeFileName(): String = filter { it.isLetterOrDigit() || it in SAFE_NAME_CHARS }

    private fun sendNow(request: HttpRequest): Outcome<String, StravaError> {
        val connection = try {
            URL(request.url).openConnection() as? HttpsURLConnection
        } catch (error: IOException) {
            logger.warn(TAG, error) { "Could not open a connection" }
            null
        } ?: return StravaError.OFFLINE.asFailure()

        return try {
            connection.apply {
                requestMethod = request.method
                connectTimeout = StravaConfig.CONNECT_TIMEOUT_MS
                readTimeout = StravaConfig.READ_TIMEOUT_MS
                instanceFollowRedirects = false
                useCaches = false
                request.headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            request.writeBody?.let { write ->
                connection.doOutput = true
                connection.setRequestProperty(CONTENT_TYPE, request.contentType)
                connection.setChunkedStreamingMode(0)
                connection.outputStream.use(write)
            }
            val status = connection.responseCode
            if (status in HTTP_OK_RANGE) {
                connection.inputStream.readLimited()?.asSuccess() ?: StravaError.BAD_RESPONSE.asFailure()
            } else {
                logger.info(TAG) { "Strava web replied $status" }
                status.toError().asFailure()
            }
        } catch (error: IOException) {
            logger.warn(TAG, error) { "Strava web request failed" }
            StravaError.OFFLINE.asFailure()
        } finally {
            connection.disconnect()
        }
    }

    private fun InputStream.readLimited(): String? = use { input ->
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_BYTES)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            if (out.size() > StravaConfig.MAX_RESPONSE_BYTES) return null
        }
        out.toString(StandardCharsets.UTF_8.name())
    }

    private fun Int.toError(): StravaError = when (this) {
        HttpURLConnection.HTTP_BAD_REQUEST,
        HttpURLConnection.HTTP_UNAUTHORIZED,
        HttpURLConnection.HTTP_FORBIDDEN,
        in 300..399 -> StravaError.UNAUTHORIZED
        HTTP_TOO_MANY_REQUESTS -> StravaError.RATE_LIMITED
        in HTTP_SERVER_ERROR_RANGE -> StravaError.SERVER
        else -> StravaError.BAD_RESPONSE
    }

    internal class HttpRequest(
        val url: String,
        val headers: Map<String, String>,
        val contentType: String? = null,
        val method: String = POST,
        val writeBody: ((OutputStream) -> Unit)? = null,
    )

    private companion object {
        const val TAG = "StravaWeb"
        const val POST = "POST"
        const val GET = "GET"
        const val STRAVA_ORIGIN = "https://www.strava.com"
        const val UPLOAD_SELECT_URL = "https://www.strava.com/upload/select"
        const val UPLOAD_FILES_URL = "https://www.strava.com/upload/files"
        const val UPLOAD_PROGRESS_URL = "https://www.strava.com/upload/progress.json"
        const val WEB_SESSION_REFRESH_MARKER = "web_session"
        const val WEB_SESSION_EXPIRES_AT_S = 4_102_444_800L

        const val COOKIE = "Cookie"
        const val USER_AGENT = "User-Agent"
        const val X_CSRF_TOKEN = "X-CSRF-Token"
        const val X_REQUESTED_WITH = "X-Requested-With"
        const val XML_HTTP_REQUEST = "XMLHttpRequest"
        const val ORIGIN = "Origin"
        const val REFERER = "Referer"
        const val ACCEPT = "Accept"
        const val CONTENT_TYPE = "Content-Type"
        const val CONTENT_DISPOSITION = "Content-Disposition"
        const val MULTIPART_CONTENT_TYPE = "multipart/form-data; boundary="
        const val BOUNDARY_PREFIX = "RideCompWeb"
        const val OCTET_STREAM = "application/octet-stream"
        const val FILES_FIELD = "files[]"
        const val SAFE_NAME_CHARS = "._-"
        const val CRLF = "\r\n"
        const val JSON_ACCEPT = "application/json, text/javascript, */*; q=0.01"
        const val HTML_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"
        const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        val CSRF_REGEX = Regex("""<meta\s+name=["']csrf-token["']\s+content=["']([^"']+)["']""")
        val CSRF_REGEX_ALT = Regex("""<meta\s+content=["']([^"']+)["']\s+name=["']csrf-token["']""")

        const val HTTP_TOO_MANY_REQUESTS = 429
        const val BUFFER_BYTES = 8 * 1024
        val HTTP_OK_RANGE = 200..299
        val HTTP_SERVER_ERROR_RANGE = 500..599
    }
}
