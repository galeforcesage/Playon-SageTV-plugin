# PlayOn SageTV Plugin

PlayOn Cloud DVR integration for SageTV V9.x and [SageTV-mine](https://github.com/galeforcesage/SageTV-mine) — automatically downloads completed recordings from your PlayOn Cloud account into SageTV's media library with full metadata.

Inspired by [Channels DVR's PlayOn integration](https://getchannels.com/playon/).

## Features

- **PlayOn Cloud Sync** — Polls your PlayOn Cloud account for completed recordings and downloads them as MP4 files
- **Encrypted Credentials** — AES-encrypted password storage (PBKDF2WithHmacSHA256 key derivation)
- **Metadata Mapping** — `.properties` sidecar files map PlayOn fields (Series, Name, ProviderID, season/episode) to SageTV's metadata model
- **File Organization** — `<DownloadDir>/<ServiceName>/<ShowName>/<ShowName_S01E01_EpisodeName>.mp4`
- **Download Resume** — HTTP Range header support for resuming interrupted downloads
- **Download Tracking** — Persistent ID tracking prevents re-downloading the same recording
- **Configurable Polling** — 30 minutes to 24 hours (default: 2 hours, matching Channels DVR)
- **Sequential Downloads** — One download at a time to avoid overloading PlayOn Cloud
- **Cover Art** — Downloads thumbnail images alongside recordings

## Requirements

- SageTV V9.x (Java 8+) or [SageTV-mine](https://github.com/galeforcesage/SageTV-mine) (Java 21)
- PlayOn Cloud account ([playoncloud.com](https://www.playoncloud.com/))

## Installation

1. Download `PlayOnPlugin-1.0.0.jar` from [Releases](https://github.com/galeforcesage/Playon-SageTV-plugin/releases)
2. Copy the JAR to your SageTV `JARs/` directory
3. Restart SageTV
4. Configure via **SageTV Plugin Manager** → PlayOn Cloud Integration

### Docker (SageTV in Docker)

```bash
docker cp PlayOnPlugin-1.0.0.jar <container>:/opt/sagetv/server/JARs/
docker restart <container>
```

## Configuration

All settings are managed through SageTV's Plugin Configuration UI.

| Setting | Default | Description |
|---------|---------|-------------|
| PlayOn Cloud Email | *(empty)* | PlayOn Cloud account email |
| PlayOn Cloud Password | *(encrypted)* | PlayOn Cloud account password (AES encrypted) |
| Sync Interval | `2 hours` | How often to poll for new recordings (30min–24hr) |
| Download Directory | `<SageTV>/PlayOn` | Where recordings are saved (should be a SageTV import path) |
| Remove from Cloud | `false` | Delete recordings from PlayOn Cloud after download |
| Debug Logging | `false` | Enable verbose logging for troubleshooting |

**Plugin UI also shows:** connection status, last sync time/result, total downloaded count, queued recordings, linked services, and account info.

## How It Works

1. You queue recordings via the PlayOn Cloud app/website (Netflix, Hulu, Disney+, HBO Max, etc.)
2. PlayOn Cloud records them as `.mp4` files
3. This plugin authenticates via JWT and polls for completed recordings
4. Downloads are saved to SageTV's import directory with metadata sidecar files
5. SageTV's media scanner picks them up and catalogs them in your library

### PlayOn Cloud API

The plugin uses the PlayOn Cloud REST API (based on the `cs.app.playon` Python package):

- `POST /api/v3/login` — JWT authentication with email/password
- `GET /api/v3/library/available` — List completed recordings
- `GET /api/v3/library/queue` — List queued/in-progress recordings
- `GET /api/v3/library/download/{id}` — Download a recording (with Range header support)
- `POST /api/v3/library/mark-downloaded` — Mark recording as downloaded
- `GET /api/v3/services` — List linked streaming services
- `GET /api/v3/account` — Account info and subscription status
- `GET /api/v3/notifications` — Account notifications

## Building from Source

```bash
git clone https://github.com/galeforcesage/Playon-SageTV-plugin.git
cd Playon-SageTV-plugin
./gradlew build
```

The plugin JAR is output to `build/libs/PlayOnPlugin-1.0.0.jar`.

### Deploy to SageTV

```bash
./gradlew copyToSageTV -PsageTvDir=/opt/sagetv/server
```

## Project Structure

```
src/
├── api/java/sage/                           # SageTV API stubs (compile-only)
│   ├── SageTVPlugin.java
│   ├── SageTVEventListener.java
│   └── SageTVPluginRegistry.java
├── main/java/com/galeforcesage/playon/
│   ├── PlayOnPlugin.java                    # Main SageTVPlugin entry point
│   ├── api/
│   │   ├── PlayOnAuth.java                  # JWT authentication handler
│   │   ├── PlayOnApiClient.java             # PlayOn Cloud REST API client
│   │   └── models/
│   │       ├── PlayOnRecording.java         # Recording model
│   │       ├── PlayOnService.java           # Streaming service model
│   │       └── PlayOnAccount.java           # Account info model
│   ├── sync/
│   │   ├── SyncScheduler.java              # Polling scheduler
│   │   ├── DownloadManager.java            # Sequential download engine
│   │   └── DownloadTracker.java            # Persistent download ID tracking
│   └── sagetv/
│       ├── ConfigManager.java              # Encrypted config management
│       ├── MetadataProcessor.java          # .properties sidecar file writer
│       └── LibraryImporter.java            # Download directory management
└── test/java/com/galeforcesage/playon/      # Unit tests
plugin/
└── PlayOnPlugin.xml                         # SageTV plugin manifest
stvi/
└── PlayOnPlugin.stvi                        # STVI placeholder (future UI)
```

## License

Apache License 2.0
