# Changelog

All notable changes to **Lava-cluster** by Its2Pawan.
Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), versions follow [SemVer](https://semver.org/).

## [1.0.2] - 2026-11-18

### Added
- **Smart placement:** the gateway decides where each new player runs. Clusters that are down, not answering, or above the limits are skipped; the rest are scored by `players + CPU% / 2 + heap% / 4` and the lowest wins.
- Config `maxHeapPercent` (default `85`) and `maxCpuPercent` (default `90`) for that check.
- **Health tracking:** every cluster has a `healthy` flag (alive and stats fresh in the last 20 seconds).
- **Frozen-cluster recovery:** a cluster that is running but has not answered for about 30 seconds is restarted by the watchdog.
- **Staggered startup:** clusters start one after another and each is waited for until it answers (up to 45 s), so the CPU does not spike at boot.
- **Retry:** stateless calls (`loadtracks`, `decodetrack`, `info`) retry once on the next cluster if the first one fails.
- `GET /v4/cluster` now also shows `healthy`, `heapPercent` and `cpuPercent` for each cluster.
- `CHANGELOG.md`.

### Changed
- Stats of the clusters are read every 3 seconds (was 5) so placement uses fresher numbers.
- Auto-start runs in the background, so Lavalink itself boots without waiting for the clusters.
- Stateless calls rotate over healthy clusters only (fall back to all running clusters if none is healthy).
- Plugin version is `1.0.2` (`build.gradle.kts`, README, JitPack snippet).

## [1.0.1] - 2026-11-18

### Added
- **One-port gateway:** bots connect to the main Lavalink port only. The gateway joins each bot WebSocket to every cluster (one `ready`, one session) and routes REST calls to the right cluster.
- **Hidden worker ports:** workers listen on automatically picked ports on `127.0.0.1`; nothing else is opened.
- **Even CPU split:** `cpuSplit` and `cpuTotalPercent`. Each cluster gets its share of the cores (4 clusters = 25% each, 5 = 20% each) using `taskset` on Linux and `-XX:ActiveProcessorCount` everywhere.
- Merged `/v4/stats` and WebSocket `stats` across all clusters; merged `GET /v4/sessions/{id}/players`.
- Least-loaded placement for new players; a guild stays on its cluster after that.
- Automatic reconnect of a bot to a cluster that crashed and came back.
- Standalone `README.md`, `jitpack.yml`, sample `application.yml`, `.gitignore`, Gradle wrapper properties (Gradle 8.10.2).

### Changed
- Build files follow the same layout as the Instaxlava repo: Lavalink Gradle plugin `1.1.2`, `apiVersion` / `serverVersion` `4.0.8`, `configurePublishing = true`, group `com.github.ALLAY-XD-20`, archive name `lava-cluster`.
- `GET /v4/cluster/best` returns the main address, since the main port is the only one to use.

### Removed
- `basePort`: workers no longer use fixed ports.
- The earlier `gateway` option: one-port mode is the only mode.

### Fixed
- Removed the duplicate Maven `publishing {}` block that clashed with `configurePublishing = true`.
- Added the missing Spring, WebSocket, Servlet and Jakarta `compileOnly` dependencies.

## [1.0.0] - 2026-11-17

### Added
- First release: run several Lavalink clusters, each in its own worker process with its own RAM (`-Xmx`). Example: 4 clusters x 512 MB = 2048 MB.
- Config `plugins.lavacluster`: `enabled`, `clusters`, `ramPerClusterMb`, `basePort`, `maxHostRamPercent`, `javaPath`, `lavalinkJar`, `workDir`, `childPassword`, `autoStart`, `extraJvmArgs`.
- REST commands: `POST /v4/cluster/configure?clusters=4&ramMb=512`, `GET /v4/cluster`, `GET /v4/cluster/best`, `POST /v4/cluster/restart/{id}`, `POST /v4/cluster/start`, `POST /v4/cluster/stop`.
- RAM safety check against the host's total RAM.
- Watchdog that restarts crashed clusters with a growing delay.
- Worker logs in `clusters/cluster-N/cluster.log`.
