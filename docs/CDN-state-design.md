# Downloader state ownership

[Documentation entry point](CDN-downloader.md) · [Transfer accounting](CDN-transfer-accounting.md)

This document owns the current runtime policy. Historical sizing and deployment notes
remain in the fork maintenance document; they do not add parallel admission policies.

The scheduler keeps speed evidence, CDN health, playback readiness, cache ownership,
and request accounting independent. A seek changes the evidence epoch and readiness;
it does not reset cooldowns or confidence penalties.

| Owner | State and transitions |
| --- | --- |
| `PlaybackSchedulingTracker` | Fresh → established on readiness; seek → seeking until ready again. Paused is a presentation state that preserves readiness history. Only a stall after readiness is genuine rebuffering. |
| `BlockSpeedWindow` | Bootstrap measurements → indexed media-position evidence. Seek captures the old estimate as fallback and starts a new evidence epoch. New credible evidence gradually replaces the fallback. |
| `CdnResolver` | Health, cooldown admission and confidence penalties remain independent of the speed window. Deadline selection uses the penalty-adjusted per-block estimate. |
| `AttemptAccounting` | Pending → retained or discarded, independently of socket completion. A discarded attempt is reported once both disposition and final byte count are known. |
| Shared cache budget | Reservations and resident bytes share a memory budget. Reaching the admission limit pauses admission; undelivered playback data is not evicted to make room for more speculative downloads. Delivered or explicitly abandoned data can be reclaimed. |
| `DownloadWorkTelemetry` | Worker cells begin/end with actual HTTP work. Their colors, wait reasons and aggregate traffic are observations, never inputs to route selection. |

## Block-speed evidence

Each finalized sample contains its media position and bytes divided by the complete
request duration, including latency and stalls. Each CDN/representation keeps at most
64 samples within 60 seconds of its furthest completed media position. Samples use
exponential weighting with a 15-second media-time half-life. Out-of-order completions
cannot move that frontier backward. Detailed request observations are then discarded.
Idle wall-clock time does not expire this speed estimate; exploration staleness and
cooldowns keep their separate existing policies.

Unknown-position requests provide at most eight bootstrap rates. After seeking,
the old estimate continues guiding selection until indexed new-position evidence
is available. Evidence becomes credible after 256 KiB or one second of finalized
request duration. Its blend weight grows toward one with 1 MiB or four seconds of
evidence; the old fallback cannot return after that transition completes. A reader
captures its epoch when opened, so late pre-seek work cannot contaminate the new
window. Health outcomes still apply independently. With no media-position mapping,
post-seek bootstrap results do not replace the retained indexed fallback.

Multiple requests on one CDN each produce a block-rate sample. Their aggregate
bandwidth is not divided, multiplied, or substituted into the selection estimate.
The chart's total traffic is separately summed across response-body transfers.

## Readiness and rescue

Startup and seek buffer emptiness are expected readiness states. A blocking request
gets at least two seconds and its captured estimated transfer duration before a
readiness rescue. Continued failure to become ready can ramp rescue work under the
existing shared-slot limits, without classifying expected startup/seek emptiness as
actual-buffering severity. Ordinary deadline, fair-opportunity and confidence
recovery rules continue to govern subsequent outcomes.

## Diagnostics and byte disposition

`ExactTransferLedger` maintains an independent ownership state machine for each
received body-copy interval: pending while owned and never delivered, used on first
Media3 delivery, or discarded when the final owner releases an unused interval.
Source completion, result stitching, cache retention and pinned reader lifetimes
transfer provenance handles without changing scheduling or memory reservations.
Media3 releasing delivered data never changes used bytes into overhead.

The overhead donut consumes this exact partition when the provenance sampler is
attached (`DownloadTransferStats.exact`). Only the sampler-free fallback retains
approximate labels. Raw observations remain separate overlapping measurements;
`AttemptAccounting` loser reports are not added to exact disposal totals. See
[transfer accounting](CDN-transfer-accounting.md) for ownership transitions, fields,
seek examples and the remaining disposal-category granularity refinement.

The worker grid has one cell per configured slot, uses the CDN palette and existing
audio/video icons, and preserves yellow probe and orange rescue markers. Empty
slots can show a compact scheduler wait reason. The old segment-size plot is
replaced by this grid and donut; segment sizes remain in the CDN summary.

## Admission under track imbalance

The least-prepared track remains a balancing barrier when its admission is denied
by memory or the coordinator queue. Another track may catch up to that scheduled
media horizon, but cannot repeatedly consume smaller free gaps to run farther ahead.
Immediate reader demand remains eligible regardless of this barrier. This applies
symmetrically to video and audio and does not change worker limits or evict protected
future data. A track with no remaining candidate does not block its peer.

Read-ahead admission events now describe successful admission, rather than every
failed selection retry. Wait events include blocked tracks and prepared horizons.
Memory-denied telemetry records the requested range and reserved demand headroom;
the UI distinguishes exhausted capacity from capacity held for immediate demand.

## Adaptive memory budget (current policy)

