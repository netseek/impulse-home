# UI surfaces: card, widget, popup

Three different things in this app are all loosely called "the card", and the
confusion is expensive: a change verified on one surface can be completely
broken on another. This is the vocabulary. Use these words in commits, issues
and agent briefs.

| Term | Where it lives | Who draws it | Sizes |
| --- | --- | --- | --- |
| **card** | the launcher rail along the bottom | **native Android** (`MainActivity`) | fixed rail tile |
| **widget** | the one full-size widget board, beside the car | the WebView | grid cells (1x1 … 3x2) |
| **popup** | floating over everything, dismissed by a backdrop tap | the WebView | one shared frame |

## Readability

Authored labels use short PT-BR; keep DEMO, unavailable, partial and stale provenance explicit.
Web surfaces use opaque `--hv-text-*` colors and footprint `--hv-fp-*` sizes, at least 18px;
primary text targets 7:1 contrast and secondary text 4.5:1 over a readable local panel.
Native labels use at least 18sp; Canvas converts that floor with cached `scaledDensity`.
Light muted text uses `#526171`. Do not add per-frame typography work or hide state through opacity.

## card — the bottom rail

A small native tile in the launcher rail. **It is not HTML.** It is built in
`makeQuickVisualCard()` and painted by `QuickCardGraphicView.onDraw()`, which
switches on the card id; anything with no `case` falls through to
`default: drawRing(...)` and silently renders a generic progress ring. Adding a
card id without adding its `case` is the standard way to ship a blank-looking
card — it is what happened to `driving` before review.

The web side never draws a card. It only supplies data:
`_syncDockIndicators()` builds a `bottomCards` array of
`{id, title, value, action, iconAction, primary, secondary, metricA, metricB,
progress, state}` and pushes it over the bridge. `MainActivity` re-parses it and
**re-validates every command against `BOTTOM_CARD_ACTIONS`** — an id the
allow-list does not know is dropped, not forwarded.

Which cards are offered is `H6_BOTTOM_CARD_CATALOG`; which are shown is per
desktop (`_desktopBottomCards`, normalised by `_normalizeBottomCards`).

**A card has two tap targets:**

- the **body** runs `action` — normally opening a popup;
- the **graphic** runs `iconAction` when set, a one-step quick change that saves
  opening the popup. The graphic is a child `View` inside the card, so giving it
  its own listener consumes the touch and the card's own click never fires.

Both go through the same native allow-list. `iconAction` is optional; without it
the whole card is one target.

The Wallpaper graphic cycles 3D → Wallpaper → Both in one state update. The
wallpaper picker opens with Wallpaper or Both and closes with 3D; the car is
visible in 3D and Both only. Tapping the card body opens the picker without
changing Wallpaper or Both. Opening it from 3D selects Wallpaper and opens the
picker in the same update, so delayed callbacks cannot reopen it after a quick
return to 3D. `test-wallpaper-mode.mjs` covers these transitions and rapid taps.

**A visual card already has a text column.** `makeQuickVisualCard` lays the
graphic out beside native TextViews that show `primary`, `secondary` and
`metricA · metricB`. The `QuickCardGraphicView` case should draw a *graphic* —
a ring, bars, a diagram — not the same numbers again. The first ENERGY painter
drew its figure and unit inside the graphic slot too; on the emulator both copies
truncated (`1…k…` beside `11.8`). ENERGY now draws seven-day bars and an EV-share
rule, and the text column carries every number.

### Editing the rail

Layout manager → **Cards** lists the cards *not* on the rail; tapping one
appends it (`_addBottomCardLast`) and the rail scrolls to it (`railFocus`).
The editing itself happens on the real rail: while the Cards tab is open
`_syncDockIndicators` sends `railEdit: true`, and native jiggles the rail
cards, draws × (remove) and ⋯ (options) badges on each one's view overlay, and
lets them be dragged. ⋯ opens a native menu built from the card's `editMenu`
rows (`_railEditMenuRows`): where the card opens, and the clock face. Its
commands (`cardAction:<card>:popup|new|desktop:<id>`) are checked natively by
`isCardActionCommand` and again on the page by `_applyCardActionCommand`.
`railEdit` is applied after the card list in `applyDockIndicators`, or the
badges would be drawn before the menus arrive.

**The page owns the order; native never reorders locally.** A drop reports
`railMoveCard:<id>:<index>` and a badge tap `railRemoveCard:<id>` through
`dockCommand`; `_moveBottomCardTo` / `_removeBottomCard` re-check the id against
the desktop's cards, commit once, and the new `bottomCards` payload makes native
rebuild the row — which re-enters edit mode in `rebuildQuickCardsRow`. Leaving
edit mode rebuilds the row again, because entering it made every child view
non-clickable so a press lands on the drag handler instead of a card action.

## widget — the full-size board

An HTML card rendered by the WebView into a slot on the widget board. There is
**one** board: a fixed `H6_FULL_GRID` (6 x 2) spanning the panel, mirroring
native's left inset on the right (`_fullBoardBounds`). Offered types are
`_widgetCatalog()`, which also declares the sizes each type supports. Placement
lives in `_widgetLayout` — `{ version: 2, appCar: { left: { use, items } } }` —
and is persisted per desktop.

**The car takes the rightmost run of columns no widget touches**
(`_carGapColumns` / `_carGapRect`). A run reaching either grid edge extends to
that panel edge; with every column taken, the car frames on the whole panel.
`_shellCameraTarget` aims at that gap and backs the camera off in proportion
once it is narrower than ~45% of the panel.

Layouts saved before the single board had separate `triple` left/right boards
and an `appCar` left board. `_parseWidgetLayout(raw, fromMode)` folds them with
`_migrateLayoutToFull`: only the boards of the layout the desktop was last
showing survive (so no type is duplicated), right-board widgets keep their
right-hand placement, and `_fitFullGridItems` re-packs anything that no longer
fits from the left.

