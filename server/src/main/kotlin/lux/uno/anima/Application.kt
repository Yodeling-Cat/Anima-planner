package lux.uno.anima

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import lux.uno.anima.server.ErrorBody
import lux.uno.anima.server.animaRoutes
import lux.uno.anima.server.auth.Accounts
import lux.uno.anima.server.blobs.BlobStore
import lux.uno.anima.server.db.Database
import lux.uno.anima.server.sync.SyncStore
import org.slf4j.event.Level
import kotlin.time.Duration.Companion.minutes

fun main() {
    val config = ServerConfig.fromEnvironment()
    embeddedServer(Netty, port = config.port, host = config.host) {
        module(config)
    }.start(wait = true)
}

fun Application.module(config: ServerConfig = ServerConfig.fromEnvironment()) {
    val database = Database(config.databaseFile)
    val accounts = Accounts(database)
    val records = SyncStore(database)
    val blobs = BlobStore(database, config.blobDirectory)

    monitor.subscribe(io.ktor.server.application.ApplicationStopped) { database.close() }

    install(ContentNegotiation) {
        json(Json { ignoreUnknownKeys = true; encodeDefaults = true })
    }

    install(CallLogging) {
        level = Level.INFO
        // Method, path and status, and nothing else. The default line would be harmless
        // today, but writing it out here is what keeps a later "just log the body while we
        // debug this" from being a one-line change nobody reviews.
        format { call ->
            "${call.response.status()?.value ?: "-"} ${call.request.httpMethod.value} ${call.request.path()}"
        }
    }

    install(RateLimit) {
        register(RateLimitName("auth")) {
            // Slow enough that guessing a password over the network is hopeless, generous
            // enough that a person mistyping theirs three times never notices.
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { call -> call.request.local.remoteAddress }
        }
    }

    install(Authentication) {
        bearer("session") {
            authenticate { credential -> accounts.authenticate(credential.token) }
        }
    }

    install(StatusPages) {
        exception<Throwable> { call, cause ->
            // The message could name a column, a path, or a fragment of what failed to
            // parse. The client gets none of that; the log keeps it.
            call.application.log.error("unhandled failure on ${call.request.path()}", cause)
            call.respond(HttpStatusCode.InternalServerError, ErrorBody("something went wrong"))
        }
    }

    routing {
        animaRoutes(config, accounts, records, blobs)
    }
}