Media3 keeps its existing heap/4 sample-buffer ceiling, at most 128 MiB. The
combined downloader budget may grow from its initial allowance up to
`heap/2 - Media3 ceiling`. Thus cache growth never borrows Media3 capacity or
increases the half-heap combined ceiling. Initialization retains the previous
conservative settings until a successful pressure sample permits expansion.

The session samples pressure every five seconds independently of diagnostics.
Known resident downloader arrays are credited once against measured usage. The
heap allowance leaves heap/4 free and protects Media3's not-yet-allocated budget.
The device allowance uses at most one quarter of available RAM after leaving the
larger of twice Android's low-memory threshold or 10% of device RAM untouched.
The smaller allowance caps downloader capacity. Low-memory status stops additional
borrowing of device RAM. Failed samples never authorize growth. These are cautious
admission estimates, not measurements of decoder/surface ownership.

Expansion happens only when a pending block needs it (an 8 MiB growth increment,
or enough for that block, bounded by the sampled allowance). Pressure reductions
apply immediately to admission; existing work may exceed the newly reduced budget
until it drains. Normal refill reclaims only delivered/abandoned cache entries;
recovery has the narrowly scoped unread-eviction exception described below. Growth and
pressure changes are rechecked even while Media3 is not reading.

The actual planned partition sizes update the video/audio shape; larger still-owned
blocks remain included. Unknown tracks begin with a bounded bootstrap estimate.
Ordinary demand reserve is twice one current block per track, minus already-charged
head blocks and the proposed admission. Rescue capacity targets twice the largest
block times half the worker count, bounded by the existing heap-based rescue ceiling
and one third of the current downloader budget so ordinary work retains capacity.
If memory cannot support all rescue workers, fewer are admitted. Active rescue
charges continue counting even when the desired reserve shrinks.

For current admission, let `B` be the adaptive downloader budget, `P` ordinary
payload charges (including recovery originals), `K` cache-only bytes, `A` duplicate
rescue charges, `O` recovery-original charges already included in `P`, `R` the desired
rescue target, `Q` pending copy-inclusive recovery claims, `D` ordinary demand headroom,
and `C` the proposed copy-inclusive cost:

```
protected recovery = max(max(0, R - O), A + Q)
ordinary fits when P + K + C + D <= max(0, B - protected recovery)
recovery fits when P + K + A + C <= B
```

Admission may grow `B` only within the sampled allowance and hard ceiling. Claims
fence ordinary refill but are not occupied memory and do not mutually block recovery.
The shared HTTP controller selects the eligible recovery; actual memory reservation
is atomic. Recovery may borrow free ordinary capacity within the total budget.

There is no second copy-overhead subtraction: ordinary and rescue reservations
already charge twice their range size. Shared cache/payload arrays count once.
Waiting never substitutes a farther-ahead track. Coordinator and transport queues
also rank ordinary blocks by media position, after immediate playback demand.

Trace records include the current budget, pressure ceiling, hard ceiling, track
block sizes, rescue reserve, requested admission size, and ordinary headroom.


## Recovery admission and memory ownership

All HTTP starts use HttpAdmissionController and idempotent slot leases. Priority is recovery,
current demand, ordinary media order, then speculative work. Eligibility is live: unavailable CDN
or memory does not monopolize the queue. Ordinary/probe/exploration occupancy is N−1; total is N.
No cancellation occurs just to acquire a worker. Startup probe quotas and cancellation-on-ready
remain separate lifecycle rules. The retired OrdinaryRequestQueue is replaced by this controller.

Recovery transitions through slot wait, memory wait/reclaim, running, completion/cancellation.
Pending claims block ordinary refill but do not count as allocated bytes or mutually block
recoveries. Cancellation remains charged until the transport exits and the last body owner releases.
The next required original may borrow recovery protection; its key follows its byte reservation
until release. All ordinary/recovery/cache memory stays within the adaptive aggregate ceiling.

Memory recovery first reclaims normal eligible data, then far-future pending work and unpinned
unread cache. Exact demanded ranges remain protected. Retired ranges return to the ordered plan,
reads cannot cross a missing range, and revocation serializes with insertion to reject late results.
Pre-deadline and buffering rescue share max(2, floor(N/2)) attempts, bounded by N; discovery also
observes the existing two-second step. A 30-second memory-blocked read fails via IOException;
Media3 loading/refusal remains diagnostic-only with no new automatic reset.

## Policy-preservation checks

The shared admission controller replaces the disconnected ordinary queue; it does not
replace CDN selection, track balancing, startup readiness or the rescue staircase.
Keep these checks when changing queue or ownership code:

- A free HTTP slot alone never causes cancellation. Memory recovery waits for the
  prior retired allocation to release before selecting another victim.
- A pending escalation is withdrawn when its target completes, becomes stale or no
  longer needs escalation; a retry cannot remain blocked behind its own obsolete ticket.
- Discovery does not bypass the observation delay or add copies just because the
  original is slow when a running backup is forecast to finish in time.
- Recovery-original memory ownership carries its key through release. Timeout and
  seek detachment release pending claims without releasing still-owned allocations.
- Slot and byte leases are separate; neither cancellation intent nor dropping one
  cache reference proves physical release.

Regression tests and the code ownership map are linked from the downloader overview.
Coverage can expose an untested integration path even when policy helpers pass tests.
