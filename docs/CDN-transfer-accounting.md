# Transfer accounting: exact body-copy disposition and raw observations

[Downloader overview](CDN-downloader.md) · [State and ownership](CDN-state-design.md)

## Boundary and terminology

The accounting boundary is **HTTP body bytes read by the app → successful delivery
to Media3**. It does not measure whether the decoder displayed or played every sample.
HTTP headers, TLS/TCP/IP overhead, retransmissions and bytes only read ahead inside
network libraries are outside these counters.

**Media3 discarding data is not downloader overhead.** Once a downloaded copy has
been handed to Media3, it remains useful delivery for this accounting purpose.
Playing, seeking, clearing the player buffer, or freeing a used cache copy must not
retroactively reclassify that delivery as waste.

Do not equate these different quantities:

- Network body traffic: cumulative bytes read, including duplicate attempts.
- Delivery: cumulative bytes returned to Media3, including cache replay.
- Unique delivery: distinct file positions delivered during the session.
- Residency: data still held now, potentially already delivered.
- Allocation: physical arrays or reserved memory, including capacity/copy headroom.

## Implemented observations

`session_capacity.transferMeasurements` records the following. The same values are
available in `DownloadSnapshot.transferMeasurements`. Counters reset with the
session, not on seek; range snapshots reflect current sampled state.

| Field | Meaning | Important limit |
| --- | --- | --- |
| `downloadedBodyBytes` | Body bytes read across all attempts, including probes, rescues, retries and sequential fallback | App-body traffic, not wire traffic |
| `deliveredToPlayerBytes` | Bytes returned to Media3, including repeated delivery | Can exceed downloaded traffic through cache replay |
| `uniqueDeliveredBytes` | Per-track union of file ranges ever delivered | Re-downloading the same positions does not increase it |
| `repeatDeliveryBytes` | Total delivery minus unique delivery | Includes both cache replay and re-delivery after new downloads; not a cache-hit counter |
| `residentUniqueBytes` | Union of validated local ranges | Includes already-delivered data still retained |
| `inFlightUniqueBytes` | Union of currently received in-flight ranges | Counts logical positions once even when duplicate attempts overlap |
| `retainedUniqueBytes` | Union of local and in-flight ranges, independently per track | Not the sum of the preceding two fields; not allocated RAM |
| `confirmedTransportDiscardedBytes` | Finalized transport bytes explicitly discarded by attempt accounting | Lower bound; excludes later cache eviction and uncertain copied-prefix disposal |

These fields **overlap**. They are not a partition of network traffic and must not
be forced to balance by clamping one counter against another. Residency is sampled
across transport owners, so individual snapshots can straddle a handoff.

Additional existing measurements answer different buffer questions:

- `session_capacity.storage.knownBytes`: identity-deduplicated known backing arrays;
  category totals overlap. Transient copies/framework allocations are excluded.
- Memory budget fields: reserved capacity, claims and retained ownership; reservations
  are copy-inclusive and not evidence that all those bytes have been downloaded.
- Media3 `allocatorInUseBytes`: sample allocator storage, excluding its free pool,
  decoder/surface buffers and other allocations. It is not an exact unread-byte count.
- Player buffered duration: playable time ahead, not bytes and not speculative future
  blocks across a gap.

## Exact accounting

`ExactTransferLedger` identifies **each HTTP body copy**, rather than identifying
bytes only by their media file offset. Its synchronized snapshot partitions all
observed body bytes:

```
downloaded = used copies + pending copies + discarded unused copies
waste ratio = discarded unused copies / downloaded
```

Treat the ratio as zero when nothing has been downloaded. Categories are mutually
exclusive; no clamping against sampled cache ranges is needed.

| Category | Transition |
| --- | --- |
| Pending | Received and still owned, never handed to Media3 |
| Used | Handed to Media3 at least once; stays used even after all owners release it |
| Discarded unused | No surviving owner and never delivered |

A session creates one `Source` per response body and records successful body reads.
The source protects its received ranges while the attempt is active. Validated
results, copied rescue prefixes/suffixes, readers and cache entries hold `Handle`
views of those ranges. Slicing and concatenation retain source identity without
copying payload data. Network completion releases the source's ownership; it does
not dispose of ranges still referenced by a result or cache.

Only the last owner release finalizes unused bytes as discarded. Releasing a cache
reference or a memory reservation alone is insufficient. A pinned cache entry keeps
its handle until the final playback lease closes, even after cache closure. Partial
delivery marks only the delivered interval as used. Repeated delivery of that same
copy, including backward cache replay, never increases used bytes again. A fresh
response for an evicted media range is a new copy and can become used again.

