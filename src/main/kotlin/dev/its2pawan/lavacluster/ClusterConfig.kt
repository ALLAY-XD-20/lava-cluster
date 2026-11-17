package dev.its2pawan.lavacluster

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component

/** application.yml -> plugins.lavacluster.* */
@Component
@ConfigurationProperties(prefix = "plugins.lavacluster")
class ClusterConfig {
    var enabled: Boolean = true
    /** How many clusters (child Lavalink workers) to run. */
    var clusters: Int = 2
    /** RAM (heap, MB) for EVERY cluster. 4 clusters x 512 = 2048 MB total. */
    var ramPerClusterMb: Int = 512
    /** Cluster N listens on basePort + N. */
    var basePort: Int = 2334
    var javaPath: String = "java"
    /** Path to Lavalink.jar. Empty = auto-detect. */
    var lavalinkJar: String = ""
    var workDir: String = "clusters"
    /** Empty = same password as the main Lavalink. */
    var childPassword: String = ""
    var autoStart: Boolean = true
    /** Refuse to start if clusters x RAM is above this % of host RAM. */
    var maxHostRamPercent: Int = 90
    var extraJvmArgs: List<String> = emptyList()
}
