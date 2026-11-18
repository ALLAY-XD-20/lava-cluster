package dev.its2pawan.lavacluster

import com.fasterxml.jackson.databind.JsonNode
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

class ClusterNode(val id: Int, val ramMb: Int) {
    /** Hidden local port, picked automatically. Never exposed, bots only use the main port. */
    @Volatile var port: Int = 0
    @Volatile var cpus: String = "all"
    @Volatile var process: Process? = null
    @Volatile var startedAt: Long = 0
    @Volatile var restarts: Int = 0
    @Volatile var stats: JsonNode? = null
    @Volatile var statsAt: Long = 0
    @Volatile var failedPolls: Int = 0
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
    @Value("\${server.port:2333}") private val parentPort: Int,
) {
    private val log = LoggerFactory.getLogger("Lava-cluster")
    private val isChild = System.getenv("LAVACLUSTER_CHILD") == "1"
    private val nodes = ConcurrentHashMap<Int, ClusterNode>()
    private val http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(2)).build()
    private val mapper = ObjectMapper()
    private val watchdog = Executors.newScheduledThreadPool(3) { r -> Thread(r, "lava-cluster-watchdog").apply { isDaemon = true } }

    @Volatile private var clusters = cfg.clusters
    @Volatile private var ramMb = cfg.ramPerClusterMb

    /** Password clients use on the main port. */
    val authPassword: String get() = parentPassword
    /** Password the workers use. */
    val workerPassword: String get() = cfg.childPassword.ifBlank { parentPassword }
    fun node(id: Int): ClusterNode? = nodes[id]
    fun aliveNodes(): List<ClusterNode> = (0 until clusters).mapNotNull { nodes[it] }.filter { it.alive }

    @PostConstruct
    fun init() {
        if (!cfg.enabled || isChild) return
        log.info("Lava-cluster by Its2Pawan loaded")
        if (cfg.autoStart) {
            // staggered, in the background: Lavalink itself boots without waiting for the clusters
            watchdog.execute {
                try { startAll(true) } catch (e: ClusterException) { log.error("Auto-start refused: {}", e.message) }
                catch (e: Exception) { log.error("Auto-start failed", e) }
            }
        }
        watchdog.scheduleWithFixedDelay({ runCatching { heal() } }, 10, 10, TimeUnit.SECONDS)
        watchdog.scheduleWithFixedDelay({ runCatching { refreshStats() } }, 3, 3, TimeUnit.SECONDS)
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

    /** wait = start one cluster at a time and wait until it answers (no CPU spike, smooth boot). */
    @Synchronized
    fun startAll(wait: Boolean = false) {
        validate(clusters, ramMb)
        for (i in 0 until clusters) {
            if (nodes[i]?.alive == true) continue
            startNode(i)
            if (wait) waitReady(i, 45)
        }
    }

    @Synchronized
    fun stopAll() {
        nodes.keys.toList().forEach { stopNode(it) }
    }

    fun status(): Map<String, Any> {
        val list = (0 until clusters).map { i ->
            val n = nodes[i]
            val stats = if (n?.alive == true) n.stats else null
            mapOf(
                "id" to i,
                "ramMb" to ramMb,
                "cpus" to (n?.cpus ?: "-"),
                "healthy" to (n != null && isHealthy(n)),
                "heapPercent" to (if (n != null) Math.round(heapPercent(n)) else 0L),
                "cpuPercent" to (if (n != null) Math.round(cpuPercent(n)) else 0L),
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
            "mainPort" to parentPort,
            "cpuSplit" to cfg.cpuSplit,
            "cpuCores" to Runtime.getRuntime().availableProcessors(),
            "clusters" to clusters,
            "ramPerClusterMb" to ramMb,
            "totalRamMb" to clusters * ramMb,
            "hostRamMb" to hostRamMb(),
            "nodes" to list,
        )
    }

    /** Where bots should connect. With the gateway on that is always the main port. */
    fun best(): Map<String, Any> = mapOf("host" to bindAddress, "port" to parentPort)

    /** Alive, answering stats, and the data is fresh. */
    fun isHealthy(n: ClusterNode): Boolean =
        n.alive && n.stats != null && System.currentTimeMillis() - n.statsAt < 20_000

    fun heapPercent(n: ClusterNode): Double {
        val m = n.stats?.path("memory") ?: return 0.0
        val max = m.path("reservable").asLong(0)
        return if (max <= 0) 0.0 else m.path("used").asLong(0) * 100.0 / max
    }

    fun cpuPercent(n: ClusterNode): Double = (n.stats?.path("cpu")?.path("lavalinkLoad")?.asDouble(0.0) ?: 0.0) * 100.0

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
        val port = freePort()
        val dir = File(cfg.workDir, "cluster-$id").apply { mkdirs() }
        prepareDir(dir)

        val cores = coreSlice(id)
        val cmd = mutableListOf<String>()
        if (cores.isNotEmpty() && canPin()) cmd += listOf("taskset", "-c", cores.joinToString(","))
        cmd += listOf(
            cfg.javaPath,
            "-Xms${ramMb / 2}M", "-Xmx${ramMb}M",
            "-XX:+UseG1GC", "-XX:+ExitOnOutOfMemoryError",
        )
        if (cores.isNotEmpty()) cmd += "-XX:ActiveProcessorCount=${cores.size}"
        cmd += cfg.extraJvmArgs
        cmd += listOf(
            "-jar", resolveJar(),
            "--server.port=$port",
            "--server.address=127.0.0.1",
            "--lavalink.server.password=$workerPassword",
        )

        val pb = ProcessBuilder(cmd)
            .directory(dir)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(File(dir, "cluster.log")))
        pb.environment()["LAVACLUSTER_CHILD"] = "1" // children never spawn more clusters

        val node = nodes.getOrPut(id) { ClusterNode(id, ramMb) }
        node.stats = null
        node.statsAt = 0
        node.failedPolls = 0
        node.port = port
        node.cpus = if (cores.isEmpty()) "all" else cores.joinToString(",")
        node.process = pb.start()
        node.startedAt = System.currentTimeMillis()
        log.info("Cluster {} started with {}MB, cpu cores [{}] (pid {})", id, ramMb, node.cpus, node.process!!.pid())
    }

    /** Free local port for a hidden worker. */
    private fun freePort(): Int =
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { it.localPort }

    /**
     * Even CPU split: usable cores are shared between the clusters.
     * 8 cores / 4 clusters = 2 cores (25%) each; 5 clusters on 5 cores = 20% each.
     */
    private fun coreSlice(id: Int): List<Int> {
        if (!cfg.cpuSplit) return emptyList()
        val total = Runtime.getRuntime().availableProcessors()
        val usable = (total * cfg.cpuTotalPercent.coerceIn(1, 100) / 100).coerceAtLeast(1)
        val n = clusters
        if (n >= usable) return listOf(id % usable) // more clusters than cores: one core each (shared)
        val base = usable / n
        val rem = usable % n
        val start = id * base + minOf(id, rem)
        val size = base + if (id < rem) 1 else 0
        return (start until start + size).toList()
    }

    private fun canPin(): Boolean =
        System.getProperty("os.name", "").lowercase().contains("linux") &&
            listOf("/usr/bin/taskset", "/bin/taskset").any { File(it).exists() }

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
            if (n != null && n.alive && n.failedPolls >= 10) {
                log.warn("Cluster {} is running but not answering, restarting it", i)
                stopNode(i)
                startNode(i)
                continue
            }
            if (n != null && n.alive) { if (System.currentTimeMillis() - n.startedAt > 120_000) n.restarts = 0; continue }
            val wait = 5_000L * ((n?.restarts ?: 0) + 1).coerceAtMost(6)
            if (n != null && System.currentTimeMillis() - n.startedAt < wait) continue
            val restarts = (n?.restarts ?: 0) + 1
            log.warn("Cluster {} is down, restarting (attempt {})", i, restarts)
            startNode(i)
            nodes[i]?.restarts = restarts
        }
    }

    private fun refreshStats() {
        val now = System.currentTimeMillis()
        nodes.values.filter { it.alive }.forEach { n ->
            val s = fetchStats(n.port)
            if (s != null) { n.stats = s; n.statsAt = now; n.failedPolls = 0 }
            else if (now - n.startedAt > 60_000) n.failedPolls++
        }
    }

    private fun waitReady(id: Int, seconds: Int) {
        val n = nodes[id] ?: return
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end && n.alive) {
            val s = fetchStats(n.port)
            if (s != null) { n.stats = s; n.statsAt = System.currentTimeMillis(); return }
            Thread.sleep(1000)
        }
    }

    private fun fetchStats(port: Int): JsonNode? = runCatching {
        val req = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/v4/stats"))
            .header("Authorization", workerPassword).timeout(Duration.ofSeconds(2)).GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() == 200) mapper.readTree(res.body()) else null
    }.getOrNull()
}