Session shutdown also drains admitted HTTP tasks that never reached a worker.
Their canceled execution runs ownership cleanup without starting a network call,
so copied prefixes, allocation reservations and admission slots cannot be stranded.

Sequential fallback has a direct-delivery fast path: bytes read successfully into
the caller's buffer advance downloaded and used together without creating retained
per-read interval metadata. It uses the same accounting boundary as range mode.

Metadata stores intervals and ownership counts, not per-byte objects or payloads.
Adjacent equivalent intervals merge; settled sources leave the live set. Accounting
is observational and independent of request admission, memory budgets, CDN selection
and penalties. Allocation reservations still describe capacity, not received bytes.

### Logs and presentation

`session_capacity.transferAccounting` contains one consistent exact snapshot:

| Field | Meaning |
| --- | --- |
| `downloadedBytes` | All body bytes observed by this ledger |
| `usedBytes` | Byte copies delivered at least once |
| `pendingBytes` | Received copies still owned and never delivered |
| `discardedBytes` | Copies released unused by their final owner |
| `discardedByKind` | Exclusive discard totals using `DownloadOverheadKind` |
| `liveSources` | Response sources with unsettled retained metadata |
| `liveRanges` | Current interval metadata count across those sources |
| `scope` | Explicit boundary: received body copies, first delivery, excluding Media3 disposal |

`DownloadSnapshot.transferStats` uses the ledger when the session installs its sampler;
`DownloadTransferStats.exact` identifies that mode. The panel and donut show measured
use/disposal in this mode. Without a provenance sampler, they retain the legacy
file-range estimate and explicitly display approximate labels. Raw
`transferMeasurements` observations remain available separately and still overlap.

### Disposal categories

The existing `DownloadOverheadKind` categories attribute finalized unused copies.
An explicit close reason takes precedence; default `Other` falls back to the source's
finish reason. Cache eviction therefore participates in the exact total when its
last owner releases unused data, even without a dedicated eviction category.
Probe or rescue work is not inherently overhead: its delivered bytes remain used.

The current categories combine request purpose and disposal reason. A richer,
independent purpose/reason taxonomy is a possible diagnostic refinement; it is not
missing byte ownership or unfinished exact total accounting. A failed exploration
must not be summed twice under different categories.

## Legacy fallback estimate

When no exact sampler is attached, the previous estimate remains:

```
estimated overhead = downloaded body bytes
                   - session-unique delivered file bytes
                   - currently retained file bytes never delivered in this session
```

Overlapping ranges are deduplicated and sampled values clamped to traffic totals.
This fallback can overstate waste after useful re-downloads because file positions
were counted as used earlier. Approximate UI labels apply only to this fallback;
its inferred categories must not be confused with exact ledger discard counters.

## Seek examples for the exact model

| Sequence | Downloaded | Used | Pending | Discarded unused |
| --- | ---:| ---:| ---:| ---:|
| Download 1 MiB; hand it to Media3 | 1 MiB | 1 MiB | 0 | 0 |
| Seek backward and replay the same cached copy | 1 MiB | 1 MiB | 0 | 0 |
| Cache was evicted; download and deliver that range again | 2 MiB | 2 MiB | 0 | 0 |
| Download another 1 MiB but abandon it before delivery, releasing all owners | 3 MiB | 2 MiB | 0 | 1 MiB |

Media3 freeing any delivered data changes none of these traffic classifications.
A duplicate still running is not discarded yet. Bytes planned but never received
are not downloaded or waste. Bytes freed after use are memory turnover, not overhead.

## Regression coverage and remaining refinements

`ExactTransferLedgerTest` exercises interval ownership, partial delivery, cache replay,
new response copies, stitching, concurrent updates and metadata settlement.
`RangeBlockCacheTest` covers failed admissions, duplicate insertion, pinned closure,
shared owners and eviction. `ParallelDownloadSessionTest` checks real transport
accounting through startup/ordinary ranges, replay, eviction/re-download and truncated
response bodies. These are regression specifications; consult the current build/test
results rather than treating this document as a test-run record.

`DownloadTransferMeasurementsTest`, `DownloadTransferStatsTest` and
`AttemptAccountingTest` remain relevant to independent raw observations and legacy
fallback behavior. `AttemptAccounting` does not supply the exact ledger's disposal
partition and must not be added to its totals.

Remaining refinements concern disposal-reason granularity and device profiling of
metadata cost under long-running mixed seek/rescue workloads. No decoder-display or
wire-level traffic claim follows from this body-delivery accounting.
