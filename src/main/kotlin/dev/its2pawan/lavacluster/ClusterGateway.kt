package dev.its2pawan.lavacluster

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** One bot session as the bot sees it. Behind it: one real Lavalink session per cluster. */
class VSession(val id: String, val userId: String, val clientName: String) {
    val real = ConcurrentHashMap<Int, String>()      // clusterId -> real session id
    val guilds = ConcurrentHashMap<String, Int>()    // guildId   -> clusterId that owns the player
    @Volatile var wsOpen = true
    @Volatile var lastSeen = System.currentTimeMillis()
}

class Link(val client: WebSocketSession, val vs: VSession, val resuming: Boolean) {
    val upstreams = ConcurrentHashMap<Int, WebSocket>()
    val stats = ConcurrentHashMap<Int, JsonNode>()
    val pending: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    val readySent = AtomicBoolean(false)
    @Volatile var open = true
}

private class Target(val node: ClusterNode, val realId: String)

/**
 * Lava-cluster gateway: bots talk to the MAIN port only. Every player is placed on the
 * least-loaded cluster, so CPU and RAM are divided between the clusters.
 */
@Service
class ClusterGateway(private val cfg: ClusterConfig, private val manager: ClusterManager) {
    private val log = LoggerFactory.getLogger("Lava-cluster-gateway")
    private val mapper = ObjectMapper()
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build()
    private val sessions = ConcurrentHashMap<String, VSession>()
    private val links = ConcurrentHashMap<String, Link>()
    private val rr = AtomicInteger()
    private val scheduler = Executors.newScheduledThreadPool(2) { r -> Thread(r, "lava-cluster-gateway").apply { isDaemon = true } }

    val active: Boolean get() = cfg.enabled && System.getenv("LAVACLUSTER_CHILD") != "1"

    @PostConstruct
    fun init() {
        if (!active) return
        scheduler.scheduleWithFixedDelay({ runCatching { sync() } }, 3, 3, TimeUnit.SECONDS)
        scheduler.scheduleWithFixedDelay({ runCatching { purge() } }, 60, 60, TimeUnit.SECONDS)
        log.info("Gateway active: connect bots to the main port only")
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdownNow()
        links.values.forEach { l -> l.upstreams.values.forEach { runCatching { it.abort() } } }
    }

    fun authorized(header: String?): Boolean =
        MessageDigest.isEqual((header ?: "").toByteArray(), manager.authPassword.toByteArray())

    // ======================= REST =======================

    fun handleRest(req: HttpServletRequest, res: HttpServletResponse) {
        val path = req.requestURI
        try {
            if (!authorized(req.getHeader("Authorization"))) return fail(res, 401, "Unauthorized", path)
            val body = req.inputStream.readAllBytes()
            val seg = path.removePrefix("/v4/").trim('/').split("/")
            when {
                seg[0] == "stats" && req.method == "GET" ->
                    writeJson(res, 200, buildStats(manager.aliveNodes().mapNotNull { it.stats }, false))
                seg[0] == "sessions" && seg.size >= 2 -> routeSession(req, res, seg, body)
                else -> {
                    // stateless call: healthy clusters in turn, retry once on the next one if it fails
                    val order = rotation()
                    if (order.isEmpty()) return fail(res, 503, "No cluster is running", path)
                    var last: Exception? = null
                    for (n in order.take(2)) {
                        try {
                            relay(res, call(n, req.method, path, req.queryString, body, req))
                            return
                        } catch (e: java.io.IOException) { last = e }
                    }
                    throw last!!
                }
            }
        } catch (e: Exception) {
            log.warn("Gateway REST error on {}: {}", path, e.toString())
            if (!res.isCommitted) fail(res, 502, "Cluster gateway error: ${e.message}", path)
        }
    }

    private fun routeSession(req: HttpServletRequest, res: HttpServletResponse, seg: List<String>, body: ByteArray) {
        val path = req.requestURI
        val method = req.method
        val vs = sessions[seg[1]] ?: return fail(res, 404, "Session not found", path)
        val targets = vs.real.mapNotNull { (cid, rid) -> manager.node(cid)?.takeIf { it.alive }?.let { Target(it, rid) } }
        if (targets.isEmpty()) return fail(res, 503, "No cluster is running", path)

        when {
            // PATCH /v4/sessions/{id}  (resuming config) -> every cluster
            seg.size == 2 -> {
                val rs = targets.map { call(it.node, method, "/v4/sessions/${it.realId}", req.queryString, body, req) }
                relay(res, rs.first())
            }
            // GET /v4/sessions/{id}/players -> merge all clusters
            seg.size == 3 && seg[2] == "players" -> {
                val merged = mapper.createArrayNode()
                targets.forEach { t ->
                    val r = call(t.node, "GET", "/v4/sessions/${t.realId}/players", null, ByteArray(0), req)
                    if (r.statusCode() == 200) mapper.readTree(r.body()).forEach { merged.add(it) }
                }
                writeJson(res, 200, merged)
            }
            // one player -> the cluster that owns it (new players go to the least-loaded cluster)
            seg.size == 4 && seg[2] == "players" -> {
                val guildId = seg[3]
                var target = vs.guilds[guildId]?.let { cid -> targets.firstOrNull { it.node.id == cid } }
                if (target == null && method == "PATCH") {
                    target = pickByLoad(targets)!!
                    vs.guilds[guildId] = target.node.id
                }
                if (target == null) target = targets.first()
                val r = call(target.node, method, "/v4/sessions/${target.realId}/players/$guildId", req.queryString, body, req)
                if (method == "DELETE" && r.statusCode() in 200..299) vs.guilds.remove(guildId)
                relay(res, r)
            }
            else -> {
                val t = targets.first()
                relay(res, call(t.node, method, "/v4/sessions/${t.realId}/${seg.drop(2).joinToString("/")}", req.queryString, body, req))
            }
        }
    }

