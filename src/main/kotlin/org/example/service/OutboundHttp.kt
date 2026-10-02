package org.example.service

import io.quarkus.logging.Log
import jakarta.enterprise.context.ApplicationScoped
import org.eclipse.microprofile.config.inject.ConfigProperty
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.Optional

/**
 * Исходящие запросы во внешний мир (KLIPY/GIPHY, Telegram).
 *
 *  - HTTP/1.1: java HttpClient по умолчанию пробует HTTP/2, и с частью CDN/WAF это
 *    заканчивается зависанием до таймаута;
 *  - браузерный User-Agent: с «Java-http-client/…» некоторые API за Cloudflare
 *    молча держат соединение или отвечают 403;
 *  - straycatz.http.proxy — если из сети сервера сервис недоступен (блокировки,
 *    корпоративный файрвол), всё внешнее идёт через прокси: http://host:port.
 */
@ApplicationScoped
class OutboundHttp(
    @ConfigProperty(name = "straycatz.http.proxy") proxy: Optional<String>,
    @ConfigProperty(name = "straycatz.http.timeout", defaultValue = "PT10S") private val timeout: Duration,
) {
    companion object {
        const val USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36 straycatz/1.0"
    }

    /** Результат запроса с понятной причиной ошибки — для логов и /status. */
    data class Result<T>(val status: Int, val body: T?, val error: String?, val millis: Long) {
        val ok get() = status in 200..299 && body != null
    }

    val client: HttpClient = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(Duration.ofSeconds(6))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .also { b ->
            proxy.filter { it.isNotBlank() }.ifPresent { p ->
                val u = URI.create(if ("://" in p) p else "http://$p")
                b.proxy(ProxySelector.of(InetSocketAddress(u.host, if (u.port > 0) u.port else 8080)))
                Log.infof("исходящие запросы — через прокси %s:%d", u.host, if (u.port > 0) u.port else 8080)
            }
        }
        .build()

    fun request(url: String, accept: String = "application/json"): HttpRequest.Builder =
        HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("User-Agent", USER_AGENT)
            .header("Accept", accept)
            .header("Accept-Language", "ru,en;q=0.8")

    fun getString(url: String, accept: String = "application/json"): Result<String> = run {
        client.send(request(url, accept).GET().build(), HttpResponse.BodyHandlers.ofString())
    }

    fun getFile(url: String, target: Path): Result<Path> = run {
        client.send(request(url, "*/*").GET().build(), HttpResponse.BodyHandlers.ofFile(target))
    }

    private fun <T> run(call: () -> HttpResponse<T>): Result<T> {
        val start = System.nanoTime()
        return try {
            val res = call()
            Result(res.statusCode(), res.body(), if (res.statusCode() in 200..299) null else "HTTP ${res.statusCode()}", ms(start))
        } catch (e: java.net.http.HttpConnectTimeoutException) {
            Result(0, null, "не смогли подключиться за 6 с (сеть/блокировка — попробуй straycatz.http.proxy)", ms(start))
        } catch (e: java.net.http.HttpTimeoutException) {
            Result(0, null, "сервер не ответил за ${timeout.seconds} с", ms(start))
        } catch (e: java.net.ConnectException) {
            Result(0, null, "соединение отклонено: ${e.message}", ms(start))
        } catch (e: java.nio.channels.UnresolvedAddressException) {
            Result(0, null, "не резолвится DNS", ms(start))
        } catch (e: javax.net.ssl.SSLException) {
            Result(0, null, "TLS: ${e.message} (подмена сертификата прокси/антивирусом?)", ms(start))
        } catch (e: Exception) {
            Result(0, null, "${e.javaClass.simpleName}: ${e.message}", ms(start))
        }
    }

    private fun ms(start: Long) = (System.nanoTime() - start) / 1_000_000
}
