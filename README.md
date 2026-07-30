# JNATS Active / Passive Connection Extension

![0.0.3](https://img.shields.io/badge/Current_Snapshot-0.0.3--SNAPSHOT-27AAE0?style=for-the-badge)&nbsp;&nbsp;&nbsp;&nbsp;
[![jnats canary](https://github.com/synadia-io/java-active-passive/actions/workflows/jnats-canary.yml/badge.svg)](https://github.com/synadia-io/java-active-passive/actions/workflows/jnats-canary.yml)&nbsp;&nbsp;&nbsp;&nbsp;
[![License Apache 2](https://img.shields.io/badge/License-Apache2-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)

## Overview

`ApConnection` is a drop-in extension of the standard [jnats](https://github.com/nats-io/nats.java) connection that keeps a **warm standby** ready at all times. It holds two live sockets: an **active** connection that you publish and subscribe on exactly like any NATS connection, and a **passive** connection kept connected to a *different* server in the background. When the active socket fails, the passive's already-open socket is promoted to be the new active — no cold reconnect, no server-pool walk, no waiting on a TCP+TLS handshake at the worst possible moment — and a fresh passive is immediately armed on another server.

Because `ApConnection extends NatsConnection`, it *is* a `Connection`: everything you already do with jnats — `publish`, `subscribe`, `request`, dispatchers, drain — works unchanged. The active/passive machinery is invisible until failover, and the failover is invisible to your subscriptions.

- **Fast failover** — the standby socket is already connected and authenticated, so promotion is near-instantaneous rather than a fresh reconnect cycle.
- **Distinct servers** — the passive is always kept on a server other than the active, so a single server going down never takes both.
- **Self-healing** — both sides use infinite reconnect (`maxReconnects(-1)`); after any failover a new passive is re-armed automatically, and a passive that loses its own socket reconnects on its own.
- **Core NATS** — this is a connection-level failover extension; it needs nothing beyond core NATS.

### Quick start

```java
ApOptions apOptions = ApOptions.builder(
        Options.builder()
            .server("nats://host-a:4222")
            .server("nats://host-b:4222")   // at least two servers so active and passive can differ
            .connectionListener(activeListener)
            .errorListener(activeErrors)
            .build())
    .passiveConnectionListener(passiveListener) // optional: watch the standby separately
    .passiveErrorListener(passiveErrors)
    .build();

try (ApConnection apc = ApConnection.connect(apOptions)) {
    // use it like any jnats Connection
    apc.publish("subject", "hello".getBytes());
    Subscription sub = apc.subscribe("replies");

    // inspect the standby
    System.out.println("active  -> " + apc.getServerInfo().getServerId());
    System.out.println("passive -> " + apc.getPassiveServerInfo().getServerId());
}
```

### API at a glance

Beyond the full `Connection` API it inherits, `ApConnection` adds:

| Method | Purpose |
| --- | --- |
| `ApConnection.connect(ApOptions)` | Establish the active connection and arm the passive standby. |
| `switchToPassive()` | Fail over to the passive **on demand** — the same promotion that happens on failure, triggered by you. Throws `IllegalStateException` if there is no connected passive. |
| `getPassiveStatus()` | Current `Status` of the standby. |
| `getPassiveServerInfo()` / `getPassiveConnectedUrl()` / `getPassiveServers()` | Inspect where the standby is connected. |
| `passiveForceReconnect(...)` | Force the standby to reconnect (e.g. to rebalance it), without touching the active. |
| `passiveRTT()` | Round-trip time to the standby's server. Throws `IllegalStateException` if the passive isn't connected. |
| `close()` | Closes both the active and the passive. |

### Options

`ApOptions` wraps a normal jnats `Options` (which configures the **active** connection and seeds the shared server pool) and adds a few passive-side knobs:

| `ApOptions.Builder` | Meaning |
| --- | --- |
| `options(Options)` | Standard jnats options for the active connection. |
| `passiveConnectionListener(...)` / `passiveErrorListener(...)` | Listeners for the passive connection, so you can observe the standby independently of the active. |
| `activeServerPool(...)` / `passiveServerPool(...)` | Supply custom `ApServerPool`s. If omitted, a pool is derived from the connection `Options` (see below); the passive shares the active's pool unless given its own. |

> Note: `ApConnection` forces `maxReconnects(-1)` on both the active and the passive regardless of what the supplied `Options` request — in an active/passive pair, one side must always keep trying so the standby is never permanently lost.

### Active connection server pool selection

```
      ┌─────────────────────────────────────────────────────┐
      │ Did ApOptions have an active ApServerPool instance? │
      └─────────────────────────────────────────────────────┘
             │                          │
            YES                         NO
             │                          │
             ▼                          ▼
┌──────────────────────┐   ┌─────────────────────────────────────┐
│ Use that pool as the │   │ Did the user supply a server pool   │
│ server pool for the  │   │ in the regular connection Options?  │
│ active instance      │   └─────────────────────────────────────┘
└──────────────────────┘           │                     │
                                  YES                    NO
                                   │                     │
                                   ▼                     ▼
                ┌───────────────────────────┐  ┌────────────────────────────────┐
                │ Is that server pool an    │  │ Create an ApPassiveServerPool  │
                │ instance of ApServerPool? │  │ with a standard NatsServerPool │
                └───────────────────────────┘  │  as the delegate               │
                      │                │       └────────────────────────────────┘                       
                     YES               NO      
                      │                │
                      ▼                ▼
        ┌──────────────────────┐  ┌───────────────────────────────┐
        │ Use that pool as the │  │ Create an ApPassiveServerPool │
        │ server pool for the  │  │ with that pool as the         │
        │ active instance      │  │ delegate                      │
        └──────────────────────┘  └───────────────────────────────┘
```

### Passive connection server pool selection

```
      ┌─────────────────────────────────────────────────────┐
      │ Did ApOptions have a passive ApServerPool instance? │
      └─────────────────────────────────────────────────────┘
             │                          │
            YES                         NO
             │                          │
             ▼                          ▼
┌──────────────────────┐   ┌────────────────────────────────┐
│ Use that pool as the │   │ Use the active server pool as  │
│ server pool for the  │   │ resolved above for the passive │
│ passive instance     │   │ instance                       │
└──────────────────────┘   └────────────────────────────────┘
```
