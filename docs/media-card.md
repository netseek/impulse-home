# MEDIA card — now playing, transport, and the app behind it

Replaces a rail tile whose body opened the widget picker, a widget with no
builder of its own, and a popup that did not exist. The three surfaces now come
out of one builder, the way `docs/ui-surfaces.md` recommends and `DRIVING` does
it: `_focusedCardRenderFields` re-exports every key of `_mediaWidgetView` whose
name starts with the card type, so `mediaTitle` reaches the popup markup as
`{{ focusedMediaTitle }}` with no second code path, and `_syncDockIndicators`
reads the same object for the rail payload.

## What is connected

| Field | Source | Evidence level |
| --- | --- | --- |
| title / artist / album | `MediaNowPlaying` → MediaSession, MediaCenter or CarPlay | Bridge-supported |
| durationMs / positionMs / playing | same | Bridge-supported |
| appLabel / packageName | `PackageManager.getApplicationLabel` | Bridge-supported |
| artDataUrl | session artwork, remote thumbnail, or search fallback | Bridge-supported |
| **appIcon** | `PackageManager.getApplicationIcon`, cached per package | Bridge-supported (added here) |
| **canLaunch** | `PackageManager.getLaunchIntentForPackage(pkg) != null` | Bridge-supported (added here) |
| needsListener | notification-listener access + connection | Bridge-supported |
| prev / playPause / next | `MediaBridge` | Round-trip verified on the emulator against a live YouTube session |

`appIcon` and `canLaunch` are the only two fields this work added, and both are
answered by `PackageManager` rather than inferred. **No telemetry contract was
touched** — media is not vehicle data and does not go through
`TelemetryBridge`.

## Only three commands exist, so only three are offered

`MediaBridge` exposes `prev()`, `playPause()`, `next()` (plus `onViewerReady`
and `dismissSlot`, which are not transport). There is deliberately **no seek,
shuffle, repeat or queue** anywhere on this card: the bridge has none of them,
and a control that cannot reach the player is worse than no control at all. The
progress bar is a readout — `role="progressbar"`, no click handler — and the
contract test fails if one is added.

## Source vocabulary

Media is the one card with **no DEMO state**, because nothing about it is ever
synthesised: there is no media equivalent of `_graphDemoValue()`. The builder
contains no `DEMO` string at all and the contract test asserts it stays that
way, so the "full badge or nothing" rule in `docs/widget-data-audit.md` cannot
be violated by abbreviation here.

| State | Badge | When |
| --- | --- | --- |
| access | `MEDIA ACCESS REQUIRED` | notification-listener access missing or disconnected |
| no bridge | `MEDIA UNAVAILABLE · NO MEDIA BRIDGE` | no `window.MediaBridge` (browser preview) |
| idle | `MEDIA · NO TRACK` | bridge present, nothing playing |
| playing | `<APP> · NOW PLAYING` | a track, and `playing` is true |
| paused | `<APP> · PAUSED` | a track, and `playing` is false |

The first three strings are fixed by the widget data audit; the first two are
verbatim from it. Two things changed:

- **`NOW PLAYING` used to be shown over a paused session.** `playing` is
  published on every update, so `PAUSED` is a reading, not a guess.
- **A browser with no MediaBridge used to say `MEDIA · NO TRACK`**, which
  implies we looked and there was none. It now says the bridge is missing.

`mediaControlsDisabled` is `!(mediaHasTrack && window.MediaBridge)` — unchanged,
and `_mediaTransport` re-checks both before touching the bridge rather than
trusting the `disabled` attribute.

## Gestures

| Surface | Gesture | What it does |
| --- | --- | --- |
| rail card | tap the body | opens the **playing app** |
| rail card | hold | opens the MEDIA popup |
| rail card | tap a transport button | prev / play-pause / next |
| widget | tap the body | opens the **playing app** |
| widget | hold → `OPEN` | opens the MEDIA popup |
| popup | `OPEN <APP>` | opens the playing app |

