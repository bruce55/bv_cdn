# CDN fork maintenance

Start with the [current downloader overview](CDN-downloader.md). Current scheduling
and ownership rules live in [the state design](CDN-state-design.md); counter semantics
and accounting boundaries live in [transfer accounting](CDN-transfer-accounting.md).
This maintenance document also retains earlier verification and sizing notes; historical
baselines are not additional runtime limits.

This fork ports the CDN selection feature from `bruce55/bv_cdn` commits
`9ff716a4` and `42aae45b` to `Frost819/newBV` main, initially `5f86846a`.
The new upstream history is independent of the legacy `feature` branch.
Keep the old branch as the legacy version; base subsequent updates on this
new upstream history and retain the fork commits above it.

## User behavior

Settings → Other → 播放源地区覆盖 selects a region, a specific node, or a custom
HTTP(S) host/URL. 使用默认源 removes the override. The selected host is persisted
in `cdn_override_host` and applies on the next playback load or media-profile
change. Both video and audio are overridden, including Dolby/FLAC when selected.
Single-stream playback prefers official API candidates. Upstream's optional
automatic selection (integrated through `6a5d231e`) is grouped with these CDN
controls; it ranks candidates before playback and tries another video candidate
after an error. Parallel mode takes precedence, followed by a manual host, then
automatic single-stream selection. See [the shared selection boundary](CDN-downloader.md#integration-with-upstream-automatic-selection).
Live streams retain upstream's separate line selection.

The new application has a different package ID from legacy BV, so Android does
not automatically transfer the old app's settings/login. Configure the CDN in
the new app. The preset list was carried forward; listing a node does not prove
that it remains reachable or serves every video in every region.

## Parallel VOD downloading (optional)

Settings → Other → 播放源地区覆盖 also contains separate switches for parallel
downloading, download visualization, and host diagnostics. All default to off and apply on the
next playback load. Mainland/overseas automatic routing uses a small candidate
pool plus the original signed base/backup URLs. Manual CDN selection is disabled while acceleration is enabled, and a saved
manual pin is ignored by accelerated playback. The saved choice is retained for
non-accelerated playback. The shared
video/audio request budget is 4, 8, 12, 16, 24, 32, 48, or 64 (default 8); retries and protected duplicate requests use the same budget.

The Media3 data source probes the same initial 64 KiB range on distinct CDNs
separately for audio and video. With N requests, the initial batch reserves one
rescue slot through shared admission (at most N−1 ordinary/probe HTTP attempts),
assigns max(1, floor(N/4)) audio probes, and assigns
the remaining probe slots to video. The first validated response supplies that
track's head immediately; one pending ordinary block per reader continues loading
required data during startup. Freed probe slots prioritize audio until ceil(N/2)
validated results, then randomly choose a track still needing results. In-flight
probes do not count toward these milestones. Each track stops and cancels its
remaining probes at N validated distinct-CDN results or candidate exhaustion.
Media3 readiness stops all startup probing; completing both tracks' probe targets
also restores normal scheduling even if playback is still buffering. Cancellation
is not a route failure. Protected explorations remain independent and may finish
after rescue. The timeline status shows validated video/audio probe counts next
to the active thread count only during startup probing.

The source reconstructs the SIDX
media-segment ranges that the original browser implementation receives. Each
range is divided into balanced pieces. The default minimum is 512 KiB; users can
choose 64/256/512 KiB or 1/2/4 MiB. Saved automatic values resolve to 512 KiB. Minima use
`min(workers, max(1, floor(bytes / minimum)))`; a short segment/remainder stays
smaller, and the startup head probe remains 64 KiB. Exploration uses these same
ordinary blocks without enlarging the planning ranges. Blocks too small for useful
halves are assigned whole to a protected candidate with a known-route backup;
for example a 512 KiB block remains a single 512 KiB exploration request.
Whole-block exploration starts its rescue two seconds plus estimated backup
transfer time before the already-advanced playback deadline (750 ms). Larger
half-block experiments keep the one-second takeover cushion. Admission uses the
same cushion so an experiment cannot be admitted after its takeover point.
The setting applies on the next playback load. Unknown indexes use the larger
of a bounded 1 MiB fallback or the selected minimum. An Android
memory-aware session budget limits reserved payload across audio and video;
planning spans remain at most 8 MiB and no larger than a permitted rescue copy.
Content-Range, length, and total size are validated before ordered delivery.
Unfinished read-ahead uses bounded coordinator capacity and shared memory admission;
completed queued pieces retain their byte ownership,
with a session-local RAM seek cache and no persistent media cache or localhost proxy. Host failures are shared within playback; throughput measurements are separate for each representation and favor recent results. Unsupported range responses
fall back to sequential HTTP at the next unread byte. Closing/replacing playback
cancels requests; ordinary range attempts have a 15-second transport timeout; indexed experiments use the media-duration budget below.

Playback deadlines use the start of the containing SIDX segment, current position,
playback speed, and a 750 ms safety margin. Playback state is sampled on Media3's
application looper, including pause, seek, and buffering events. Index collection
runs even when the visualization is hidden. Before an index is available, a
900 ms no-index hedge is additionally gated by the startup/seek readiness observation
window; it does not invent media times or bypass the two-second opportunity rule.
Ordinary requests leave one slot available for recovery; all configured budgets share one global pool.
An at-risk block can race an alternative while its original request continues;
only a fully validated winner reaches Media3. Ordinary rescue losers are cancelled
without counting cancellation as a transport failure. A deadline-triggered rescue
still reduces the original route's scheduling confidence.

Exploration is different: only the farthest eligible block in the current bounded
read-ahead window may split into two useful halves (each at least 1 MiB). The
known route serves the first half and the candidate serves the second. Admission
requires more than three seconds of deadline slack and enough time for a known
route takeover; it does not change as the window advances. At most one experiment
is outstanding. If rescue supplies the candidate's half first, playback receives
those bytes immediately while the candidate continues its measurement under the
same request limit and transport timeout. The experiment slot stays occupied until
that measurement ends. Seek/close cancels obsolete requests. Deadline misses are
counted separately from valid completions and transport failures. Candidate
reservations are spaced by at least two seconds, with ten seconds before re-probing
the same representation/host.

Exploration does not continuously rotate through healthy fresh hosts. It skips
busy hosts and prioritizes startup-only/light evidence, then measurements older
than 15 seconds, then recovery checks for penalized hosts idle for at least ten
seconds since their last meaningful measurement. A validated non-startup transfer
of at least 256 KiB or one second, or three small completions / 256 KiB accumulated
without a 15-second observation gap, supplies meaningful evidence. Ordinary
transfers refresh it, including small audio ranges, independently per representation.
Startup probes do not earn penalty recovery. Protected exploration uses the same
+0.05 confidence recovery as ordinary on-time completions, capped at 1.0, only when
its assignment was not rescued. Late retained samples update speed but not confidence.

For each block, unused measured routes that can meet its deadline form the first
choice set, selected randomly. If only busy routes can meet it, the earliest
predicted finish wins. If no route can meet it, the globally earliest measured
finish wins even if that host is already in use. Unknown routes are admitted by
startup probing or farthest-block exploration, not an urgent-block lottery.

Normal route selection estimates finish time from finalized per-block transfer rates,
weighted by media position, with the representation-local confidence penalty applied.
Full request duration includes response latency and stalls. Neither aggregate host
bandwidth nor concurrency multiplies this estimate. No individual CDN has to exceed
the whole video's bitrate: several routes can serve the required shares together.
These are scheduling estimates; actual deadlines still trigger rescue. See
[the state design](CDN-state-design.md) for seek transitions and evidence bounds.
Each deadline rescue with a fair completion opportunity multiplies representation-local
scheduling confidence with progressively stronger factors (starting at 0.75),
with a floor of 0.01; culpable actual buffering receives the stronger upgrade described below. Eligibility is captured at actual request start: remaining
deadline slack must cover the pre-dispatch completion estimate and at least one
second. Unmeasured or already-overdue startup work can still be rescued, but does
not lose confidence merely for that rescue. Duplicate rescue notifications for the same request are ignored.
Valid, on-time, unrescued completions restore 0.05; late retained measurements update
raw throughput but do not restore confidence. Short requests borrow recent route
history. Longer attempts also use whole-request elapsed throughput, including quiet
gaps, instead of a 250 ms instantaneous EWMA. Protected explorations keep their
window until estimated takeover time plus one second remains. Socket read timeout
is eight seconds. Ordinary calls and experiments without usable segment metadata
retain the fifteen-second total timeout. Indexed experiments instead have a fixed
1.5 × media-duration timeout, calculated at HTTP dispatch from the requested bytes'
fraction of each overlapping SIDX segment's duration and divided by playback speed.
This follows variable bitrate between segments; within a segment byte-to-time is
still an estimate. Distant playback slack does not extend this observation budget.
Expiry fails the measurement and cancels the socket even if another attempt has
already supplied playback. The trace records the budget and applied timeout.
Non-accelerated playback continues to honor its saved manual CDN override.

When enabled, two lanes above the playback bar show video and audio ranges:
translucent planned extents, cyan video, lavender audio, and completed green.
Transparent track icons sit in the existing left margin; video is thicker than audio.
Small grid cells encode received-byte fraction, independently of attempt outcomes.
Exploration, successful exploration, rescue/retry, failure, and cancelled exploration
are represented by thin colored underlines below the grid. Simultaneous events use
separate underline rows. No failure crosshatch or attempt color replaces a block's
actual progress, including when a failed experiment's range was delivered by rescue.
The player shows `Downloads: active/limit`, counting HTTP requests rather than
CPU threads. Positions come from MP4 SIDX entries, with interpolation inside
indexed segments explicitly labeled approximate. Without a readable index in
the first 2 MiB, the player reports that no chunk time index is available.
Exploration underlines are yellow while testing, teal once finished, and gray on cancellation.
An exploration issue adds a separate magenta underline; a takeover adds orange. Thus teal
alone means a successful probe, while teal plus magenta records an unsuccessful probe.
Orange rescue and magenta issue underlines retain their history
after the main fill changes. Unfinished backgrounds gradually turn red during the
last five seconds before the conservative segment deadline; pause freezes urgency.
The compact diagnostics show actual SIDX media-segment mean size with the retained
mean download-block size in parentheses, separately for audio and video. One shared
header labels the pair. They also show latest completed-request speed, success/needed-rescue/provided-rescue/failure counts, and
scheduling confidence. CDNs are ranked by actual request starts (descending), including retries and probes.
Route colors remain stable when ranking changes and match the charts. The short
colored block legend is next to the timeline, outside the CDN panel. Sample size/duration and cancellation counts remain in telemetry. These means use indexed media segments, not download pieces, startup probes,
exploration halves, or duplicate rescue requests.
Optional charts retain 120 one-second samples: total real traffic in white with faint
per-host curves, raw and penalty-adjusted block-speed estimates, playable buffer,
and active requests. Event ticks mark exploration, rescue and failure. Aggregate
traffic is visualization only. An overhead donut and live worker grid replace the
segment-size curve. Grid cells use existing CDN colors and track icons, with probe
and rescue underlines. Known discarded bytes are classified by purpose; uncertain
attribution remains Other rather than treating all probe/rescue traffic as waste. A separate option keeps playback controls visible;
Back still hides them. Both options default off.
Telemetry is bounded to 256 blocks and published at most ten times per second;
it is not a whole-video download history. Live playback is unchanged.

The strategy and CDN pool are inspired by
[Bilibili-thread-ripper](https://github.com/MrTangLuyao/Bilibili-thread-ripper),
reference revision `cb50803c66cf2a3dfa8253eefa09759e168fc380`. Its
[MIT notice](licenses/Bilibili-thread-ripper-MIT.txt) is retained. This native
implementation does not include browser MSE integration or the original script’s full scheduling machinery. Its protected duplicate requests and playback deadlines are native Media3 adaptations.

## Network flow

1. `Prefs.apiType` selects Web or App explicitly. `VideoPlayRepository` uses
   Web HTTP playurl endpoints or App gRPC (parallel codec requests). UGC and
   PGC convert their responses to `PlayData`; there is no Web/App auto-fallback.
2. `BiliHttpApi` uses Ktor with OkHttp, compression, JSON conversion, exception
   retries and request interception. Cookies are added before signing. UGC
   playurl includes login cookies but omits device cookies; WBI signing applies
   to WBI paths, PGC playurl, and live danmaku-info. App requests use their
   corresponding signing/token/metadata path. This fork does not change it.
3. With acceleration disabled, `PlayerViewModel.resolveMediaUrls` selects quality/codec/audio and prefers
   an official URL from base + backup candidates (excluding mcdn, szbdyd and
   IPv4 candidates where a preferred candidate exists). Its original fallback
   remains the first URL. This is selection, not a connectivity probe or a
   retry of every backup URL.
4. With acceleration disabled, `CdnOverride.apply` runs after selection, before constructing `MediaUrls`.
   It rewrites only recognized VOD media domains, excludes API/service/P2P
   hosts, and leaves invalid inputs unchanged. Domain checks use exact suffix
   boundaries. Replacement preserves scheme, raw path, raw query and fragment;
   signed query parameters are never decoded or re-encoded.
5. `ExoMediaPlayer` uses Media3's OkHttp data source, preserving API-specific
   User-Agent and Referer settings. It merges separate video/audio sources.
   API cookies are not copied into this separate media client by the override.
6. Live playback uses `LiveRepository`/`LivePlayerViewModel`, independently
   selecting its returned CDN lines and HLS/FLV sources. Subtitles, images,
   danmaku, authentication, API endpoints and gRPC channels are unaffected.
7. GitHub update checks point at `bruce55/bv_cdn`, preventing this build from
   offering an upstream binary that lacks the fork feature. A compatible fork
   release must exist for updates to be available.

## Existing upstream networking considerations

`player/OkHttpUtil.kt` trusts system CAs plus an additional bundled CA, but its
hostname verifier returns true for bilivideo.com / bilivideo.cn and their
subdomains without checking the certificate hostname. Chain verification still
runs; hostname matching is bypassed for those domains. Also, the app's network
security configuration permits cleartext and trusts user-installed CAs.
These are inherited upstream behaviors, not changes introduced by this port.

## Validation

Regression tests cover normalization, invalid destinations, host boundaries,
API/P2P exclusions, exact preservation of signed URLs, preset lookup, preference
save/clear, and the player quality-change path for both Web/App modes, including
video-only playback. On-device validation should also cover opening/canceling
the custom dialog, switching presets, restarting the app, UGC/PGC playback,
quality/audio changes and reverting to the default.

### Initial port verification

The focused run passed: six CDN utility/catalog regressions and two player CDN
regressions, plus `:app:assembleDebug`. The wider suite was stopped at the user's
request to keep testing focused. No emulator was available at that stage.

Command: `./gradlew :app:testDebugUnitTest --tests '*CdnOverrideTest' --tests '*PlayerViewModelTest*CDN*' :app:assembleDebug --max-workers=2 --no-parallel`

### Initial parallel download verification

Eleven focused transport/index/telemetry tests and three player integration
tests passed. They cover ordered range delivery, reopen after seek, malformed
responses, cancellation while sequential fallback is opening, SIDX offsets,
and preservation of original candidates across quality changes. The debug
APK built successfully. That initial run preceded the Android TV emulator checks below.

Provided-rescue counts increment only when a validated hedge wins delivery; a
failed, cancelled, or losing duplicate earns no rescue credit. Deadline urgency
retains the solid red background, distinct from magenta failure underlines. Terminal failures stop the deadline tint.

## TV release packaging

The release build is `newBV CDN` (`dev.frost819.newbv.cdn`), separate from
the emulator debug package and the previous `dev.frost819.bv.cdn` app.
It uses the existing local BV CDN release signing key via ignored
`signing.properties`; never commit signing credentials or private keys.
Run `:app:assembleRelease` with Java 17. Optional diagnostics remain available
in release builds; debug-only range logging is excluded. Future TV updates
must retain this package ID and signing key. The first installation needs its
own login/settings because it is a separate app.

The request ceiling is configurable up to 64 throughout settings, transport and
telemetry. This is an upper bound: the memory-aware session payload budget,
selected minimum block size and available work still limit actual parallelism.

Required audio/video data continues alongside startup probes, with one pending
ordinary block per reader until startup ends. Required audio waits for shared priority
admission; probes are not cancelled merely to free a worker. Urgent audio rescues are allowed. Rescue selection excludes
already-tried and cooling hosts before choosing. Ordinary starts select and reserve
centrally after acquiring request permits, using current host loads. Both ordinary
and rescue selections prefer deadline-feasible idle hosts, then feasible busy hosts;
when no measured host can finish in time, they use the earliest predicted finish.
Explicit protected exploration assignments keep their selected candidate.

If playback re-buffers after having played, the exact range currently blocking a
reader may escalate beyond its first rescue. Extra copies are added progressively
only with spare global permits, after the youngest attempt has had two seconds
to respond. Total concurrent copies for that range (original and regular rescue
included) cannot exceed min(N, max(2, floor(N/2))). Distinct unused tested
CDNs are preferred. First validated response wins and ordinary losers cancel;
protected exploration measurements remain independent. Each missed attempt is
recorded once; speed confidence is penalized only after a fair observation window,
not merely because a duplicate lost the race. Startup, user pauses and decoder-only
stalls cannot trigger this emergency path.

While playback is still advancing, a required range can ramp up distinct-CDN
rescues before its deadline. Each new copy gets two seconds of observation, and
a progressing attempt forecast to finish in time suppresses further escalation.
For eight threads, the next-copy thresholds are backup transfer time plus six,
four, then two seconds (two, three, then four total copies). Larger budgets cannot
extend this lookahead beyond backup time plus eight seconds. All copies share the
global budget and retain the half-thread per-range cap.

Repeated penalizable misses multiply confidence by 0.75, then 0.65, 0.55 and so
on down to 0.25, with a 0.01 confidence floor. A fair-opportunity request whose
already-needed range is blocking during actual rebuffering receives a one-time
upgrade to at most 0.02; subsequent culpable requests can reach 0.01. A buffering
upgrade does not double-count its deadline miss. Ahead-of-playback audio/video
ranges, startup, unfairly short opportunities and cancelled race losers are not
assigned this severe penalty. Timely unrescued completions still recover 0.05
and reduce the accumulated strike count by one.

Request occupancy and queued bytes are separate. While a required early range is
unfinished, completed later ranges no longer count against the unfinished-work
limit; the reader refills freed slots every 100 ms while waiting. Payload bytes
remain reserved until consumed, discarded, or retained exploration measurement
finishes. A full payload budget waits for capacity rather than reporting false EOF.

The initial sizing baseline (before adaptive growth and keyed recovery claims) includes
a 2× copy allowance. Its ordinary sizing target is
max(16 MiB, N × max(selected minimum, 512 KiB) × 8), capped at heap/8 and 128 MiB.
The former seek-cache allowance (heap/16, capped at 32 MiB) is pooled with that
partition into one retained/in-flight budget. Cached arrays already owned by
an ordinary reservation do not incur another charge. The heap/16, 64 MiB rescue
figure sizes protected capacity; recovery can borrow available ordinary capacity
within the adaptive total ceiling. See the state design for current admission.
Global admission enforces N actual HTTP calls.

Recovery is time-limited per CDN/representation: at most +0.05 per ten seconds,
only on a timely, unrescued, non-startup completion. Parallel completions cannot
accelerate it. The first eligible recovery may apply immediately, but going from
1–2% to full confidence still requires twenty qualifying steps spanning at least
190 seconds, assuming no further penalties.

### Playback scheduler log capture

Settings → Other → Log Viewer (`日志管理`) now offers `播放下载日志`.
This is opt-in, process-local and independent of the on-screen diagnostics.
It reuses the existing random-port HTTP server; enabling capture keeps that
server alive when returning to playback. Turning capture off stops recording,
retains the bounded final capture, and allows the server to stop when leaving
settings. Restarting the application disables capture.

The displayed LAN address serves `/logs_ui/download.html` for live filtering
and JSON export. `GET /api/download/events?after=<sequence>` returns only newer
events; `oldestSequence` identifies ring eviction and `nextSequence` is the next
unused cursor (poll using the last received event sequence). Add `download=1`
for a downloadable JSON response. The device retains up to 4096 events and
roughly 2 MiB of text; the browser also has bounded retention. Capture/export
contains host names, never signed media paths, query strings or request headers.

Events correlate sessions, readers, blocks and HTTP attempts. One-second
samples include playback state/buffer, queued completed versus unfinished work,
awaited ranges, last player read, global/ordinary free slots and payload/rescue
byte budgets. Lifecycle events include validation phase, response code, time to
headers, bytes and cancellation/rescue reasons. CDN ranking and rescue selection
report raw speed, penalty, measurement age, occupancy, remaining assigned bytes,
predicted duration, deadline feasibility and the exact fallback reason. Candidate
fields use dotted keys such as `candidates.0.confidence`. Rankings are explicitly
labelled as rankings rather than actual dispatches; `attempt_start` identifies
what ran. No scheduling rules are changed by enabling recording.

### Low-buffer capacity discovery

After startup, a blocking range under rescue/deadline pressure or actual
rebuffering can race an unused exploratory CDN without requiring a distant
range or positive deadline slack. Existing attempts continue; the duplicate
retrieves exactly the missing range, so its validated response can win playback.
Missing meaningful evidence is preferred, then stale or recovering routes.
Fresh healthy measured routes are not redundantly explored. Busy/tried/cooling
hosts are excluded, with the same 2-second global and 10-second per-representation
exploration spacing as distant exploration.

These protected measurements use only free global request slots and duplicate
byte reservations. At most max(1, floor(N/4)) can remain alive across the session;
original plus copies on one range are bounded by max(2, floor(N/2)), where two
allows a primary and one alternative on small configurations. Existing emergency
ramp caps still apply to that ramp. This does not cap total normal downloads.
A playback winner cancels ordinary losing copies, but exploratory measurements
finish or time out (seeking/closing still cancels obsolete work). New evidence is
immediately available to subsequent CDN selections. Discovery is disabled while
paused and does not replace startup probing. The trace identifies each such
launch as `recovery_exploration_dispatch` with resolver reason
`low_buffer_unmeasured` or `low_buffer_retest`.

### Dispatch latency observed on the TV

The first live log capture showed block 100 queued with 9081 ms of deadline
slack, but its first HTTP attempt began 11325 ms later. Other samples had five
free request permits and about 5.4 MiB reserved out of 16 MiB while video work
was queued. This isolates a pre-HTTP scheduling delay, independently of CDN
transfer throughput.

The dispatch hot path now skips distant-exploration planning immediately when
startup, pause, urgent work, an active exploration, or insufficient slack rules
it out. Otherwise it scans planned blocks from farthest to nearest and stops at
the first eligible candidate instead of ranking every route for every planned
block. The resolver caches immutable signed candidate strings and parsed URL
identities; dynamic health, load and deadlines still update every call. Each
ranking computes each candidate forecast once. New `coordinator_start.queueMs`,
`exploration_gate.evaluationMs`, and `cdn_dispatch_selection.selectionMs` fields
separate the stages in the next TV capture. Device-side improvement still needs
validation; the capture alone does not quantify the cost of every individual lock.

### First-block-only rescue ownership

All new rescue copies now require the currently awaited playback range, with a
single owner shared across video and audio. When both tracks await data, indexed
media time chooses the earlier range (unknown initialization ranges come first,
with reader order breaking ties). Ownership lasts until that range completes or
exits, then moves to the next blocking range. Ordinary, predeadline, buffering,
and low-buffer exploration rescues all use this same gate. A later range's late
forecast or protected-exploration status no longer bypasses it. Later ranges
continue normal downloads and failure retries. Already-running protected
measurements still finish or time out; the change prevents new rescue fan-out
on another range. `rescue_focus` trace events expose acquisition and release.

### Suffix rescues

Rescues copy a fixed published prefix from the furthest-progressing active attempt
and request only the remaining suffix. The original can continue downloading;
its later progress never changes that copy's join offset. Content-Range, total,
and exact body length are validated before a full block reaches Media3. With at
most 64 KiB remaining, no new duplicate launches. Reservations still cover a full
assembled duplicate block; CDN throughput measures only bytes actually transferred.
Prefix copying occurs only after rescue admission, and failed body references are
released. Trace attempt ranges and `prefixBytes` expose resumed transfers.

### Symmetric audio/video dispatch priority

Ordinary requests now enter a shared waiting queue before obtaining either request
permit or choosing a CDN. Each available slot prefers the track whose furthest
completed block reaches less media time, including completed blocks beyond gaps.
SIDX segment sizes and durations map each completed block end to an approximate
media-time target, interpolating within its own segment. This is
symmetric: audio behind video gets priority, and video behind audio gets priority.
A later completed video block moves audio's catch-up target forward even while an
earlier video block is still downloading, and the same rule applies in reverse.
The reader's currently awaited block takes precedence over catch-up preference.
The preferred track's queued requests retain FIFO order. Spare slots beyond the
higher-priority waiting requests are immediately available to the other track;
no request waits for an active download on the other track to finish.
Equal or unknown horizons use FIFO.
In-flight requests are not interrupted, and startup probing and rescue allowances
retain their existing policies and shared global limit.

Coordinator admission is bounded to N running tasks plus at most N waiting tasks
for an N-request configuration. Waiting work is selected again when a coordinator
finishes: current playback demand first, then the catch-up track, then FIFO.
A full queue defers the unsubmitted ranges and releases their payload reservations;
normal read/refill retries them. Cancellation removes queued tasks immediately but
keeps a running coordinator counted until its body exits. HTTP permits and CDN
assignment still happen lazily when the selected task runs. The HTTP-slot queue
also rechecks playback demand before track preference. `session_capacity` exposes
`coordinatorActive`, `coordinatorQueued`, and `coordinatorQueueLimit`; scheduler
traces report `coordinator_queue_full` when admission is deferred.

The dispatch horizon tracks validated playback winners independently of the diagnostics
UI and of Media3 consuming their bytes. It is a catch-up target, not a playable-buffer
estimate; Media3 buffering and conservative segment-start deadlines are unchanged.
A seek resets the horizon and uses an epoch token to ignore old completions.
`ordinary_queued` and `track_dispatch` log queue admission, selected/preferred track,
`videoHorizonMs`/`audioHorizonMs`, and the fallback
reason. Neither track is held at a lead threshold: the former audio delivery-horizon
gate and its one-block demand exception have been removed. Both tracks can stage
work within their existing reader and memory windows. Priority chooses work when
slots are scarce; it never waits for an in-flight block on the other track to finish.

Progress display size is independent of network block size. The visualization
settings offer Compact / Normal / Large / Extra large (24 / 36 / 48 / 72 dp for the two download lanes,
default Normal). Lane heights, grid cells, icons, and event underline thicknesses
scale together; video remains thicker than audio and deadline urgency remains a
background tint. The setting is available when download-block visualization is on.

### Seeking and session RAM reuse

Before Media3 seeks, the session maps the target to SIDX byte ranges. It keeps
unfinished requests that overlap the new forward window, including one preceding
segment when the byte budget permits decoder lookback, and cancels obsolete work.
The preliminary seek hint expires after three seconds; the reopened reader narrows
retention to its actual byte position and requested length. Without an index, only
completed-cache reuse is available. Seeking inside Media3's own sample buffer can
avoid reopening the transport entirely.

Validated completed blocks survive reader closure in session-local storage sharing
the ordinary download watermark. Arrays are shared without copying and pinned while
read. During normal playback only fully delivered, unpinned entries are reclaimable;
unread upcoming data cannot be displaced by later completions. Explicit seeks may
abandon old data under pressure, while new reader acquisitions restore protection.
Representation identity and exact byte ranges keep video, audio and quality bodies
separate. Changing the playback session clears the cache. Invalid bodies never enter it.

Reopened readers can start partway through a cached or retained block. Missing
gaps alone generate new requests, and cached/network pieces are delivered in byte
order. In-flight ownership remains with the original transport job across repeated
seeks. Terminal callbacks release abandoned ownership on both success and failure;
failed adopted work retries normally. A detached reader cannot refill its old queue after seek handover. When new partitions split a retained future, prefetch adopts its remaining requested range once and skips all covered new pieces, avoiding duplicate suffix downloads. Reused completions contribute to the new
track catch-up horizon, including future blocks beyond gaps. Cancellation remains
distinct from a CDN failure.

Two running/two queued head coordinators let required opens survive loader
replacement without competing with speculative coordinator admission. They share
the same HTTP permits and byte limits as other work, so they do not increase the
configured network concurrency. Each track's retention window is bounded by its
share of the ordinary payload allowance.

Trace events `seek_window`, `seek_reader_retained`, `seek_retained_cancel`,
`seek_reuse_inflight`, `seek_reuse_wait`, `seek_retained_retry`,
`seek_retained_failed`, and `cache_hit` expose the decisions. `session_capacity`
adds `cacheBytes`, `cacheLimit`, `cacheEntries`, `headCoordinatorActive`, and
`headCoordinatorQueued` to the existing reservation and worker counters.

### Local availability on the progress bar

Green lane regions represent current local availability, combining validated bytes
still held by the session cache/queued readers with Media3's reported forward buffer.
The sources share one appearance. Completed request history alone never paints green;
exploration, rescue and failure history remains in the separate underlines.

The monitor samples cache metadata outside its own lock when publishing. Byte ranges
are mapped through the selected track's SIDX and unioned without filling holes. Missing
indexes never produce guessed cache times. Media3's current-to-buffered interval is
already expressed in time and applies to the selected tracks even without an index.
This is conservative: the current player has no configured back buffer, so previously
played regions are only shown as available while the downloader still retains them.

Playback samples refresh every 250 ms and visualization updates coalesce for 100 ms.
Evicted data loses its green region on the next refresh unless Media3 still holds it.
Seeking clears the old Media3 claim and samples the new position/buffer; changing the
session clears both sources. The legacy zero-to-buffered gray line is suppressed when
these lanes are shown, because it incorrectly spans holes after seeking. Green describes
local data, not a guarantee that decoder restart after a seek takes zero time.

### Pixel-aligned download display

The lane raster uses physical-pixel columns shared across the whole timeline,
with four progress rows for video and two for audio. Block boundaries no longer
choose their own cell widths, gaps, or alternating background shades. Fractional
horizontal coverage contributes to the containing pixel instead of rounding each
thin block to a different width. Locally available regions override in-flight
progress; exploration, rescue and failure remain in separate underline rows.

The player screen samples the latest download snapshot every 100 ms for the lanes,
thread counter, CDN list and charts. Intermediate telemetry is skipped, not queued.
The raster is cached between updates so unrelated playback-position draws do not
recalculate it. Scheduling, deadlines and rescues are independent of this 10 FPS
presentation limit. Overall lane heights and icons still follow the app density
and Compact/Normal/Large/Extra large setting; raster columns remain physical-pixel aligned.

Download availability fills use 40% opacity so the video stays visible behind the lanes.
The playback position line sits between the video lane (including its event underlines)
and the audio lane. Media3 availability remains green in the lanes, not a separate
zero-to-buffered line. The legend is compact: 探测 (yellow/teal), 补救, 异常, 取消,
plus the red deadline tint. A failed attempt does not imply locally available media
became unavailable.

### Transfer accounting and compact diagnostics

The compact UI and overhead donut use exact received-copy accounting when the session provenance sampler is attached. Approximate labels apply only to the legacy file-range fallback without that sampler. Cache replay does not increase used bytes again; a useful new download after eviction does. Raw observations are logged independently. See [transfer accounting](CDN-transfer-accounting.md) for ownership transitions and fields. Media3 releasing delivered data is not download overhead.

Diagnostic icons share one vector size and stroke. Charts show latest values and Ø, the arithmetic mean of available samples in the visible history window; missing measurements are excluded and valid zeros retained. Traffic summaries include all routes, even those beyond the six plotted. Raw and adjusted speed summaries average measured routes per sample, then average those values over the window. Video and audio segment sizes remain in the compact CDN summary, not a separate chart.

When all candidate CDNs are cooling down, one session-wide ordinary request for current playback demand can bypass cooldown. Speculative tasks wait without taking HTTP slots; rescues and exploration still respect cooldown. Failure imposes a one-second recovery-admission backoff; cancellation or completion releases admission. Existing deadline and speed-penalty policies still apply.

Diagnostic panels use the measured space between the title and playback controls. Each panel is measured at its natural UI-scaled size, then uniformly reduced to fit both its share of the width and the measured free height. Long titles and taller controls therefore shrink the complete panel, including icons and charts, instead of hiding lower rows in a scroll area. Panels never grow beyond their natural size or overlap the progress bar.

Compact diagnostics place video/audio sizes on one row and all transfer counters on one row, with binary K/M/G abbreviations. Chart titles share a row with current and mean values; the range is drawn inside the plot. Summary rows can scroll horizontally on exceptionally narrow screens rather than consuming extra vertical space.

User settings default to 512 KiB minimum download blocks; automatic sizing is no longer offered, and saved zero/automatic values resolve to 512 KiB. Short media segments and startup probes remain exceptions. Progress sizes are Compact 1×, Normal 1.5× (the former Large), Large 2×, and Extra large 3×.

Progress icons retain a 24 dp outer screen margin plus a size-aware gutter. Expanded touch seeking measures only the inset bar; persistent bars reserve their own safe outer margins.

### Memory-pressure logging

The existing opt-in playback download logger also records memory while using either ordinary or parallel playback. One IO sampler runs at most every five seconds (no overlapping samples). `memory_sample` includes device available/total RAM and low-memory threshold, Java heap used/committed/maximum, process PSS/dirty pages, native heap and Android-accounted graphics/stack categories where available. Decoder names, video format, output surface dimensions and timestamped playback buffer duration help correlate 4K/HDR changes. `memory_trim` records OS callbacks; Android 11+ also provides `memory_exit_history` for the latest process exit reason.

Media3 uses the size-first timing policy with the coordinated byte ceiling below. The measured load control reports actual **in-use** sample allocator bytes, allocation granularity and the selected-track target. In-use allocator bytes exclude the allocator's free pool, decoder and surface buffers. Neither PSS nor Android's graphics category completely accounts for every vendor decoder, DMA buffer or TV post-processing allocation. A drop in device available RAM with stable app metrics is evidence to investigate, not an exact measurement of other apps' ownership.

`session_capacity.storage` counts known downloader backing arrays by identity without copying bodies. `knownBytes` deduplicates shared arrays; cache/reader/attempt and audio/video categories overlap and must not be added together. Reservations and limits remain separate fields. Transient join copies and framework/network allocations are explicitly excluded from this sampled inventory.

Memory events and five-second capacity samples are saved off-thread to two rotating JSONL files (at most 2 MiB total), with a bounded writer queue. They survive process death and can be fetched from `/api/download/memory` or the download-log page after reopening the log server. Recording is process-local and opt-in; previous captures remain readable when recording is off. Abrupt kills can still lose an in-flight sample; dropped queued records are reported in the download response header. No buffering limits or scheduling behavior change as part of this instrumentation.

### Coordinated playback buffer budget

The adaptive policy in [CDN-state-design.md](CDN-state-design.md#adaptive-memory-budget-current-policy) supersedes the fixed admission headroom and fixed partition ceilings described below; these remain the initialization baseline.

The application requests Android's large heap; sizing uses the actual VM maximum, never the
advertised large-memory class. One shared partition policy caps Media3's selected-track target
at one quarter of the heap (128 MiB ceiling), ordinary downloads at one eighth (128 MiB),
duplicate rescues at one sixteenth (64 MiB), and the seek cache at one sixteenth (32 MiB).
Concurrency and block size may further reduce downloader allowances. Combined targets use at
most half the heap, leaving room for UI, networking, temporary copies and garbage collection.
These are buffer targets, not a hard bound on every live allocation or native decoder memory.
Media3 retains its 50-second timing goal and size-first behavior; it stops loading at the byte
target even if that duration has not been reached. Memory logs expose the Media3 and combined
budget ceilings. A 192 MiB VM gets a 48 MiB Media3 ceiling; a 512 MiB VM gets 128 MiB.

Reservations now charge twice each range's byte length, covering split joins and immutable rescue
prefix/suffix copies inside the same ceilings. Requested allowance sizing includes this headroom;
heap ceilings still take precedence. With a 512 MiB heap and 64 workers, the combined
96 MiB ordinary/cache watermark can cover at most twelve 4 MiB copy-inclusive
reservations before demand headroom or existing retained data reduces admission. Configured block sizes remain
unchanged except where existing small-heap planning limits require smaller ranges. Cancellation
keeps ordinary reservations until socket workers exit; completed duplicate results remain charged
until collected or discarded. Fully consumed reader blocks drop their references immediately.

### Shared cache ownership and central admission

One shared admission budget covers copy-inclusive ordinary reservations plus cache-only
backing arrays. Identity-based ownership avoids charging a body again when a reader
also inserts it into cache. Releasing its last ordinary owner leaves the cached body
charged; completion or reader/cache handoff never makes retained bytes disappear from
accounting. Socket cancellation retains its reservation until the worker exits.

A new request first reserves its complete bounded allocation. Under pressure only
fully delivered or explicitly seek-abandoned, unpinned cache entries are reclaimed.
If that cannot provide capacity, admission waits. Delivery is successful DataSource
read interval coverage, never a reader-position or playhead comparison. Partially
delivered arrays stay protected until their unread portion is delivered or abandoned.
Speculative work leaves copy-inclusive room for one maximum planning span per track;
required demand can use that headroom. Recovery claims protect capacity from ordinary
refill; duplicate rescues and recovery originals can borrow otherwise free capacity
within the adaptive total ceiling. Rescue protection is not a separate hard ceiling.

Central admission selects immediate missing demand, then the track with the lower
prepared media-time horizon, including scheduled blocks beyond holes. There is no
second ten-second window or completed-block-count ceiling. Execution queues remain
bounded, downstream dispatch respects urgent work then admission order, and CDN
selection stays lazy. All HTTP work shares one priority admission controller. Ordinary work and probes use
at most N−1 slots; eligible recovery work has first priority for free slots. Coordinator capacity notifications trigger
a coalesced asynchronous refill without nesting reader locks in completion callbacks.

Explicit seeks make abandoned old data reclaimable, retain useful in-flight work,
and acquire cached new-target ranges before network admission. A completion racing
seek/close is marked abandoned unless the new reader has acquired it. Cache eviction
alone may not free an allocation still owned by a live reader, so only ownership
release changes the shared budget.

`session_capacity` and scheduler events expose `sharedUsedBytes`, `sharedLimitBytes`,
and `cacheOnlyBytes`; capacity snapshots also expose protected, evictable, and pinned
cache bytes. `shared_byte_watermark` distinguishes memory backpressure from worker
or queue occupancy. `cache_reclaim` reports removed cache-reference bytes, which may
still have another owner; it does not claim those bytes were freed from the heap.

Green progress lanes union downloader residency, Media3's confirmed shared forward
buffer, and current-reader per-track delivery estimates mapped through SIDX. This
keeps the audio lead visible after the downloader releases its copy. Estimates are
clipped at the playhead and invalidated on seek, reader replacement, or invalid
Media3 buffering; cumulative historical downloads never imply current residency.

### Persistent buffering reports

Cache settings has an opt-in 卡顿日志 preference (off by default, persisted across restarts).
It enables the bounded redacted scheduler ring independently of the live HTTP logging toggle.
A play-when-ready buffering state lasting three seconds saves the recent ring beside crash logs;
the same incident is refreshed every ten seconds and once on recovery/pause. Startup and seek
waits are included, with the surrounding playback events identifying their context.
Reports contain Media3 loading decisions and allocator usage, player state, scheduler/admission,
CDN choices, rescues and memory events; no media bodies are saved. Disk work is asynchronous,
with one pending snapshot, atomic replacement, and five retained incident files. Log Management
and its HTTP page list/download them; Cache settings' Clear logs removes them.


### Rescue admission and memory recovery

One HTTP admission controller orders recovery, immediate reader demand, ordinary work, then
extra probes/exploration. CDN/memory-ineligible requests do not hold sockets or obstruct eligible
work. Slot leases remain owned until request exit, including cancellation. No request is cancelled
merely to free a socket. Ordinary/probe/exploration occupancy is at most N−1.

Normal rescue adds one duplicate; pre-deadline and buffering escalation share a single cap of
max(2, floor(N/2)) attempts including the original, bounded by N. Discovery rescues share the
same observation delay rather than bypassing the staircase. Only the first blocking range owns
rescue focus. Existing CDN selection, penalty/recovery, suffix validation and 64 KiB tail wait remain.

A missing demanded range can borrow recovery memory, while a keyed pending claim protects its
space from ordinary refill. Claims are promises, not allocations: actual occupied bytes determine
which recovery fits, atomically, so multiple claims cannot deadlock one another. Recovery may use
free ordinary capacity within the adaptive total ceiling. When a slot opportunity and CDN exist but
memory is short, reclaim delivered/abandoned data, retire far-future pending work, then evict unpinned
far-future unread cache if necessary. This is an explicit exception to normal unread-cache protection.
Cancelled allocations stay charged until their final users exit. Retired ranges are restored in byte
order; reads never skip gaps; revoked completions cannot reinsert evicted data. Reclamation is for
one upcoming attempt, never the entire emergency cap up front.

A demanded read stuck on memory for 30 seconds throws DownloadCapacityException through existing
Media3 error handling. There is no automatic player reset. Buffering captures distinguish slot,
memory and CDN waits and retain Media3's actual loading decision for separate diagnosis.

### Exact copy accounting and independent transfer observations

`session_capacity.transferMeasurements` records independent HTTP body bytes read,
total bytes delivered to Media3 (including replay), session-unique delivered file
ranges, validated local ranges, received in-flight ranges, their deduplicated union,
and finalized transport-discard events. These are overlapping counters, not terms
of a conservation equation. Local range counts are logical payload lengths, not
heap allocation; the existing storage/budget sample measures backing arrays and
reserved capacity separately. Media3 allocator usage and buffer duration remain
separate player measurements, not downloader pending bytes or disposal counters.

Clearing Media3 residency, consuming samples, or seeking never increments transport
discard. A successful handoff is useful delivery even when Media3 later discards it.
Confirmed transport discards exclude later cache eviction and copied rescue prefixes
whose final disposition is not yet known. They are a lower bound on unused traffic.
The exact `session_capacity.transferAccounting` partition is separate from these raw
observations. `ExactTransferLedger` retains per-response interval provenance through
rescue stitching, cache sharing and delivery. A source owns active received bytes;
result/cache handles preserve copies after source completion. Final owner release
classifies only never-delivered bytes as discarded, including unused cache eviction.
Sequential fallback records direct downloaded-and-used bytes without retained
per-read handles. Downloaded equals used plus pending plus discarded in each ledger
snapshot. `liveSources` and `liveRanges` expose remaining metadata, not payload RAM.

The display consumes exact counters when its sampler is attached and sets
`DownloadTransferStats.exact`; only the sampler-free fallback retains estimate labels.
Existing `DownloadOverheadKind` categories are exclusive final attribution, with
source finish reason used when a handle closes as `Other`. Separating request purpose
from disposal cause would improve category detail, but exact byte totals no longer
rely on file-position unions or inferred cache loss. Do not add overlapping raw
observations or `AttemptAccounting` totals to this partition.
