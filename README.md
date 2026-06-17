# jdbc-yugabyte-universe-failover

[![JitPack](https://jitpack.io/v/bchan77/jdbc-yugabyte-universe-failover.svg)](https://jitpack.io/#bchan77/jdbc-yugabyte-universe-failover)

### JDBC `DataSource` failover between YugabyteDB universes for Java

**Repository:** https://github.com/bchan77/jdbc-yugabyte-universe-failover

> **Disclaimer:** This is an independent, community-maintained library. It is **not** affiliated with, endorsed by, or provided by YugabyteDB, Inc. Yugabyte and YugabyteDB are trademarks of YugabyteDB, Inc.

A **framework-neutral** Java library that sits **between your application and the Yugabyte SmartDriver**. It routes JDBC connections to a **primary** or **standby** Yugabyte **universe** and switches when the active universe is **sustained unavailable**.

It does **not** modify SmartDriver. It does **not** replace connection pooling. SmartDriver still handles **nodes inside one universe** (load balance, topology keys, leader changes). This library handles **which universe** your app uses.

---

## Purpose

Yugabyte **SmartDriver** (`jdbc-yugabytedb`) assumes a **single universe**: one master catalog, one set of tablet leaders, one `topology-keys` map. It cannot treat two separate universes (for example EU and US, each with its own six nodes and quorum) as one cluster.

Many deployments still need application traffic to move between universes when:

- A universe loses quorum or becomes unreachable
- Disaster recovery (xCluster DR) requires using the other universe
- Active/active or DR testing requires a controlled JDBC cutover

**This library provides that missing layer:** a `javax.sql.DataSource` that:

1. Keeps a **dedicated pool (or DataSource) per universe**
2. Sends `getConnection()` to the **active** universe only
3. Runs **background health probes** and applies a **failover policy** to switch universes
4. Avoids failing over on **single-node** reboots inside a healthy universe

It is intended for **any Java application** (Spring Boot, plain Java, Jakarta EE, etc.), not only Spring.

---

## What this library does and does not do

| In scope | Out of scope (today) |
|----------|----------------------|
| Route JDBC to primary or standby universe | Modify SmartDriver or `jdbc-yugabytedb` |
| Optional HikariCP pools built from JDBC URIs | Implement its own connection pool |
| JDBC health probes (`SELECT 1`) | Detect Raft quorum directly |
| Pluggable `FailoverPolicy` (default: JDBC thresholds) | YugabyteDB Anywhere (YBA) API integration ([planned](#roadmap-and-future-design)) |
| Manual `switchTo(universeId)` | Run YBA DR failover tasks ([planned](#roadmap-and-future-design)) |
| Metrics holder for observability | Shared metadata table between universes ([not planned near-term](#roadmap-and-future-design)) |

---

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Your application (JdbcTemplate, JPA, raw JDBC, etc.)       │
└─────────────────────────────┬───────────────────────────────┘
                              │ getConnection()
                              ▼
┌─────────────────────────────────────────────────────────────┐
│  UniverseFailoverDataSource   ← this library (routing)      │
│  • active universe: eu | us                                 │
│  • FailoverPolicy + background probes                       │
└──────────────┬──────────────────────────────┬───────────────┘
               │                            │
               ▼                            ▼
┌──────────────────────────┐   ┌──────────────────────────┐
│  HikariCP (EU URI only)   │   │  HikariCP (US URI only)   │
│  SmartDriver              │   │  SmartDriver              │
│  topology-keys: eu…       │   │  topology-keys: us…       │
└──────────────────────────┘   └──────────────────────────┘
        Universe 1                      Universe 2
```

**Single-node failure inside EU:** SmartDriver + Hikari retry on the **EU URL**; universe failover does **not** run.

**EU universe down for a sustained period:** probes fail on EU, US probe succeeds → policy switches active pool to US.

---

## Maven modules

| Module | Artifact | Description |
|--------|----------|-------------|
| `universe-failover-core` | `com.universe.jdbc.failover:universe-failover-core` | `UniverseFailoverDataSource`, `FailoverPolicy` — no Spring, no Hikari |
| `universe-failover-hikari` | `com.universe.jdbc.failover:universe-failover-hikari` | Build pools from JDBC URIs |

### Build from source

```bash
git clone https://github.com/bchan77/jdbc-yugabyte-universe-failover.git
cd jdbc-yugabyte-universe-failover
mvn clean verify
```

---

## Getting the library (for other applications)

This project is **not** on Maven Central yet. Public consumers typically use **[JitPack](https://jitpack.io/#bchan77/jdbc-yugabyte-universe-failover)**, which builds JARs from this GitHub repo.

### Step 1 — Publish a version on GitHub

Create a **git tag** for each release (recommended):

```bash
git tag v0.1.0
git push origin v0.1.0
```

Or use a commit hash / branch name (for example `main-SNAPSHOT`) — releases tags are more stable.

### Step 2 — Build on JitPack (first time only)

1. Open https://jitpack.io/#bchan77/jdbc-yugabyte-universe-failover  
2. Enter the tag (for example `v0.1.0`) and click **Get it**  
3. Wait until the build shows **green** (JitPack runs `mvn install` using [`jitpack.yml`](jitpack.yml))

### Step 3 — Add JitPack as a Maven repository

Maven only pulls artifacts from repositories you configure. Add **JitPack** to the **application** `pom.xml` (or to `~/.m2/settings.xml` for all projects):

```xml
<repositories>
  <repository>
    <id>jitpack.io</id>
    <url>https://jitpack.io</url>
  </repository>
</repositories>
```

Gradle (`settings.gradle` or `build.gradle`):

```gradle
repositories {
    mavenCentral()
    maven { url 'https://jitpack.io' }
}
```

### Step 4 — Declare dependencies

JitPack uses **GitHub-based coordinates** (different `groupId` from the Java package name inside the JAR):

| Module | JitPack coordinates |
|--------|---------------------|
| Core | `com.github.bchan77.jdbc-yugabyte-universe-failover:universe-failover-core:v0.1.0` |
| Hikari helpers | `com.github.bchan77.jdbc-yugabyte-universe-failover:universe-failover-hikari:v0.1.0` |

**Maven** (`pom.xml`):

```xml
<dependency>
  <groupId>com.github.bchan77.jdbc-yugabyte-universe-failover</groupId>
  <artifactId>universe-failover-core</artifactId>
  <version>v0.1.0</version>
</dependency>
<dependency>
  <groupId>com.github.bchan77.jdbc-yugabyte-universe-failover</groupId>
  <artifactId>universe-failover-hikari</artifactId>
  <version>v0.1.0</version>
</dependency>
<!-- Yugabyte SmartDriver (from Maven Central) -->
<dependency>
  <groupId>com.yugabyte</groupId>
  <artifactId>jdbc-yugabytedb</artifactId>
  <version>42.7.3-yb-4</version>
</dependency>
```

**Gradle:**

```gradle
implementation 'com.github.bchan77.jdbc-yugabyte-universe-failover:universe-failover-core:v0.1.0'
implementation 'com.github.bchan77.jdbc-yugabyte-universe-failover:universe-failover-hikari:v0.1.0'
implementation 'com.yugabyte:jdbc-yugabytedb:42.7.3-yb-4'
```

Imports in Java code are unchanged (`com.universe.jdbc.failover.*`) — only the **Maven/Gradle dependency coordinates** come from JitPack.

Replace `v0.1.0` with your tag, branch, or commit (see the version picker on JitPack).

### Alternative: local `mvn install` (development)

```bash
git clone https://github.com/bchan77/jdbc-yugabyte-universe-failover.git
cd jdbc-yugabyte-universe-failover
mvn clean install
```

Then depend on the in-repo coordinates (no JitPack repository needed on that machine):

```xml
<dependency>
  <groupId>com.universe.jdbc.failover</groupId>
  <artifactId>universe-failover-core</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```

### How Maven chooses which repository

Maven resolves each dependency from:

1. Your local cache `~/.m2/repository`
2. Repositories listed in the project `pom.xml` and `~/.m2/settings.xml`
3. **Maven Central** (by default) — provides `jdbc-yugabytedb`, SLF4J, Hikari, etc.

This library is fetched from **JitPack** (or local `.m2` after `mvn install`), not from Central, until it is published to Maven Central under `com.universe.jdbc.failover`.

### Corporate / private Artifactory

Mirror JitPack or upload the built JARs to internal Artifactory/Nexus, then point developers at that repository only (same pattern as any third-party dependency).

---

## Usage

### Option A — Bring your own `DataSource` (recommended for production)

You configure each universe’s pool (Hikari, etc.) with **only that universe’s nodes** in the JDBC URL.

```java
import com.universe.jdbc.failover.JdbcThresholdFailoverPolicy;
import com.universe.jdbc.failover.UniverseFailoverDataSource;
import java.time.Duration;
import javax.sql.DataSource;

DataSource routing =
    UniverseFailoverDataSource.builder()
        .primary("eu", hikariEu)
        .standby("us", hikariUs)
        .failoverPolicy(
            JdbcThresholdFailoverPolicy.builder()
                .consecutiveActiveProbeFailures(3)
                .probeInterval(Duration.ofSeconds(5)) // used by routing DS scheduler
                .cooldownAfterSwitch(Duration.ofMinutes(5))
                .requireStandbyHealthy(true)
                .allowAutoFailback(false)
                .build())
        .probeInterval(Duration.ofSeconds(5))
        .build();

Connection conn = routing.getConnection();
```

### Option B — JDBC URI per universe (convenience / demos)

```java
import com.universe.jdbc.failover.hikari.PoolConfig;
import com.universe.jdbc.failover.hikari.UniverseFailoverDataSources;

DataSource routing =
    UniverseFailoverDataSources.builder()
        .defaultPoolConfig(
            PoolConfig.builder()
                .driverClassName("com.yugabyte.Driver")
                .maximumPoolSize(50)
                .connectionInitSql("SET statement_timeout = 3000")
                .build())
        .primaryUri("eu", euJdbcUrl)
        .standbyUri("us", usJdbcUrl)
        .build();
```

Each URL must list **only one universe’s hosts**, for example:

```
jdbc:yugabytedb://eu-node1:5433,eu-node2:5433,eu-node3:5433/mydb
  ?load-balance=true
  &topology-keys=onprem.eu-west-1.*:1,onprem.eu-central-1.*:1
  &fallback-to-topology-keys-only=true
```

Do **not** mix EU and US nodes in one URL.

### SmartDriver with two universes in one JVM

SmartDriver load-balances **inside one universe**. This library load-balances **between** universes. Both run together when you keep a separate pool (and JDBC URL) per universe.

When the application holds **two** SmartDriver pools (primary + standby), the driver may call `yb_servers()` on each pool. On YugabyteDB versions that do **not** return `universe_uuid` in `yb_servers()`, the US pool can discover EU nodes (and vice versa). Symptoms:

- Driver log: *"does not send universe_uuid in its response of yb_servers()"*
- `active=us` in this library but `inet_server_addr()` shows an EU host
- US health probes stay **HEALTHY** after all US tservers stop → **no universe failover**

**Mitigations** (pick one per deployment):

| Approach | When to use |
|----------|-------------|
| `fallback-to-topology-keys-only=true` plus correct `topology-keys` per URL | Two universes, same JVM, no `universe_uuid` yet (validated on 2024.2.2.0) |
| Upgrade YugabyteDB so `yb_servers()` returns `universe_uuid` | Preferred long-term; SmartDriver can separate universes without topology fallback |
| `load-balance=false` | External clients (e.g. laptop outside VPC) or when in-universe SmartDriver LB is not required |

`universe_uuid` (or the topology fallback) **does not replace** this library—it only keeps each SmartDriver pool scoped to one universe so JDBC probes report the correct universe health.

### Manual switch (operator / test)

```java
((UniverseFailoverDataSource) routing).switchTo("us");
System.out.println(routing instanceof UniverseFailoverDataSource ds
    ? ds.getActiveUniverseId()
    : "n/a");
```

### Disable automatic failover

```java
.failoverPolicy(NeverFailoverPolicy.INSTANCE)
```

---

## Failover policy (`JdbcThresholdFailoverPolicy`)

The default policy fails over only when **all** of the following hold:

1. **Cooldown** elapsed since the last switch (default 5 minutes)
2. **Active universe** probe failed **consecutively** (default 3 probe cycles)
3. **Standby** probe is **healthy** (default required)
4. Active universe is currently the **configured primary** (failover → standby)

It does **not** fail over when:

- A single connection breaks (node reboot) but probes recover on other nodes
- One probe failure (threshold is consecutive failures)
- Standby is also unhealthy (decision: `BLOCKED`)

Tune for your RTO/RPO and outage expectations:

```java
JdbcThresholdFailoverPolicy.builder()
    .consecutiveActiveProbeFailures(3)   // ~15s with 5s probe interval
    .minStandbyProbeSuccesses(1)
    .cooldownAfterSwitch(Duration.ofMinutes(5))
    .requireStandbyHealthy(true)
    .allowAutoFailback(false)            // DR: usually manual failback
    .build();
```

---

## Spring Boot (optional integration)

This library does not require Spring. Typical Spring Boot wiring:

```java
@Bean
@Primary
DataSource dataSource() {
  return UniverseFailoverDataSource.builder()
      .primary("eu", hikariEu())
      .standby("us", hikariUs())
      .build();
}
```

Keep your existing `@Retry` for **in-universe** SQL states (`40001`, `08006`, etc.). Universe failover is **orthogonal**: it changes the target pool, not statement retries on the same universe.

---

## Lifecycle and shutdown

If you use `primaryUri` / `standbyUri`, pools are **owned** by the routing `DataSource` and closed on:

```java
((AutoCloseable) routing).close();
```

If you pass your own Hikari instances, **you** close them unless you registered them with `registerOwnedDataSource`.

---

## Observability

`UniverseFailoverDataSource.metrics()` exposes:

- `activeUniverseId`
- `activeProbeStatus` / `standbyProbeStatus`
- `consecutiveActiveProbeFailures`
- `failoverCount`, `lastSwitchAt`

Register a listener:

```java
.addFailoverListener((from, to, decision, at) ->
    log.warn("Universe switched {} -> {} ({})", from, to, decision))
```

---

## Logging and troubleshooting

The library uses **SLF4J**. Add a binding in your application (for example `logback-classic` or `slf4j-simple`).

All loggers are under the package prefix:

```text
com.universe.jdbc.failover
com.universe.jdbc.failover.hikari
```

### Log levels

| Level | What you see | When to enable |
|-------|----------------|----------------|
| **ERROR** | Unexpected probe-cycle failures | Always |
| **WARN** | `getConnection()` failure on active universe (sqlState, message); failover **BLOCKED** (standby not ready, cooldown); background probes disabled | Production troubleshooting |
| **INFO** | Startup: universe ids, **sanitized** JDBC/pool summary per universe, probe interval, policy class; policy decision to fail over; **active universe switch**; manual `switchTo`; Hikari pool creation | Production (recommended) |
| **DEBUG** | Each probe **cycle summary**; per-probe SQL failures; policy `NO_ACTION` while failures are accumulating; connection failure stack traces; full Hikari JDBC URL (redacted) and pool settings | Active incident investigation |
| **TRACE** | Every `getConnection()` route target; every probe result with timing; policy `NO_ACTION` when fully healthy | Deep debugging (verbose) |

Passwords in JDBC URLs are **never** logged in clear text (`password=` and URL userinfo are redacted).

### Example Logback configuration

```xml
<!-- Production: INFO for failover, WARN for driver noise -->
<logger name="com.universe.jdbc.failover" level="INFO"/>
<logger name="com.universe.jdbc.failover.hikari" level="INFO"/>

<!-- Investigation: DEBUG probe cycles and policy reasoning -->
<logger name="com.universe.jdbc.failover" level="DEBUG"/>

<!-- Deep dive: every connection route and probe -->
<logger name="com.universe.jdbc.failover" level="TRACE"/>
```

### What to look for during failover

1. **Startup INFO** — confirms primary/standby ids and which JDBC URL/pool each universe uses (sanitized).
2. **Probe cycle DEBUG** — `activeProbe`, `activeProbeFailures`, `standbyProbe`, `standbyProbeSuccesses`.
3. **Policy INFO/WARN** — `decision=FAILOVER_TO_STANDBY` with `reason=...`, or `BLOCKED` with reason (cooldown, standby not ready).
4. **INFO** — `Active universe changed: eu -> us`.
5. **WARN** on a single `getConnection` failure** — normal for one node reboot; universe should **not** switch until probe thresholds are met (see DEBUG probe counters).

`JdbcThresholdFailoverPolicy` also logs its threshold configuration once at **INFO** when the policy is built.

---

## Live validation

Generic ops scripts live under [`scripts/failover-lab/`](scripts/failover-lab/). A runnable demo app can live locally under `examples/` (gitignored — not published).

**Setup** (once per cluster):

```bash
cd scripts/failover-lab
cp hosts.env.example hosts.env              # SSH + node IPs (public or reachable)
cp hosts-internal.env.example hosts-internal.env   # optional: in-VPC JDBC + topology-keys
# edit both files with your addresses; set SSH_KEY if not in the file
export YBA_HOST=ec2-user@203.0.113.1        # jump host inside your VPC (example IP)
```

| Item | Typical lab value |
|------|------------------|
| Primary universe | 3 nodes, multi-region |
| Standby universe | 3 nodes, xCluster replication |
| Database | your app DB |
| Failover policy (lab) | 3 probe failures, 3s probe interval, 30s cooldown |

Set `PRIMARY_UNIVERSE_ID` / `STANDBY_UNIVERSE_ID` in `hosts-internal.env` to match your demo app’s universe ids (e.g. `us` / `eu`).

### Test results (June 2026, private lab)

#### Universe failover

| Run | Client | JDBC | Stop all primary tservers | Universe failover | Notes |
|-----|--------|------|---------------------------|-------------------|--------|
| 1 | Laptop (public IPs) | `load-balance=false` | Yes | **Yes** (primary → standby, ~15s) | SmartDriver uses public hosts in URL only |
| 2 | Jump host (in-VPC) | `load-balance=false`, internal IPs | Yes | **Yes** (~15s) | Recommended in-VPC baseline |
| 3 | Jump host | `load-balance=true`, topology-keys only | Yes | **No** | Primary pool probed standby nodes; false healthy probes |
| 4 | Jump host | `load-balance=true` + `fallback-to-topology-keys-only=true` | Yes | **Yes** (~15s) | Queries on primary before outage; standby after switch |

#### Auto-failback (`allowAutoFailback=true`)

| Run | Client | Steps | Result | Notes |
|-----|--------|-------|--------|--------|
| 5 | Jump host (automated script) | Stop primary → failover → restart primary before cooldown → wait | **PASS** | `Active universe changed: standby → primary` ~30s after failover |
| 5a | Jump host (manual, primary still down at failback) | Stop primary → failover → wait cooldown without restart | Failback **fires** but queries **fail** until tservers restarted | Policy failbacks when active standby pool is healthy + cooldown elapsed |

Automated run:

```bash
export YBA_HOST=ec2-user@203.0.113.1
./scripts/failover-lab/run-auto-failback-test-on-yba.sh
# => PASS: auto-failback live test succeeded
```

#### Unit tests (`JdbcThresholdFailoverPolicy`)

`mvn test` in `universe-failover-core` — 10 tests including `allowAutoFailback`:

| Test | Verifies |
|------|----------|
| `autoFailbackDisabled_doesNotReturnToPrimaryWhenOnStandby` | Default `false` → no failback |
| `autoFailbackEnabled_returnsToPrimaryWhenStandbyActiveAndHealthy` | `FAILBACK_TO_PRIMARY` |
| `autoFailbackEnabled_blockedDuringCooldown` | Failback blocked during cooldown |
| `autoFailbackEnabled_doesNotReturnWhenActiveProbeFailuresAccumulating` | No failback while probes failing |
| `autoFailbackEnabled_doesNotApplyWhenAlreadyOnPrimary` | No-op on primary |

**Takeaways:**

1. **Single-node stop** (one US tserver) did not trigger universe failover—SmartDriver retried other US nodes as expected.
2. **All three US tservers stopped** triggered failover when the primary pool could not serve `SELECT 1`.
3. With **`load-balance=true` alone** on 2024.2.2.0, false healthy probes blocked failover; add **`fallback-to-topology-keys-only=true`** (or upgrade for `universe_uuid`).
4. **`allowAutoFailback(true)`** returns traffic to primary after cooldown; restart the primary universe before cooldown ends so queries succeed after failback.
5. This library routes JDBC only—it does not run YBA xCluster DR promotion.

### Run auto-failback test (jump host)

```bash
export YBA_HOST=ec2-user@203.0.113.1
./scripts/failover-lab/run-auto-failback-test-on-yba.sh
```

Orchestrates: `ALLOW_AUTO_FAILBACK=true` → stop all primary tservers → failover → restart primary → verify automatic failback (~75s total).

### Run from jump host (internal IPs)

```bash
mvn clean install -DskipTests   # repo root
export YBA_HOST=ec2-user@203.0.113.1
./scripts/failover-lab/run-on-yba.sh all
```

Steps: `deploy` → `demo` → `failover` → `recover`. Requires a built demo JAR (`FAILOVER_DEMO_JAR`) or a local `examples/` demo module.

### Run locally (public IPs)

```bash
mvn clean install -DskipTests
# run your demo app with PRIMARY_HOSTS / STANDBY_HOSTS from hosts.env
# other terminal:
./scripts/failover-lab/stop-universe-tservers.sh primary
```

---

## DR and data consistency

Switching the JDBC target does **not** by itself:

- Promote an xCluster DR replica (use YBA DR workflows when required)
- Guarantee zero data loss (async replication lag may apply)

Coordinate application cutover with your DR runbook. This library is the **traffic routing** layer; YBA remains the **control plane** for cluster and xCluster operations.

---

## Roadmap and future design

**v0.1 (today)** delivers JDBC-only failover: background `SELECT 1` probes, `JdbcThresholdFailoverPolicy`, and routing between two universe pools. That is enough to validate routing, pool switching, and single-node vs universe-level failure behavior without any control-plane dependency.

**Planned next** is optional integration with **YugabyteDB Anywhere (YBA)** as an additional health signal—not a replacement for JDBC liveness. The design below is the target; APIs may evolve in minor releases.

### Design goal: pluggable health signals

Failover decisions will be driven by a small **provider** model so you can enable one or more sources:

```
┌─────────────────────────────────────────────────────────────┐
│  FailoverDecisionEngine (planned)                           │
│  • combines signals from enabled providers                  │
│  • applies composite policy (AND / veto / priority)         │
└───────────────┬─────────────────────┬───────────────────────┘
                │                     │
                ▼                     ▼
┌───────────────────────────┐  ┌───────────────────────────┐
│  JdbcHealthProvider       │  │  YbaHealthProvider        │
│  (v0.1 — probes + errors) │  │  (planned)                │
└───────────────────────────┘  └───────────────────────────┘
```

| Provider | Role | Status |
|----------|------|--------|
| **JDBC** | Proves the active universe can serve connections; detects sustained outage | **Implemented** (via probes + `JdbcThresholdFailoverPolicy`) |
| **YBA** | Control-plane view: universe health, xCluster/DR state, replication lag / safetime | **Planned** |
| **Registry table** | Shared DB table indicating intended active universe | **Not planned near-term** |

JDBC remains the **liveness** signal. YBA is intended for **safety and intent** (for example “is replication lag acceptable before we cut traffic?”), not for detecting a single node reboot inside a healthy universe.

### Planned module: `universe-failover-yba` (optional)

A separate Maven module (optional dependency) will keep YBA HTTP/client code out of `universe-failover-core`, so applications that only use JDBC probes do not pull YBA client libraries.

Rough configuration shape:

```yaml
failover:
  providers:
    jdbc:
      enabled: true
    yba:
      enabled: true
      base-url: https://yba.example.com          # VIP / DNS to active YBA
      # or, when no single URL:
      endpoints:
        - https://yba-1.example.com
        - https://yba-2.example.com
      api-token: ${YBA_API_TOKEN}
      customer-uuid: ...
      primary-universe-uuid: ...
      standby-universe-uuid: ...
      dr-config-uuid: ...                         # optional, for DR/xCluster checks
      ignore-unavailable: true                    # YBA down → do not block JDBC-only failover
      required-for-failover: false                # if true, never auto-failover when YBA unreachable
```

### What YBA health checks would do (planned)

`YbaHealthProvider` would poll YBA REST APIs on a background schedule (short timeouts; never on every `getConnection()`), and produce a `HealthSignal` such as:

| Check | Purpose |
|-------|---------|
| **Universe status** | Primary/standby universe unhealthy, update in progress, paused, etc. |
| **xCluster / DR config state** | DR relationship running, paused, or error |
| **Replication lag / safetime** | Estimated lag or data-loss window before allowing traffic switch |

Example YBA APIs under consideration (exact paths depend on YBA version):

- Universe status (health of masters/tservers from the platform view)
- DR config **safetime** and lag estimates (for “is it safe to fail over?”)

The provider would **interpret** YBA responses against configurable thresholds (for example max lag in milliseconds). It would **not** reimplement Raft quorum logic inside the app.

### YBA high availability (planned)

YBA itself may run in HA (primary/standby). The client will support:

1. **Single `base-url`** — recommended when a load balancer or DNS points at the current active YBA instance.
2. **Multiple `endpoints`** — try each endpoint until one responds; optional stickiness and circuit breaker to avoid hammering a dead peer.

When all endpoints fail:

| Setting | Behavior |
|---------|----------|
| `ignore-unavailable: true` | Treat YBA as “no veto”; JDBC policy may still fail over (degraded mode). |
| `required-for-failover: true` | Block automatic failover until YBA is reachable again (conservative). |

A **circuit breaker** will stop rapid repeat calls to YBA after repeated failures, then probe again after a backoff interval.

### Composite failover policy (planned)

`FailoverPolicy` will be extended (or wrapped) to support **composite** rules, for example:

```text
Failover allowed when:
  JDBC: primary universe DOWN for sustained period (existing thresholds)
  AND (
    YBA: confirms primary unhealthy / DR allows switch
    OR yba.ignore-unavailable == true
  )
  AND NOT (
    YBA: replication lag > maxLagMs
  )
```

Typical production pattern:

- **JDBC** → “is the database actually unreachable for clients?”
- **YBA** → **veto** if lag is too high or DR task is in progress; **confirm** when control plane agrees primary is down

Preset policy modes (planned):

| Mode | Providers | Use case |
|------|-----------|----------|
| `jdbc-only` | JDBC | Default today; labs; no YBA dependency |
| `yba-only` | YBA + JDBC confirm after switch | Rare; ops-driven |
| `composite` | JDBC + YBA | Production DR with safety gates |

Automatic **failback** to primary will remain **off by default**; YBA switchover/failover tasks are operator-controlled in most DR runbooks.

### What we are not planning to do in the YBA module

| Item | Notes |
|------|--------|
| **Execute YBA DR failover/switchover tasks** | v1 routing only **moves JDBC traffic**. Calling YBA to promote a replica may be a later, explicit optional action—not silent side effect of `getConnection()`. |
| **Replace SmartDriver** | Unchanged. |
| **Shared registry table** | A replicated metadata table for “active universe” is **deferred**; may be revisited separately from YBA. |
| **Spring-only coupling** | YBA provider will be plain Java like the core module; Spring Boot autoconfig may follow later. |

### Phased delivery (planned)

| Phase | Deliverable |
|-------|-------------|
| **0.1** (current) | `UniverseFailoverDataSource`, JDBC probes, `JdbcThresholdFailoverPolicy`, Hikari URI helpers |
| **0.2** | `HealthSignalProvider` abstraction; refactor policy to `FailoverDecisionEngine` |
| **0.3** | `universe-failover-yba`: `YbaHealthProvider`, HA client, `ignore-unavailable` / circuit breaker |
| **0.4** | `CompositeFailoverPolicy`, lag/safetime thresholds, documentation and examples |
| **Future** | Optional webhook listener (YBA notifies app after DR switch); optional Spring Boot starter |

Contributions and feedback on YBA API choices (which endpoints and thresholds matter for your DR model) are welcome via GitHub issues.

---

## License

Apache License 2.0. See [LICENSE](LICENSE).
