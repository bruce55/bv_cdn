# CDN fork maintenance

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
The upstream official-CDN preference is always active; its old toggle is no
longer needed. Live streams retain upstream's separate line selection.

The new application has a different package ID from legacy BV, so Android does
not automatically transfer the old app's settings/login. Configure the CDN in
the new app. The preset list was carried forward; listing a node does not prove
that it remains reachable or serves every video in every region.

## Parallel VOD downloading (optional)

Settings → Other → 播放源地区覆盖 also contains separate switches for parallel
downloading and download visualization. Both default to off and apply on the
next playback load. Mainland/overseas automatic routing uses a small candidate
pool plus the original signed base/backup URLs. A saved manual override pins
the destination. Clearing the override restores automatic routing. The shared
video/audio request budget is 4 or 8; retries use the same budget.

The Media3 data source requests bounded 256 KiB blocks, validates Content-Range,
length and total size, and delivers them in byte order. Read-ahead is limited
to four blocks per reader, with no persistent cache or localhost proxy. Route
health and throughput are scoped to playback. Unsupported range responses
fall back to sequential HTTP at the next unread byte. Closing/replacing playback
cancels requests; raw range attempts have a 15-second deadline.

When enabled, two lanes above the playback bar show video and audio ranges:
planned outlines, active cyan, completed green, retries amber and failed red.
The player shows `Downloads: active/limit`, counting HTTP requests rather than
CPU threads. Positions come from MP4 SIDX entries, with interpolation inside
indexed segments explicitly labeled approximate. Without a readable index in
the first 2 MiB, the player reports that no chunk time index is available.
Telemetry is bounded to 256 blocks and published at most ten times per second;
it is not a whole-video download history. Live playback is unchanged.

The strategy and CDN pool are inspired by
[Bilibili-thread-ripper](https://github.com/MrTangLuyao/Bilibili-thread-ripper),
reference revision `cb50803c66cf2a3dfa8253eefa09759e168fc380`. Its
[MIT notice](licenses/Bilibili-thread-ripper-MIT.txt) is retained. This native
implementation does not include browser MSE integration, speculative hedging,
or the original script’s full scheduling machinery.

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

### Verification result

The focused run passed: six CDN utility/catalog regressions and two player CDN
regressions, plus `:app:assembleDebug`. The wider suite was stopped at the user's
request to keep testing focused. No device or emulator was available, so
on-device UI and real CDN playback remain unverified.

Command: `./gradlew :app:testDebugUnitTest --tests '*CdnOverrideTest' --tests '*PlayerViewModelTest*CDN*' :app:assembleDebug --max-workers=2 --no-parallel`

### Parallel download verification

Eleven focused transport/index/telemetry tests and three player integration
tests passed. They cover ordered range delivery, reopen after seek, malformed
responses, cancellation while sequential fallback is opening, SIDX offsets,
and preservation of original candidates across quality changes. The debug
APK builds successfully. No physical device/emulator was available; actual
CDN behavior and TV focus/rendering remain to be checked on hardware.