Adding a widget type touches, at minimum:

1. `_widgetCatalog()` — label, title, sizes
2. a `_<type>WidgetView(item)` builder
3. `isType` in `_widgetItemView`, the `isStub` exclusion chain, and the
   `Object.assign(view, this._<type>WidgetView(item))` line
4. the `<sc-if value="{{ wg.isType }}">` markup — once, on the one board
5. `preview<Type>` in `_widgetThumbItems` and the size-step reset block, plus a
   `.hv-wpick-<type>` thumbnail. **The thumbnail markup exists twice**: in the
   on-board picker and in Layout manager → Widgets. Both use `_widgetThumbItems`.

Retiring a type means removing all of the above **and** migrating saved layouts
(`_migrateRetiredWidgetTypes`, called from `_parseWidgetLayout`). Dropping an
unknown type instead would silently blank a slot the user placed deliberately.

Layout manager → Widgets picks the **type first**: `_startWidgetPlacement` puts
the board into slot-pick with `widgetPlaceType` already set, `_slotHasRoom` then
offers only slots that type fits, and `_pickWidgetSlot` finishes the add.

## popup — the focused workspace

One shared floating frame (`.hv-card-focus`) with a backdrop, opened by
`_openFocusedCard(type)` and keyed on `state.focusedCardType`. Its geometry
comes from the shared popup workspace variables so it cannot cover the launcher
or the dock on Android.

**Every popup is the size of a 3x2 widget, on the left edge nearest the
driver** — the card popup, the Layout manager, the wallpaper picker and the
vehicle-status popup all take `--hv-pop-left/top/width/height` from
`_popupFrameRect`. The vars are set on `<html>` too, because the roof popup lives
on `<body>`, outside `#hv-root`. Do not centre a new one with
`left: 50%; translateX(-50%)`.

The trick worth knowing: `_focusedCardRenderFields()` re-exports every key of
the widget view whose name starts with the card type, prefixed with `focused`.
So `_drivingWidgetView()` returning `drivingGroups` reaches the popup markup as
`{{ focusedDrivingGroups }}` with **no second builder to keep in sync**. Build
the popup's data in the widget view builder and let the prefixing carry it.

A popup needs: a `focusedCardIs<Type>` flag and a title in
`_focusedCardRenderFields`, its branch in the `view` selection, and one
`<sc-if value="{{ focusedCardIs<Type> }}">` block.

## How the three connect

```
bottom rail (native)          widget board (web)         popup (web)
  card body  ──action──────────────────────────────────►  _openFocusedCard
  card icon  ──iconAction──►  quick change (setCarData)
                              widget tap ────────────────►  _openFocusedCard
```

For one feature, prefer **one data builder** feeding all three. The DRIVING
work does this: `_drivingWidgetView()` produces the widget's fields, the popup's
groups (via the `focused` prefixing) and the three rail tiles' visuals, so a
mode cannot read one way on the rail and another in the popup.

## Where a card opens: `_cardAction`

`_cardAction(type)` stores, per desktop, where a card body goes:

- `{kind:'popup'}` — the floating workspace, the default;
- `{kind:'desktop', desktopId}` — switch to a desktop that hosts the card as a
  2x2 widget, with the car beside it. `_createFocusedCardDesktop` builds one on
  demand.

Editable from the ⋯ on a rail card while Layout manager → Cards is open. The destination is keyed on the
**workspace** the card opens, not the card id (`H6_CARD_POPUP_TYPES`), so the
three driving tiles share one setting instead of drifting apart.

**There were two hardcoded `['climate', 'consumption']` gates**, one in the
studio row and one inside `_setCardAction`. Removing only the first makes the
chooser appear and then silently discard every write — it looks like a
persistence bug and is not one. `H6_CARD_FOCUS_TYPES` is now derived from
`H6_CARD_POPUP_TYPES` so the two cannot disagree again.

## Layouts: full size and Side by Side

There is one car layout, **full size** (still stored and sent as `appCar`, because
native persists that key). The old *car in the middle* (`triple`) is gone;
both sides coerce it on read. **Side by Side** (`appsOnly`, two freeform apps)
is not a desktop setting: it is entered from the **Side by Side** launcher tile
(`bindSideBySideItem`) and left from its floating menu (*Exit Side by Side*).
A desktop's snapshot always records `appCar`.

## The template engine wraps every `{{ value }}` in a span

`{{ wg.drivingMode }}` compiles to `<span class="sc-interp">Normal</span>`, so a
descendant rule like `.hv-driving-name span { font: 7px … }` also matches the
interpolated text inside a sibling `<strong>` and silently shrinks it. Use a
child combinator (`> span`); `small`, `em` and `i` are safe. See
`docs/features/driving-card.md` for the full write-up, the way to diagnose it in one
call, and the three shared card rules that still have it.

## The template engine splits an interpolated `style` on `;`

`style="{{ x }}"` is parsed declaration by declaration, so a `;` inside a value
ends it. A data URL always has one (`data:image/jpeg;base64,...`): the Desktops
strip's thumbnails rendered as `background-image: url("data:image/jpeg")` --
black boxes -- while the view builder held the full 25 KB string. Plain `https`
wallpaper URLs never hit it, which is why the same pattern looked safe. Put a
data URL in an attribute instead (`<img src="{{ url }}">`, as the media art and
the thumbnails do). The tell is `getAttribute('style')` being far shorter than
the value the builder returned.

In the light theme, widgets use a 92%-opaque background and darker secondary
text to stay legible over bright wallpapers. Window, roof and tailgate controls
have stronger borders and fills; pending commands retain readable text and a
dashed border in addition to their disabled state.
