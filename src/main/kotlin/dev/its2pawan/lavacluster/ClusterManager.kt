package dev.its2pawan.lavacluster

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.io.File
import java.lang.management.ManagementFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ClusterNode(val id: Int, val port: Int, val ramMb: Int) {
    @Volatile var process: Process? = null
    @Volatile var startedAt: Long = 0
    @Volatile var restarts: Int = 0
    val alive: Boolean get() = process?.isAlive == true
}

class ClusterException(message: String) : RuntimeException(message)

/**
 * Lava-cluster by Its2Pawan.
 * A JVM can't split its heap per "cluster", so each cluster is its own
 * Lavalink worker process started with -Xmx<ramPerCluster>M.
 */
@Service
class ClusterManager(
    private val cfg: ClusterConfig,
    @Value("\${lavalink.server.password:youshallnotpass}") private val parentPassword: String,
    @Value("\${server.address:0.0.0.0}") private val bindAddress: String,
) {
    private val log = LoggerFactory.getLogger("Lava-cluster")
    private val isChild = System.getenv("LAVACLUSTER_CHILD") == "1"
    private val nodes = ConcurrentHashMap<Int, ClusterNode>()
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val mapper = ObjectMapper()
    private val watchdog = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "lava-cluster-watchdog").apply { isDaemon = true } }

    @Volatile private var clusters = cfg.clusters
    @Volatile private var ramMb = cfg.ramPerClusterMb

    private val password get() = cfg.childPassword.ifBlank { parentPassword }

    @PostConstruct
    fun init() {
        if (!cfg.enabled || isChild) return
        log.info("Lava-cluster by Its2Pawan loaded")
        if (cfg.autoStart) {
            try { startAll() } catch (e: ClusterException) { log.error("Auto-start refused: {}", e.message) }
        }
        watchdog.scheduleWithFixedDelay({ runCatching { heal() } }, 10, 10, TimeUnit.SECONDS)
    }

    @PreDestroy
    fun shutdown() {
        watchdog.shutdownNow()
        if (!isChild) stopAll()
    }

    // ---------- public API ----------

    /** The "command": set cluster count + RAM per cluster, then restart workers. */
    @Synchronized
    fun configure(newClusters: Int, newRamMb: Int) {
        validate(newClusters, newRamMb)
        stopAll()
        clusters = newClusters
        ramMb = newRamMb
        startAll()
    }

    @Synchronized
    fun restart(id: Int) {
        if (id !in 0 until clusters) throw ClusterException("Cluster $id does not exist")
        stopNode(id)
        startNode(id)
    }

    @Synchronized
    fun startAll() {
        validate(clusters, ramMb)
        for (i in 0 until clusters) if (nodes[i]?.alive != true) startNode(i)
    }

    @Synchronized
    fun stopAll() {
        nodes.keys.toList().forEach { stopNode(it) }
    }

    fun status(): Map<String, Any> {
        val list = (0 until clusters).map { i ->
            val n = nodes[i]
            val stats = if (n?.alive == true) fetchStats(n.port) else null
            mapOf(
                "id" to i,
                "port" to (cfg.basePort + i),
                "ramMb" to ramMb,
                "alive" to (n?.alive == true),
                "pid" to n?.process?.pid(),
                "uptimeMs" to if (n?.alive == true) System.currentTimeMillis() - n.startedAt else 0,
                "restarts" to (n?.restarts ?: 0),
                "players" to stats?.path("players")?.asInt(0),
                "playingPlayers" to stats?.path("playingPlayers")?.asInt(0),
                "heapUsedBytes" to stats?.path("memory")?.path("used")?.asLong(0),
                "cpuLoad" to stats?.path("cpu")?.path("lavalinkLoad")?.asDouble(0.0),
            )
        }
        return mapOf(
            "clusters" to clusters,
            "ramPerClusterMb" to ramMb,
            "totalRamMb" to clusters * ramMb,
            "hostRamMb" to hostRamMb(),
            "nodes" to list,
        )
    }

    /** Least-loaded alive cluster (fewest playing players). */
    fun best(): Map<String, Any>? {
        val alive = (0 until clusters).mapNotNull { nodes[it] }.filter { it.alive }
        val pick = alive.minByOrNull { fetchStats(it.port)?.path("playingPlayers")?.asInt(Int.MAX_VALUE) ?: Int.MAX_VALUE }
            ?: return null
        return mapOf("id" to pick.id, "host" to bindAddress, "port" to pick.port)
    }

    // ---------- internals ----------

    private fun validate(c: Int, ram: Int) {
        if (c !in 1..32) throw ClusterException("clusters must be 1-32")
        if (ram < 256) throw ClusterException("ramMb must be at least 256 per cluster")
        val total = c.toLong() * ram
        val limit = hostRamMb() * cfg.maxHostRamPercent / 100
        if (limit > 0 && total > limit) {
            throw ClusterException("$c x ${ram}MB = ${total}MB is above ${cfg.maxHostRamPercent}% of host RAM (${limit}MB allowed)")
        }
    }

    private fun hostRamMb(): Long = runCatching {
        (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).totalMemorySize / 1024 / 1024
    }.getOrDefault(0L)

    private fun resolveJar(): String {
        if (cfg.lavalinkJar.isNotBlank()) return File(cfg.lavalinkJar).absolutePath
        val first = System.getProperty("sun.java.command", "").split(" ").firstOrNull().orEmpty()
        return File(if (first.endsWith(".jar")) first else "Lavalink.jar").absolutePath
    }

    private fun startNode(id: Int) {
        val port = cfg.basePort + id
        val dir = File(cfg.workDir, "cluster-$id").apply { mkdirs() }
        prepareDir(dir)

        val cmd = mutableListOf(
            cfg.javaPath,
            "-Xms${ramMb / 2}M", "-Xmx${ramMb}M",
            "-XX:+UseG1GC", "-XX:+ExitOnOutOfMemoryError",
        )
        cmd += cfg.extraJvmArgs
        cmd += listOf(
            "-jar", resolveJar(),
            "--server.port=$port",
            "--server.address=$bindAddress",
            "--lavalink.server.password=$password",
        )

        val pb = ProcessBuilder(cmd)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir, "cluster.log")))
        pb.environment()["LAVACLUSTER_CHILD"] = "1" // children never spawn more clusters

        val node = nodes.getOrPut(id) { ClusterNode(id, port, ramMb) }
        node.process = pb.start()
        node.startedAt = System.currentTimeMillis()
        log.info("Cluster {} started on port {} with {}MB (pid {})", id, port, ramMb, node.process!!.pid())
    }

    private fun stopNode(id: Int) {
        val node = nodes.remove(id) ?: return
        val p = node.process ?: return
        p.destroy()
        if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
        log.info("Cluster {} stopped", id)
    }

    /** Give each worker the main application.yml and plugins folder. */
    private fun prepareDir(dir: File) {
        val yml = File("application.yml")
        if (yml.exists()) Files.copy(yml.toPath(), File(dir, "application.yml").toPath(), StandardCopyOption.REPLACE_EXISTING)
        val plugins = File("plugins")
        val link = File(dir, "plugins")
        if (plugins.isDirectory && !link.exists()) {
            runCatching { Files.createSymbolicLink(link.toPath(), plugins.absoluteFile.toPath()) }.onFailure {
                link.mkdirs()
                plugins.listFiles { f -> f.extension == "jar" }?.forEach { f ->
                    Files.copy(f.toPath(), File(link, f.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            }
        }
    }

    /** Restart crashed clusters with a growing delay. */
    @Synchronized
    private fun heal() {
        for (i in 0 until clusters) {
            val n = nodes[i]
            if (n != null && n.alive) { if (System.currentTimeMillis() - n.startedAt > 120_000) n.restarts = 0; continue }
            val wait = 5_000L * ((n?.restarts ?: 0) + 1).coerceAtMost(6)
            if (n != null && System.currentTimeMillis() - n.startedAt < wait) continue
            val restarts = (n?.restarts ?: 0) + 1
            log.warn("Cluster {} is down, restarting (attempt {})", i, restarts)
            startNode(i)
            nodes[i]?.restarts = restarts
        }
    }

    private fun fetchStats(port: Int) = runCatching {
        val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v4/stats"))
            .header("Authorization", password).timeout(Duration.ofSeconds(2)).GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 200) mapper.readTree(res.body()) else null
    }.getOrNull()
}
