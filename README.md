# fleet-telemetry

A working vehicle telemetry system built to explore how fleet software behaves when the network isn't there.

A GPS board feeds a Java agent running on a Raspberry Pi in my car. The agent buffers every reading to local SQLite, publishes over MQTT when it can reach the broker, and replays what it missed when connectivity returns. A subscriber on the receiving end deduplicates on sequence number.

The outages aren't simulated. The car leaves WiFi range, the buffer fills, and the queue drains on return.

---

## Why

Most telemetry examples assume the network is up. The interesting engineering starts when it isn't — a vehicle drives into a parking garage, a tunnel, or simply out of range, and the question becomes what happens to the data in between.

I'd previously built and shipped a telemetry application on a Cradlepoint router that was fire-and-forget: if a send failed, it was logged and the reading was lost. That was an acceptable tradeoff for a handful of vehicles, but it wouldn't be for a real fleet. This project is the version that handles the gap.

---

## Architecture

```
VEHICLE (Raspberry Pi 4B, systemd)          HOME / "CLOUD" (Docker)
┌──────────────────────────────┐            ┌────────────────────────────┐
│  CubeCell HTCC-AB02S         │            │  yard-agent                │
│  (AIR530Z GPS) ── USB serial │            │  (Docker container)        │
│           │                  │            │                            │
│           ▼                  │            │           │                │
│      SerialReader            │            │           ▼                │
│           │                  │  MQTT      │      mosquitto:1883        │
│           ▼                  │ ────────>  │           │                │
│      LocalBuffer (SQLite)    │  when in   │           ▼                │
│           │                  │   range    │    cloud-subscriber        │
│           ▼                  │            │    (dedupe by seq)         │
│      MqttAgent               │            │                            │
└──────────────────────────────┘            └────────────────────────────┘
      DEVICE_ID=vehicle1                         
```

**One jar, two deployments.** The same build runs bare under systemd on hardware in a moving vehicle and inside a container on a stationary node. Identity and behavior come entirely from environment variables. A real fleet has both intermittent and always-connected nodes, and there's no good reason for them to run different code.

---

## Design decisions

### Store-and-forward

Every reading is written to SQLite **before** any publish attempt, not after a failure. The buffer is the source of truth; MQTT is a drain on it. This means an unexpected power loss — which in a vehicle means the ignition cutting mid-write — costs at most the reading in flight rather than everything since the last successful send.

Rows carry a `sent` flag rather than being deleted on publish, so the send path and the cleanup path are independent and a crash between them isn't a data-loss window.

### QoS per message type

| Topic | QoS | Reasoning |
|---|---|---|
| `position` | 0 | A dropped position is superseded a second later. Paying for delivery guarantees on data with a one-second shelf life is the wrong trade. |
| `boarding` | 1 | Every event is a distinct fact that can't be re-derived. At-least-once with server-side dedupe. |
| `status` (LWT) | 1, retained | Presence must survive the subscriber connecting late, so it's retained. |

### Last Will and Testament

The agent registers an LWT at connect. If it disconnects ungracefully — software crash, power loss, keepalive timeout — the broker publishes the offline status on its behalf. Paired with a birth message on connect, presence is readable from a single retained topic without anyone polling.

### Deduplication on sequence, not timestamp

Each event carries a monotonic sequence number scoped to the device. The subscriber upserts on `(device_id, seq)`.

Timestamps were the wrong key here: the Pi has no RTC, so its clock is unreliable until NTP settles — which requires the network that may not be there. Sequence numbers are generated locally and don't depend on knowing what time it is.

### Event time vs. ingestion time

`device_uptime_ms` records when the reading was taken, relative to a monotonic clock that doesn't jump when NTP corrects. `receivedAtEpochMs` records arrival. After a twenty-minute outage those differ by twenty minutes, and conflating them would place replayed data in the wrong place on the timeline.

### Serial reads

`jSerialComm`'s `TIMEOUT_READ_SEMI_BLOCKING` with `(0, 0)` parameters blocks `readLine()` indefinitely rather than returning — documented behavior, but it presents as a silent hang with no error and no log output. The reader now does raw byte reads with an explicit 2-second timeout and a 15-second stall threshold that triggers a port reopen, so a wedged USB device recovers on its own instead of taking the agent down quietly.

---

## Message schema

```json
{"type":"position","device_uptime_ms":123456,"lat":43.101,"lon":-77.876,"speed_kmh":12.3}
```

---

## Configuration

All configuration is environment-driven — nothing about identity or endpoints is compiled in. See `.env.example`.

| Variable | Purpose |
|---|---|
| `SERIAL_PORT` | Device node for the GPS board (`/dev/ttyUSB0` or `/dev/ttyACM0` — verify, it depends on the USB-serial chip) |
| `MQTT_BROKER_URL` | Broker address |
| `DEVICE_ID` | Identity for topics and dedupe scope |
| `BUFFER_DB_PATH` | SQLite buffer location |

---

## Build and run

**Build** (Java 21, Maven — bytecode is architecture-independent, so building on a workstation and shipping the jar to an ARM device is fine):

```bash
mvn package
```

**Deploy to the vehicle node:**

```bash
scp target/fleet-agent-1.0-jar-with-dependencies.jar user@host:/tmp/
```

Then on the device, place it at `/opt/fleet-agent/fleet-agent.jar` and install the systemd unit in `deploy/fleet-agent.service`.Update permissions as needed

**Broker and subscriber:**

```bash
docker compose up -d
```

---

## Hardware

| Component | Notes |
|---|---|
| Heltec HTCC-AB02S CubeCell | Onboard AIR530Z GPS, built-in OLED, USB serial |
| Raspberry Pi 4B (2GB) | Raspberry Pi OS Lite 64-bit, AArch64 so mainstream Temurin runs unmodified |
| Docker host | Mosquitto and subscriber |

**Vehicle installation notes:** wire to a switched/accessory circuit rather than always-hot to avoid parasitic drain. Secure the hardware — loose electronics are a projectile risk in a hard stop. Expect no GPS fix for the first minute or two of a trip; that's cold start, not a fault.

---

## Known limitations

Honest list of what this doesn't do yet:

- **Buffer eviction is not implemented.** The buffer is bounded in principle but the policy isn't written. Drop-oldest is the right default here — recency matters more than completeness for position data — but it's not there yet.
- **No TLS.** The broker runs plaintext on a trusted LAN. Any real deployment needs TLS with per-device certificates, not anonymous access.
- **No authentication.** Same reason. `allow_anonymous true` is fine for a LAN demo and unacceptable anywhere else.
- **No backoff on reconnect.** Paho's default reconnect is used; there's no jitter, so a fleet recovering from a shared outage would retry in lockstep.
- **Single subscriber, no persistence layer.** The subscriber dedupes in memory rather than writing to a database, so restarting it loses dedupe state.

---

## What I'd do next

Bounded buffer with an explicit eviction policy, TLS with per-device certificates, reconnect backoff with jitter, and persisting dedupe state so subscriber restarts don't reopen the duplicate window.
Finish building a 'boarding' counter with a proximity sensor or a ultra sonic distance sensor to get passanger counts as I walk by it. 