package dev.its2pawan.lavacluster

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

/** Lava-cluster by Its2Pawan — REST "commands" (protected by the Lavalink password). */
@RestController
@RequestMapping("/v4/cluster")
class ClusterController(private val manager: ClusterManager) {

    @GetMapping
    fun status() = manager.status()

    /** POST /v4/cluster/configure?clusters=4&ramMb=512  -> 4 clusters x 512 MB */
    @PostMapping("/configure")
    fun configure(@RequestParam clusters: Int, @RequestParam ramMb: Int): ResponseEntity<Any> = try {
        manager.configure(clusters, ramMb)
        ResponseEntity.ok(manager.status())
    } catch (e: ClusterException) {
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message))
    }

    @PostMapping("/restart/{id}")
    fun restart(@PathVariable id: Int): ResponseEntity<Any> = try {
        manager.restart(id)
        ResponseEntity.ok(manager.status())
    } catch (e: ClusterException) {
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message))
    }

    @PostMapping("/stop")
    fun stop(): Map<String, Any> { manager.stopAll(); return manager.status() }

    @PostMapping("/start")
    fun start(): ResponseEntity<Any> = try {
        manager.startAll(); ResponseEntity.ok(manager.status())
    } catch (e: ClusterException) {
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to e.message))
    }

    /** The one address bots use (the main port). */
    @GetMapping("/best")
    fun best(): Map<String, Any> = manager.best()
}
