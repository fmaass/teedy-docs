# Browser-harness safety

`scripts/e2e-browser-harness.sh` is a **destructive** end-to-end check. It logs in with the default `admin/admin` credentials, seeds fixture tags and documents, and stores a deliberate XSS payload as a document description to verify the sanitizer. Run it **only** against a disposable, throwaway instance.

## The guard

The harness refuses to run unless `E2E_ALLOW_SEED=1` is set. It refuses before any login or write, exits non-zero, and prints the reason.

There is deliberately no hostname check or heuristic auto-detection of whether the target is production. No runtime signal reliably distinguishes a disposable instance from a real deployment: a hostname can be spoofed, and a freshly restored production instance also answers on `admin/admin`. The only reliable guard is an explicit operator opt-in acknowledging that the target is disposable. Do not add a heuristic that would provide false confidence.

## Why it must never target a real instance

* It authenticates with default credentials.
* It writes fixture data and a stored XSS payload to the target.
* It is intended for a fresh, empty, throwaway instance whose data does not matter.

Pointing it at a real deployment would inject that payload and default-credential writes into live data.

## Cleanup

The run purges everything it creates. Each seeded document is trashed **and then permanently deleted from the recycle bin**, so the stored payload cannot remain there, and every seeded tag is deleted. Cleanup runs whether the checks pass or fail. Even so, cleanup is not a reason to relax the guard: treat any harness target as consumed.

## Running it

* `E2E_ALLOW_SEED=1` — required opt-in acknowledging that the target is disposable.
* `E2E_BASE_URL` — the disposable instance to target.
* `E2E_EXPECT_VERSION` — required expected version; there is no default.

`scripts/e2e-harness-run.sh` boots a throwaway container, tears it down, and sets the opt-in itself. A hand-run against another target must set it explicitly.
