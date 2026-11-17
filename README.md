# Lava-cluster

**Run several Lavalink clusters and choose how much RAM every cluster gets.**

Pick the number of clusters and the RAM for each one. `4` clusters at `512 MB` = **512 x 4 = 2048 MB** in total, with every cluster running in its own isolated worker, so one busy or crashed cluster never drags the others down.

| | |
| --- | --- |
| Plugin | `lava-cluster` |
| Version | `1.0.1` |
| Author | **Its2Pawan** |
| Lavalink | v4 (`4.0.8`+) |
| Java | 17+ |
| Language | Kotlin |

---

## Features

- Set **cluster count** and **RAM per cluster** from `application.yml` or live over REST.
- Every cluster is a separate Lavalink worker with its own heap (`-Xmx`), so memory is truly capped per cluster.
- **RAM safety check:** refuses a setup that would use more than a set percent of the host's RAM.
- **Watchdog:** a crashed or out-of-memory cluster is restarted automatically.
- **Status API** with players, heap usage and CPU load for each cluster.
- **Best-cluster endpoint** that returns the least-loaded cluster for your bot to connect to.
- Workers reuse your main `application.yml` and `plugins/`, with nothing extra to configure.

---

## Install

### Option A: jar
1. Build it (see [Build](#build)) or download the release jar.
2. Put `lava-cluster-1.0.1.jar` in Lavalink's `plugins/` folder.
3. Add the config below and restart Lavalink.

### Option B: JitPack
```yaml
lavalink:
  plugins:
    - dependency: "com.github.ALLAY-XD-20:lava-cluster:1.0.1"
      repository: "https://jitpack.io"
```

---

## Configure

```yaml
plugins:
  lavacluster:
    enabled: true
    clusters: 4              # how many clusters
    ramPerClusterMb: 512     # RAM for EVERY cluster, in MB
    basePort: 2334           # cluster 0 = 2334, cluster 1 = 2335, ...
    maxHostRamPercent: 90    # safety limit against the host's total RAM
```

| Option | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Turn the plugin on or off |
| `clusters` | `2` | Number of clusters (1-32) |
| `ramPerClusterMb` | `512` | Heap per cluster in MB (minimum 256) |
| `basePort` | `2334` | First cluster port; each next cluster uses +1 |
| `javaPath` | `java` | Java binary used for the workers |
| `lavalinkJar` | auto | Path to `Lavalink.jar` (auto-detected if empty) |
| `workDir` | `clusters` | Folder for worker files and logs |
| `childPassword` | main password | Password of the workers |
| `autoStart` | `true` | Start the clusters when Lavalink starts |
| `maxHostRamPercent` | `90` | Refuse to start above this % of host RAM |
| `extraJvmArgs` | none | Extra JVM flags for every worker |

### Choosing RAM

| Clusters | RAM each | Total heap |
| --- | --- | --- |
| 2 | 512 MB | 1024 MB |
| 4 | 512 MB | 2048 MB |
| 4 | 1024 MB | 4096 MB |
| 8 | 512 MB | 4096 MB |

Each worker also needs a little memory outside the heap, so leave spare RAM on the host.

---

## Commands

Send these to your main Lavalink with the header `Authorization: <your lavalink password>`.

| Action | Request |
| --- | --- |
| Set clusters and RAM each | `POST /v4/cluster/configure?clusters=4&ramMb=512` |
| Status | `GET /v4/cluster` |
| Least-loaded cluster | `GET /v4/cluster/best` |
| Restart one cluster | `POST /v4/cluster/restart/{id}` |
| Start all | `POST /v4/cluster/start` |
| Stop all | `POST /v4/cluster/stop` |

**Set 4 clusters with 512 MB each**
```bash
curl -X POST -H "Authorization: youshallnotpass" \
  "http://localhost:2333/v4/cluster/configure?clusters=4&ramMb=512"
```

**Status response (shortened)**
```json
{
  "clusters": 4,
  "ramPerClusterMb": 512,
  "totalRamMb": 2048,
  "nodes": [
    { "id": 0, "port": 2334, "alive": true, "players": 12, "playingPlayers": 9 }
  ]
}
```

**Best cluster**
```json
{ "id": 2, "host": "0.0.0.0", "port": 2336 }
```

`configure` stops the running clusters and starts them again with the new values, so players on them reconnect.

---

## How it works

A JVM cannot split one heap into parts, so Lava-cluster gives each cluster its own process:

1. The main Lavalink reads your config.
2. For every cluster it starts a worker: `java -Xmx<ramMb>M -jar Lavalink.jar --server.port=<basePort + N>`.
3. Each worker gets a copy of `application.yml` and the same `plugins/` folder, and is marked so it never starts clusters of its own.
4. A watchdog checks the workers every 10 seconds and restarts any that stopped, with a growing delay.
5. When the main Lavalink stops, all workers stop with it.

Worker logs are in `clusters/cluster-N/cluster.log`.

---

## Troubleshooting

- **"above 90% of host RAM":** lower `clusters` or `ramPerClusterMb`, or raise `maxHostRamPercent` if you know the host can take it.
- **A cluster keeps restarting:** read its `cluster.log`. If it is running out of memory, raise `ramPerClusterMb`.
- **401 from the commands:** the `Authorization` header must be your main Lavalink password.
- **Port already in use:** change `basePort` so the range `basePort ... basePort + clusters - 1` is free.

---

## Build

```bash
gradle wrapper --gradle-version 8.10.2   # first time only
./gradlew build
```

The jar is written to `build/libs/lava-cluster-1.0.1.jar`.
Test locally with `./gradlew runLavalink` (uses `./application.yml`).

---

## Changelog

- **1.0.1:** standalone README, JitPack packaging.
- **1.0.0:** first release: clusters with per-cluster RAM, REST commands, watchdog.

---

Made by **Its2Pawan**.