    private fun call(node: ClusterNode, method: String, path: String, query: String?, body: ByteArray, req: HttpServletRequest?): HttpResponse<ByteArray> {
        val uri = URI("http://127.0.0.1:${node.port}$path" + (if (query.isNullOrEmpty()) "" else "?$query"))
        val b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Authorization", manager.workerPassword)
        req?.getHeader("Content-Type")?.let { b.header("Content-Type", it) }
        req?.getHeader("Accept")?.let { b.header("Accept", it) }
        b.method(method, if (body.isEmpty()) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofByteArray(body))
        return http.send(b.build(), HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun relay(res: HttpServletResponse, r: HttpResponse<ByteArray>) {
        res.status = r.statusCode()
        r.headers().firstValue("Content-Type").ifPresent { res.contentType = it }
        if (r.body().isNotEmpty()) res.outputStream.write(r.body())
    }

    private fun writeJson(res: HttpServletResponse, code: Int, node: JsonNode) {
        res.status = code
        res.contentType = "application/json"
        res.outputStream.write(mapper.writeValueAsBytes(node))
    }

    private fun fail(res: HttpServletResponse, code: Int, msg: String, path: String) {
        val o = mapper.createObjectNode()
        o.put("timestamp", System.currentTimeMillis())
        o.put("status", code)
        o.put("error", when (code) { 401 -> "Unauthorized"; 404 -> "Not Found"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"; else -> "Error" })
        o.put("message", msg)
        o.put("path", path)
        writeJson(res, code, o)
    }

    // ======================= load balancing =======================

    /** Healthy clusters first, rotated so stateless calls are spread evenly. */
    private fun rotation(): List<ClusterNode> {
        val alive = manager.aliveNodes()
        val pool = alive.filter { manager.isHealthy(it) }.ifEmpty { alive }
        if (pool.isEmpty()) return emptyList()
        val start = Math.floorMod(rr.getAndIncrement(), pool.size)
        return pool.drop(start) + pool.take(start)
    }

    /**
     * The gateway decides where a new player runs:
     * skip clusters that are unhealthy or over the heap/CPU limit, then take the lowest
     * score = players + cpu%/2 + heap%/4.
     */
    private fun pickByLoad(targets: List<Target>): Target? {
        if (targets.isEmpty()) return null
        val counts = HashMap<Int, Int>()
        sessions.values.filter { it.wsOpen }.forEach { s -> s.guilds.values.forEach { counts[it] = (counts[it] ?: 0) + 1 } }
        val healthy = targets.filter { manager.isHealthy(it.node) }
        val roomy = healthy.filter {
            manager.heapPercent(it.node) < cfg.maxHeapPercent && manager.cpuPercent(it.node) < cfg.maxCpuPercent
        }
        val pool = roomy.ifEmpty { healthy }.ifEmpty { targets }
        return pool.minByOrNull { (counts[it.node.id] ?: 0) + manager.cpuPercent(it.node) / 2 + manager.heapPercent(it.node) / 4 }
    }

    fun buildStats(list: List<JsonNode>, withOp: Boolean): ObjectNode {
        val o = mapper.createObjectNode()
        if (withOp) o.put("op", "stats")
        o.put("players", list.sumOf { it.path("players").asInt(0) })
        o.put("playingPlayers", list.sumOf { it.path("playingPlayers").asInt(0) })
        o.put("uptime", list.maxOfOrNull { it.path("uptime").asLong(0) } ?: 0L)
        val mem = o.putObject("memory")
        for (k in listOf("free", "used", "allocated", "reservable")) mem.put(k, list.sumOf { it.path("memory").path(k).asLong(0) })
        val cpu = o.putObject("cpu")
        cpu.put("cores", list.firstOrNull()?.path("cpu")?.path("cores")?.asInt(0) ?: 0)
        cpu.put("systemLoad", if (list.isEmpty()) 0.0 else list.map { it.path("cpu").path("systemLoad").asDouble(0.0) }.average())
        cpu.put("lavalinkLoad", minOf(1.0, list.sumOf { it.path("cpu").path("lavalinkLoad").asDouble(0.0) }))
        val fs = list.mapNotNull { it.get("frameStats")?.takeIf { f -> !f.isNull } }
        if (fs.isNotEmpty()) {
            val f = o.putObject("frameStats")
            for (k in listOf("sent", "nulled", "deficit")) f.put(k, fs.sumOf { it.path(k).asInt(0) })
        }
        return o
    }

    // ======================= WebSocket =======================

    fun attach(raw: WebSocketSession) {
        val h = raw.handshakeHeaders
        val userId = h.getFirst("User-Id") ?: return raw.close(CloseStatus.BAD_DATA)
        val clientName = h.getFirst("Client-Name") ?: "unknown"
        val resumeId = h.getFirst("Session-Id")
        val client = ConcurrentWebSocketSessionDecorator(raw, 10_000, 1 shl 20)

        val existing = resumeId?.let { sessions[it] }
        val vs = existing ?: VSession(UUID.randomUUID().toString().replace("-", "").take(16), userId, clientName)
            .also { sessions[it.id] = it }
        vs.wsOpen = true
        val link = Link(client, vs, existing != null)
        links[raw.id] = link

        val alive = manager.aliveNodes()
        if (alive.isEmpty()) { client.close(CloseStatus(1013, "No cluster is running")); return }
        alive.forEach { if (link.pending.add(it.id)) connectUpstream(link, it, link.resuming) }
    }

    fun fromClient(id: String, text: String) {
        links[id]?.upstreams?.values?.forEach { runCatching { it.sendText(text, true) } }
    }

    fun detach(id: String) {
        val link = links.remove(id) ?: return
        link.open = false
        link.vs.wsOpen = false
        link.vs.lastSeen = System.currentTimeMillis()
        link.upstreams.values.forEach { runCatching { it.sendClose(WebSocket.NORMAL_CLOSURE, "") } }
        link.upstreams.clear()
    }

    private fun connectUpstream(link: Link, node: ClusterNode, resume: Boolean) {
        val vs = link.vs
        val b = http.newWebSocketBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .header("Authorization", manager.workerPassword)
            .header("User-Id", vs.userId)
            .header("Client-Name", vs.clientName)
        if (resume) vs.real[node.id]?.let { b.header("Session-Id", it) }
        b.buildAsync(URI("ws://127.0.0.1:${node.port}/v4/websocket"), Upstream(link, node.id))
            .whenComplete { ws, err ->
                link.pending.remove(node.id)
                if (err != null || ws == null) log.debug("Upstream to cluster {} failed: {}", node.id, err?.toString())
                else if (!link.open) ws.sendClose(WebSocket.NORMAL_CLOSURE, "")
                else link.upstreams[node.id] = ws
            }
    }

    /** Keeps every open bot connected to every running cluster (also after crashes/restarts). */
    private fun sync() {
        val alive = manager.aliveNodes()
        links.values.filter { it.open }.forEach { l ->
            alive.forEach { n ->
                if (!l.upstreams.containsKey(n.id) && l.pending.add(n.id)) connectUpstream(l, n, false)
            }
        }
    }

    private fun purge() {
        val now = System.currentTimeMillis()
        sessions.values.removeIf { !it.wsOpen && now - it.lastSeen > 600_000 }
    }

    private fun toClient(link: Link, text: String) {
        try { link.client.sendMessage(TextMessage(text)) } catch (_: Exception) { }
    }

    private fun onUpstreamMessage(link: Link, clusterId: Int, msg: String) {
        val node = try { mapper.readTree(msg) } catch (_: Exception) { return toClient(link, msg) }
        when (node.path("op").asText()) {
            "ready" -> {
                link.vs.real[clusterId] = node.path("sessionId").asText()
                if (link.readySent.compareAndSet(false, true)) {
                    val o = mapper.createObjectNode()
                    o.put("op", "ready")
                    o.put("resumed", node.path("resumed").asBoolean(false))
                    o.put("sessionId", link.vs.id)
                    toClient(link, o.toString())
                }
            }
            "stats" -> {
                link.stats[clusterId] = node
                toClient(link, buildStats(link.stats.values.toList(), true).toString())
            }
            else -> toClient(link, msg) // playerUpdate / event: carry guildId, pass through
        }
    }

    private fun onUpstreamClosed(link: Link, clusterId: Int) {
        link.upstreams.remove(clusterId)
        link.stats.remove(clusterId)
        // unexpected close while the bot is connected: those players are gone, forget the assignment
        if (link.open) link.vs.guilds.entries.removeIf { it.value == clusterId }
    }

    private inner class Upstream(val link: Link, val clusterId: Int) : WebSocket.Listener {
        private val buf = StringBuilder()

        override fun onOpen(webSocket: WebSocket) { webSocket.request(1) }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            buf.append(data)
            if (last) {
                val msg = buf.toString()
                buf.setLength(0)
                onUpstreamMessage(link, clusterId, msg)
            }
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            onUpstreamClosed(link, clusterId)
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            onUpstreamClosed(link, clusterId)
        }
    }
}