The body opens the player because that is what a driver wants from a
now-playing card. **It falls back to the popup when there is nothing to open**,
on both surfaces, so the gesture always answers instead of doing nothing — and
"nothing to open" is a real state: a projection source can publish a track from
a package with no launcher entry, and `launchAppFullscreen` returns silently
there. That is what `canLaunch` is for; the popup's OPEN button is disabled and
reads `NO APP TO OPEN` in the same case.

**The rail card is clickable unconditionally**, even with nothing to open. A
non-clickable native View does not consume its touch, and the 3D canvas sits
directly underneath the rail — an unclickable media card sent taps through to
the scene and orbited the camera.

## The card keeps its own native builder

`makeQuickMediaCard` is the one rail tile that is **not** `makeQuickVisualCard`,
and it stays that way deliberately: it is the only card whose useful action is a
command rather than a reading, and folding it into the generic card would cost
the three transport buttons to gain a shape it does not want. MediaCenter, the
OEM reference in `docs/oem-apk-can-reference.md`, puts transport on its rail
surface too.

What it borrowed back from the generic card is the part that was missing: a body
that runs a command, and a long press.

Two things that bit here, both invisible on a dark screen:

- **The app chip wore a dock plate**, whose light fill swallowed its label.
  It does not need a ground of its own — it sits beside a heading that is
  already legible on that card, so it borrows the same `frostSecondary` tinting.
  Setting the colour in the payload path as well meant the two disagreed and
  whichever ran last won.
- **The artist line fell back to the app name.** With the chip naming the app,
  that was the same fact twice, and it read as an artist called "YouTube". It
  falls back to the album now, and hides when there is neither.

**A rail rebuild used to blank the card.** `populateQuickCardsRow` drops every
media view and builds fresh ones, and an accent change rebuilds the whole row —
so the card reverted to "Nothing playing" with its transport greyed out and
stayed that way until the player happened to publish an update.
`lastMediaPayload` / `replayMediaPayload()` fix it, in both branches of the
builder. Same class of bug as the driving wash that `refreshQuickCardsTheme` has
to carry: state baked into a card at build time disappears silently on the next
rebuild.

## Layout

`1x1`, `1x2`, `2x1`, `2x2`, `3x1`, `3x2`. `1x1` is new.

Two layout families, picked by slot shape rather than a per-size branch of the
data:

- **`cover`** (1x1, 1x2, 2x2, 3x2) — the artwork fills the slot and the copy
  sits over it on a scrim.
- **`split`** (2x1, 3x1) — the artwork is a full-height square bled to the
  card's own edges, with the copy in the middle and the transport as its own
  column on the trailing edge. A full-bleed image on a short wide slot leaves
  the title nowhere to go.

Only spacing and type scale are per size; the field set is not. What size does
decide:

- **1x1 drops the artist line** and shows the app chip as an icon with no name.
  A 197x176 tile cannot hold five lines plus a button, and the title and the
  icon already identify the track.
- **1x1 drops prev/next.** They need a precise tap; play/pause is the one
  control worth having at that size.

**The split artwork is capped at 36% of the card width.** A "2x1" is not a fixed
number of pixels — on a narrow board it is ~400px wide, where a full-height
square art left the copy column too thin for the source line, which truncated to
`YOUTUBE · _`. WebView 91 has no container queries, so the cap is the lever.

### Colour rule: the scrim is keyed on artwork, not on the theme

The copy goes white-on-scrim only when there IS art under it
(`.hv-media.cover.has-art`). With no artwork the card is an ordinary themed
frost surface. The previous card forced `.hv-media-chrome { color: #f4f7fb }` in
**both** themes, which painted white text on a near-white light board whenever a
player published no cover.

Three details that came out of using it:

- **The placeholder disc is hidden in `cover`.** The art layer IS the card
  there, so a 44%-of-card disc lands squarely on the title and the progress bar.
  In `split` it has its own square plate and stays.
