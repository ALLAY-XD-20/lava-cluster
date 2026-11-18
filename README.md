# Lava-cluster

**One port, many clusters.** Your bots connect to the main Lavalink port only. Lava-cluster splits the players across several clusters, gives **every cluster its own RAM**, and divides the **CPU evenly** between them.

`4` clusters at `512 MB` = **512 x 4 = 2048 MB** total, and each cluster gets **25% of the CPU**.

| | |
| --- | --- |
| Plugin | `lava-cluster` |
| Version | `1.0.2` |
| Author | **Its2Pawan** |
| Lavalink | v4 (`4.0.8`+) |
| Java | 17+ |
| Language | Kotlin |

---

## Features

- **Same port for everything:** bots use `main-host:2333` as always. There are no cluster ports to open or manage.
- **Even CPU split:** 100% / clusters each (5 clusters = 20% each, 4 clusters = 25% each) and all cores in use.
- **RAM per cluster:** every cluster has its own heap (`-Xmx`), set from config or live over REST.
- **Smart placement:** the plugin decides where every new player runs, based on players, CPU and heap of each cluster.
- **Stable:** unhealthy or overloaded clusters are skipped, a frozen cluster is restarted, and stateless calls retry on another cluster.
- **Merged view:** `/v4/stats` and the WebSocket `stats` show the combined numbers of all clusters.
- **Safe:** refuses a setup above a set percent of host RAM.
- **Watchdog:** a crashed or out-of-memory cluster is restarted and re-joined automatically.

---

## Install

### Option A: jar
Put `lava-cluster-1.0.2.jar` in Lavalink's `plugins/` folder, add the config, restart.

### Option B: JitPack
```yaml
lavalink:
  plugins:
    - dependency: "com.github.ALLAY-XD-20:lava-cluster:1.0.2"
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
    cpuSplit: true           # CPU divided evenly between the clusters
    cpuTotalPercent: 100     # 100 = clusters may use all cores
    maxHeapPercent: 85       # new players skip a cluster above this heap %
    maxCpuPercent: 90        # new players skip a cluster above this CPU %
    maxHostRamPercent: 90    # safety limit against the host's total RAM
```

| Option | Default | Meaning |
| --- | --- | --- |
| `enabled` | `true` | Turn the plugin on or off |
| `clusters` | `2` | Number of clusters (1-32) |
| `ramPerClusterMb` | `512` | Heap per cluster in MB (minimum 256) |
| `cpuSplit` | `true` | Divide the CPU evenly between the clusters |
| `cpuTotalPercent` | `100` | How much of the whole CPU the clusters may use together |
| `maxHeapPercent` | `85` | New players skip a cluster whose heap is above this % |
| `maxCpuPercent` | `90` | New players skip a cluster whose CPU load is above this % |
| `javaPath` | `java` | Java binary used for the workers |
| `lavalinkJar` | auto | Path to `Lavalink.jar` (auto-detected if empty) |
| `workDir` | `clusters` | Folder for worker files and logs |
| `childPassword` | main password | Password of the workers |
| `autoStart` | `true` | Start the clusters when Lavalink starts |
| `maxHostRamPercent` | `90` | Refuse to start above this % of host RAM |
| `extraJvmArgs` | none | Extra JVM flags for every worker |

### CPU split examples

| CPU cores | Clusters | Each cluster gets |
| --- | --- | --- |
| 8 | 4 | 2 cores (25%) |
| 10 | 5 | 2 cores (20%) |
| 8 | 3 | 3 + 3 + 2 cores |
| 4 | 8 | 1 core each (shared) |

Set `cpuTotalPercent: 80` to keep 20% of the cores free for the main Lavalink and the system.

On **Linux** each cluster is pinned to its own cores (`taskset`), a hard even split. On other systems only the thread count of each cluster is limited.

### How placement is decided

