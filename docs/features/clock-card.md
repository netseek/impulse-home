# Clock

The clock is a widget on the board and a card on the native bottom rail. Both show the same four
faces, drawn from one source, and both read the head unit's wall time: no network, no vehicle
signal and no demo data are involved, so the clock works offline and in demo mode.

## Faces

Each face is a designed composition, not a menu of dial/date/digital toggles. The code key is stable
and stored in settings; the name is what the user sees.

| Key | Name | Details it exposes |
|---|---|---|
| `panorama` | Orbit | seconds sweep on the rail card |
| `meridian` | Chronograph | dial marks: `index` or `plain` |
| `split` | Monogram | plates: `frost` or `flat` |
| `date-spine` | Dashboard | date as `month-name` or `numeric` |

The rail's default face is `panorama`; a widget's default is `meridian`. Older widget styles map onto
these: `twin` and `atelier` to `meridian`, `signal` to `panorama`, `datebook` to `date-spine`.
Unknown values fall back to the default without overwriting a valid saved setting.

## Settings

Stored per desktop and normalised by `assets/clock-core.js`.

```json
{ "bottomClock": { "version": 2, "face": "panorama", "hourFormat": "system",
                   "dialMarks": "index", "splitPlates": "frost",
                   "dateSpineFormat": "month-name", "dateWording": "short" } }
```

A widget keeps a smaller `{ version: 1, style, hourFormat }`. `hourFormat` is `system`, `24` or `12`;
`dateWording` is `short` or `long`. Unknown or corrupt values are normalised, never thrown.

Tapping the clock card on the rail, or choosing Clock in Desktop Studio, opens the Studio **Clock**
tab (`openClockSettings`, handled by `_openClockSettings`): a gallery of the faces with the format and
the selected face's own detail controls. A change is saved at once for the active desktop, and the
tab can be scoped to the widget or to the bottom card.

## How it is drawn

- `assets/clock-faces.js` renders a complete SVG face for the rail and for each widget size (`rail`,
  `1x1`, `1x2`, `2x1`, `2x2`). **Native displays a rasterization of that SVG; it never draws its own
  approximation.** One geometry, one place to change it.
- `assets/clock-elements.js` is the custom element that hosts the web faces; `assets/clock-core.js`
  holds the configuration rules and the time formatting.
- **The seconds sweep is the one exception, and it is still the same geometry.** Re-encoding the
  whole face every second cost the main thread 25-160 ms a second on the car, a 1 Hz hitch in the 3D
  view. The card is therefore rasterized once a minute with the sweep omitted, and native strokes the
  exact path that `sweep()` describes.

## Time rules

- Take one wall-clock snapshot per refresh and derive every digit, the date and the hands from it;
  never count frames or increment a stored minute. Hands use the local components
  (minute `m x 6` degrees, hour `(h mod 12) x 30 + m x 0.5` degrees), so both jump at the minute
  boundary and 59 to 00 never takes the long way round.
- 24h runs 00:00-23:59. 12h shows 12:00 AM at midnight and 12:00 PM at noon with a day period.
  Locale and timezone follow the device; a missing timezone name hides that label, it does not make
  the time unavailable.
- If a platform time or format call really fails, show `—:—`, never a plausible frozen time.

## Performance rules

The clock lives on a main thread that is already the bottleneck, so a tick must stay local.

- A tick must not call a parent `setState`, `requestRender`, `_onResize`, a shadow refresh or a
  post-FX invalidation. No rAF loop, no 1 Hz interval for the face, no CSS keyframe sweep, no blinking
  colon and no WebGL clock.
- One scheduler per visible WebView, aimed at the next minute boundary, cancelled when no clock is
  visible (hidden document, other desktop, scrolled-off card) and refreshed when it becomes visible
  again or when the time, timezone, locale or hour format changes.
- Static dial paths are built on mount and on style, size or theme changes only; formatters are
  cached. No blur inside a clock and no per-hand compositing layer.
- The native card works with no web clock on screen: a clock-only view is refreshed from one native
  snapshot, and the page payload must not overwrite it with a stale string.

## Tests

`scripts/test-clock-face-labels.mjs` and `scripts/test-clock-sweep.mjs`. Layout and function can be
checked on the emulator; performance has to be measured on the car
([measuring-and-performance](../engineering/measuring-and-performance.md)).