- **The scrim ramp has to reach near-opaque well before the copy starts**,
  because the copy climbs as the slot shrinks: on a 1x1 the source line sits
  halfway up the card, over whatever the cover has there. The 1x1 and 1x2 ramps
  are tighter still.
- **A disabled play button needs more than opacity.** At 32% a saturated accent
  still reads brighter than the two inert rings beside it, so a card with no
  track looked as though play was the one thing that would work. Disabled
  play drops the accent fill entirely.

Colours resolve through `--hv-widget-fg`, `--hv-widget-muted`, `--hv-accent`,
`--hv-frost-edge` and `--hv-frost-inner`, so the card works on both boards and
honours the configured accent. The contract test fails if a media rule
hard-codes `rgba(255,255,255,…)` for text, border or fill — with one licensed
exception, anything scoped to `.cover.has-art`, where the ground is the artwork
rather than the board.

## The position tick must never reach setState

`componentDidUpdate` asks for a 3D frame after every `setState`, and the player
publishes a position roughly every second. `_syncMediaPositionDom` pokes the
painted nodes instead — and there are now two per surface (widget and popup),
which is why it is `querySelectorAll` and class-addressed rather than by id.
`applyMediaPosition` only reaches `setState` on a drift of 400 ms or more, so
the React copy stays in step for re-renders without firing at the tick rate.
The contract test guards both.

## The popup

`focusedCardIsMedia`, one builder, one block of markup. It sizes to its content
(`.fit`, like DRIVING) and takes a narrower frame than the shared 760px
(`.narrow`): media carries art, four lines and three buttons, and the shared
width left a third of the dialog empty.

The title is clamped to two lines with `-webkit-line-clamp`. A video title runs
long and `overflow: hidden` alone guillotines the second line mid-word.

## Screenshots

`docs/media-card/`, captured on the Haval emulator at 1920x720 through
`scripts/device-cdp.mjs serve`, against a **live YouTube MediaSession** (the
emulator has no OEM media source, so a real player was driven to produce one):

| File | What |
| --- | --- |
| `media-dark-1x1-1x2-2x2.png` | dark, cover layouts |
| `media-dark-2x1-3x1.png` | dark, split layouts |
| `media-light-2x1-3x1.png` | light, split layouts |
| `media-dark-popup.png` | dark popup |
| `media-light-popup.png` | light popup |
| `media-accent-coral.png` | accent `#ff866e` on every surface |
| `media-no-track.png` | idle — `MEDIA · NO TRACK`, transport inert |
| `media-access-required-popup.png` | notification access missing |

The rail card was verified separately against the same session: the body opened
YouTube (`mCurrentFocus` became the YouTube activity), a long press opened the
MEDIA popup, and the chip drew YouTube's real icon and label.

## Known limitations

1. **No OEM source was exercised.** MediaCenter (Android Auto / USB) and
   CarPlay bind to OEM services that do not exist on the emulator, so every
   capture here came from a MediaSession. `appIcon` and `canLaunch` resolve the
   package those sources report, and the projection packages are exactly the
   ones most likely to have no launcher entry — which is why the tap falls back
   to the popup rather than failing silently. **Verify on the car** that
   `com.beantechs.mediacenter` and `com.ts.carplay` report the icon and
   launchability you expect.
2. **A very long title still ellipsises on a narrow slot.** A 400px `2x1`
   holding art, an app chip, five text lines and three buttons is
   over-subscribed by a YouTube-length title. Nothing overlaps or overflows —
   the measurement is in the commit — but the title is cut.
3. **The app icon is re-encoded per package, not per launch.** The cache holds
   eight and is cleared wholesale when it fills; a head unit cycling through
   more than eight players would re-encode. Not measured on the car.
4. **`dismissSlot` is untouched.** The freeform media-app slot
   (`_dismissRightMediaForPanel`) is a separate mechanism from this card and was
   left exactly as it was.
