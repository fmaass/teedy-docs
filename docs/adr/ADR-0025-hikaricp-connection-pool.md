# ADR-0025 — HikariCP as the JDBC connection pool

- **Status:** accepted
- **Date:** 2026-08-15
- **Issue:** #230 ("The internal connection pool has reached its maximum size") — the sizing history
  this decision closes out.

## Context

Until now the application ran on Hibernate's built-in JDBC pool
(`DriverManagerConnectionProviderImpl`). It has four properties that together produced two
production incidents:

- **It never shrinks.** Every connection a burst opens is held for the lifetime of the process. On a
  shared PostgreSQL server that is visible as dozens of permanently idle backends belonging to one
  instance, and it counts against the server's `max_connections` for everybody else.
- **It throws as soon as it is full.** A caller arriving at the cap gets
  "The internal connection pool has reached its maximum size" immediately, with no queueing. That is
  #230: the application's own async workers (two buses, sized off the CPU count) can outnumber a
  fixed pool on a many-core host and fail a burst outright. The adaptive default introduced for #230
  (`ConcurrencySizing.defaultConnectionPoolSize`) raised the ceiling but did not change the
  behaviour at the ceiling.
- **It has no borrow timeout and no on-checkout validation.** On 2026-08-10 a transient network
  blip left a dead socket in the pool; every request thread parked forever acquiring a connection
  and only a container restart recovered. The client-side pgjdbc timeouts added afterwards
  (documented in `EMF.buildEnvironmentProperties`) bound the *socket*, which is what made that
  failure surface at all — but nothing bounded the *wait for a connection*.
- **It cannot be instrumented.** A connection that is never returned starves the pool silently.

Pinning `DATABASE_POOL_SIZE` low would only trade the idle-connection problem for #230 again.

## Decision

1. **HikariCP is the connection pool**, wired through Hibernate's own bridge
   (`org.hibernate.orm:hibernate-hikaricp`, provider
   `org.hibernate.hikaricp.internal.HikariCPConnectionProvider`). The provider is set explicitly in
   `EMF`, not left to classpath discovery, so the pool is never configured by someone else's
   defaults.

2. **The HikariCP version is pinned in the parent POM's dependency management**, ahead of the
   version the bridge declares transitively: the bridge still points at HikariCP 3.2.0, a 2018 build
   for Java 8. The pool the application runs on should be a maintained release.

3. **One post-step configures the pool for both configuration sources.** `EMF` resolves its
   properties either from a `hibernate.properties` resource (development, tests, CI) or from the
   environment (production), and `applyConnectionPool` runs after both. The CI PostgreSQL jobs
   therefore exercise the same pool wiring production runs.

4. **`DATABASE_POOL_SIZE` is unchanged in meaning and precedence** (environment variable →
   `hibernate.properties` pin → adaptive default from the CPU count) and becomes HikariCP's
   `maximumPoolSize`. The resolved value stays in `hibernate.connection.pool_size`, which is now a
   legacy key: nothing reads it at runtime, but it is the single place the resolution writes its
   result and the precedence tests assert on it. The two other built-in-pool keys
   (`initial_pool_size`, `pool_validation_interval`) are gone — no provider reads them any more.

5. **The remaining pool settings are fixed, not configurable** (a knob can be added later if a
   deployment ever needs one):
   - `minimumIdle` = 2, never above the maximum — the idle floor a quiet instance settles back to.
   - `idleTimeout` = 10 minutes — how long a connection above the floor survives being unused.
   - `connectionTimeout` = 30 s — the bounded wait for a connection when the pool is saturated.
   - `leakDetectionThreshold` = 5 minutes — a connection held longer logs an *apparent* leak with
     the stack that borrowed it. It is a warning, not proof: the threshold sits far above the
     legitimate long holders (a mail send, a search-index rebuild) so that a warning is worth
     reading.
   - `poolName` = `teedy`, so the pool's log lines are identifiable in a shared log stream.

6. **Two timeout layers coexist, deliberately.** HikariCP's `connectionTimeout` bounds the *borrow*
   (how long a caller queues for a connection). The pgjdbc `connectTimeout`, `loginTimeout` and
   `socketTimeout` bound the *network* (TCP connect, login handshake, socket read) for each physical
   connection, and they are what stops a dead socket from blocking a connection attempt forever.
   Hibernate hands HikariCP only url/username/password/driver/isolation/autocommit out of
   `hibernate.connection.*`, so the driver-level timeouts are mirrored into
   `hibernate.hikari.dataSource.*`, which HikariCP passes on to the driver. Without that mirroring
   the 2026-08-10 protection would be silently gone; a test asserts the keys and an end-to-end test
   proves a query against a dead socket still fails in seconds.

## Consequences / trade-offs

- Idle connections on the database server drop to about the idle floor (2 per instance) once a burst
  is over, instead of staying at the high-water mark for the life of the process.
- Under saturation a caller no longer fails immediately: it waits up to 30 s and only then fails.
  That is a better trade for a burst than #230's instant failure, but it is a real change — a
  saturated instance answers slowly before it answers with an error.
- The OIDC first-login provisioning path opens a second connection while its request transaction is
  still open (`OidcResource.runOnFreshTransaction`). Under saturation that nested borrow now waits
  up to 30 s instead of failing at once; the borrow timeout is what keeps it a failed request rather
  than an indefinite hang.
- Connections are validated before they are handed out, so a network blip costs a retry rather than
  a wedged worker.
- One more dependency to keep current, and one more component whose defaults have to be understood
  when reading a stall; the leak warnings are the compensation — a long hold now names its stack.
- Development and test runs use the same pool, so pool behaviour is exercised continuously rather
  than only in production.

## Alternatives rejected

- **Pin `DATABASE_POOL_SIZE` lower** — fewer idle connections, but it re-creates #230: the async
  workers alone can exceed a small fixed pool on a many-core host, and the built-in pool fails at
  the cap instead of queueing.
- **Leave the pool alone and only change monitoring** (raise the alert threshold, or exclude this
  instance) — hides the idle connections instead of releasing them, and keeps the fail-at-cap and
  no-validation behaviour that caused both incidents.
- **Cap the container's CPUs so the adaptive default computes a smaller pool** — works around the
  sizing formula and slows the application down to do it; it fixes neither shrinking nor validation
  nor the borrow timeout.
- **Evict idle connections manually on the built-in pool** — Hibernate's pool exposes no such hook,
  and growing a pool implementation inside this application is not a reasonable trade against a
  well-understood library.
- **Agroal or c3p0** — both are viable; HikariCP is the de-facto standard, has the smallest
  configuration surface for what is needed here, and is maintained by the same bridge project
  Hibernate ships. The provider is one property, so swapping it later is again a one-place change.