For every new player the plugin:
1. Drops clusters that are down, not answering, or above `maxHeapPercent` / `maxCpuPercent`.
2. Scores the rest: **players + CPU% / 2 + heap% / 4**.
3. Puts the player on the lowest score.

If every cluster is over a limit, it still picks the best of them, so playback never stops. After placement, a guild stays on its cluster.

### Plan your RAM

The main Lavalink is only the gateway, so start it small, for example `java -Xmx256M -jar Lavalink.jar`.

| Clusters | RAM each | Clusters total | Plus main |
| --- | --- | --- | --- |
| 2 | 512 MB | 1024 MB | ~256 MB |
| 4 | 512 MB | 2048 MB | ~256 MB |
| 4 | 1024 MB | 4096 MB | ~256 MB |

Each worker also uses a little memory outside the heap, so leave spare RAM on the host.

---

## Connect your bot

Nothing changes for your bot:

```
host: your-server-ip
port: 2333            # the MAIN port, the only port
password: youshallnotpass
```

Behind the scenes:
- Only **2333** exists for you. Several processes cannot each own the same port and still keep a player on one process, so the workers sit on hidden local ports (`127.0.0.1`, picked automatically). You never configure, use or open them.
- Your bot's WebSocket is joined to every cluster. The bot sees one session and one `ready`.
- A new player goes to the least-loaded cluster. After that, that guild always uses the same cluster.
- `loadtracks`, `decodetrack`, `info` are spread round-robin over the clusters.

---

## Commands

Send these to the main port with the header `Authorization: <your lavalink password>`.

| Action | Request |
| --- | --- |
| Set clusters and RAM each | `POST /v4/cluster/configure?clusters=4&ramMb=512` |
| Status (RAM, cores, players, health, heap %, CPU %) | `GET /v4/cluster` |
| Restart one cluster | `POST /v4/cluster/restart/{id}` |
| Start all | `POST /v4/cluster/start` |
| Stop all | `POST /v4/cluster/stop` |

```bash
curl -X POST -H "Authorization: youshallnotpass" \
  "http://localhost:2333/v4/cluster/configure?clusters=4&ramMb=512"
```

`configure` restarts the clusters with the new values (and a new CPU split), so players on them are dropped and your bot must recreate them.

---

## How it works

1. A JVM cannot split one heap, so each cluster is its own process: `java -Xmx<ramMb>M -jar Lavalink.jar`, on a hidden local port.
2. Each process is limited to its share of the CPU cores.
3. The main port runs a gateway in front of them: it checks your password, opens one upstream connection per cluster, and routes every call.
4. Stats of every cluster are read every 3 seconds, and placement uses them.
5. A watchdog restarts a cluster that stopped, and one that is running but has not answered for about 30 seconds.
6. At startup the clusters start one after another, each waiting until it answers, so the CPU does not spike.

Worker logs: `clusters/cluster-N/cluster.log`.

---

## Good to know

- Players are spread evenly, so each cluster ends up with a similar load. A cluster can only use its own cores, so an idle cluster does not lend its cores to a busy one.
- If a cluster crashes, the players on it are lost. The bot has to create them again (they land on a healthy cluster).

---

## Troubleshooting

- **"above 90% of host RAM":** lower `clusters` or `ramPerClusterMb`, or raise `maxHostRamPercent` if the host can take it.
- **A cluster keeps restarting:** read its `cluster.log`. If it is out of memory, raise `ramPerClusterMb`.
- **401:** the `Authorization` header must be your main Lavalink password.
- **Bot connects but no players work:** check `GET /v4/cluster`; at least one cluster must be `alive`.
- **CPU not split on Linux:** check that `taskset` exists (`apt install util-linux`).

---

## Build

```bash
gradle wrapper --gradle-version 8.10.2   # first time only
./gradlew build
```

Jar: `build/libs/lava-cluster-1.0.2.jar`. Test with `./gradlew runLavalink` (uses `./application.yml`).

---

## Changelog

See [CHANGELOG.md](CHANGELOG.md).

---

Made by **Its2Pawan**.
