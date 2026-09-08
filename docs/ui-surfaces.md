# UI surfaces: card, widget, popup

Three different things in this app are all loosely called "the card", and the
confusion is expensive: a change verified on one surface can be completely
broken on another. This is the vocabulary. Use these words in commits, issues
and agent briefs.

| Term | Where it lives | Who draws it | Sizes |
| --- | --- | --- | --- |
| **card** | the launcher rail along the bottom | **native Android** (`MainActivity`) | fixed rail tile |
| **widget** | the reserved widget boards left/right of the car | the WebView | grid cells (1x1 … 3x2) |
| **popup** | floating over everything, dismissed by a backdrop tap | the WebView | one shared frame |

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

## widget — the reserved boards

An HTML card rendered by the WebView into a slot on the left/right widget
board. Offered types are `_widgetCatalog()`, which also declares the sizes each
type supports. Placement lives in `_widgetLayout` (per shell mode, per side)
and is persisted per desktop.

Adding a widget type touches, at minimum:

1. `_widgetCatalog()` — label, title, sizes
2. a `_<type>WidgetView(item)` builder
3. `isType` in `_widgetItemView`, the `isStub` exclusion chain, and the
   `Object.assign(view, this._<type>WidgetView(item))` line
4. the `<sc-if value="{{ wg.isType }}">` markup — **in both boards**, the left
   and right board markup is duplicated
5. `preview<Type>` in the picker (two places: the flag map and the reset block)
   plus a `.hv-wpick-<type>` thumbnail

Retiring a type means removing all of the above **and** migrating saved layouts
(`_migrateRetiredWidgetTypes`, called from `_parseWidgetLayout`). Dropping an
unknown type instead would silently blank a slot the user placed deliberately.

## popup — the focused workspace

One shared floating frame (`.hv-card-focus`) with a backdrop, opened by
`_openFocusedCard(type)` and keyed on `state.focusedCardType`. Its geometry
comes from the shared popup workspace variables so it cannot cover the launcher
or the dock on Android.

The trick worth knowing: `_focusedCardRenderFields()` re-exports every key of
the widget view whose name starts with the card type, prefixed with `focused`.
So `_drivingWidgetView()` returning `drivingGroups` reaches the popup markup as
`{{ focusedDrivingGroups }}` with **no second builder to keep in sync**. Build
the popup's data in the widget view builder and let the prefixing carry it.

A popup needs: a `focusedCardIs<Type>` flag and a title in
`_focusedCardRenderFields`, its branch in the `view` selection, and one
`<sc-if value="{{ focusedCardIs<Type> }}">` block (only once — the popup is not
duplicated the way the boards are).

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
  full-area widget with the car on the right. `_createFocusedCardDesktop` builds
  one on demand (`appCar` shell, 2x2 widget on the left).

Editable in Desktop Studio → Bottom bar → *Change*. The destination is keyed on
the **workspace** the card opens, not the card id (`H6_CARD_POPUP_TYPES`), so
the three driving tiles share one setting instead of drifting apart.

**There were two hardcoded `['climate', 'consumption']` gates**, one in the
studio row and one inside `_setCardAction`. Removing only the first makes the
chooser appear and then silently discard every write — it looks like a
persistence bug and is not one. `H6_CARD_FOCUS_TYPES` is now derived from
`H6_CARD_POPUP_TYPES` so the two cannot disagree again.

## The template engine wraps every `{{ value }}` in a span

`{{ wg.drivingMode }}` compiles to `<span class="sc-interp">Normal</span>`, so a
descendant rule like `.hv-driving-name span { font: 7px … }` also matches the
interpolated text inside a sibling `<strong>` and silently shrinks it. Use a
child combinator (`> span`); `small`, `em` and `i` are safe. See
`docs/driving-card.md` for the full write-up, the way to diagnose it in one
call, and the three shared card rules that still have it.
