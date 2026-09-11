#!/usr/bin/env node
/*
 * Source-level contract for the MEDIA card (rail tile + widget + popup).
 *
 * Same shape as the Driving and Tires contracts: it reads index.html and
 * MainActivity.java rather than booting a browser, Gradle or a device. What it
 * guards is the small, durable part of the card — that the three surfaces come
 * out of ONE builder, that a transport command cannot leave without both an
 * active track and a working MediaBridge, that no metadata or control is
 * invented beyond what MediaNowPlaying publishes and MediaBridge exposes, and
 * that the idle vocabulary in docs/widget-data-audit.md is never softened into
 * something that reads like a live claim.
 *
 * Run: node scripts/test-media-card-contract.mjs
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
// Line endings are normalised on read. index.html and MainActivity.java are
// stored LF and checked out CRLF on Windows, so a source contract that
// hardcodes either one passes or fails depending on which command last
// rewrote the file. Two of these tests had already broken that way.
const html = readFileSync(resolve(root, 'index.html'), 'utf8').replace(/\r\n/g, '\n');
const native = readFileSync(
  resolve(root, 'app/src/main/java/com/havalh6/viewer/MainActivity.java'), 'utf8').replace(/\r\n/g, '\n');
const nowPlaying = readFileSync(
  resolve(root, 'app/src/main/java/com/havalh6/viewer/MediaNowPlaying.java'), 'utf8').replace(/\r\n/g, '\n');

function blockFrom(source, token, label = token) {
  const start = source.indexOf(token);
  assert.ok(start >= 0, `missing ${label}`);
  const brace = source.indexOf('{', start);
  assert.ok(brace >= 0, `missing body for ${label}`);
  let depth = 0;
  for (let i = brace; i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
  }
  throw new Error(`unterminated ${label}`);
}

function includesAll(source, values, label) {
  for (const value of values) {
    assert.ok(source.includes(value), `${label}: expected ${value}`);
  }
}

const mediaView = blockFrom(html, '  _mediaWidgetView(item) {', 'media view builder');

// ---------------------------------------------------------------------------
// 1. The rail tile's gestures.
//
//    Body opens the PLAYER — the app that published the track, projection
//    sources included. A hold opens the MEDIA popup. It used to be
//    `action: 'addWidget'`, which opened the widget PICKER: the one rail card
//    whose body did something unrelated to its own subject.
// ---------------------------------------------------------------------------
const catalogStart = html.indexOf('const H6_BOTTOM_CARD_CATALOG');
assert.ok(catalogStart >= 0, 'missing bottom-card catalog');
const catalog = html.slice(catalogStart, html.indexOf('];', catalogStart) + 2);
assert.match(catalog,
  /id:\s*'media'\s*,\s*title:\s*'Media'\s*,\s*action:\s*'openMediaApp'\s*,\s*longAction:\s*'openMedia'/,
  "the media rail card's body opens the player and a hold opens the popup");
assert.doesNotMatch(catalog, /id:\s*'media'[^}]*action:\s*'addWidget'/,
  'the media card must not open the widget picker');

// It must reach a real handler on both sides of the bridge.
const dockCommandToken = html.includes('  _onDockCommand(') ? '  _onDockCommand(' : '  dockCommand(';
const dockCommand = blockFrom(html, dockCommandToken, 'dock command handler');
assert.match(dockCommand,
  /case\s+['"]openMedia['"]\s*:\s*this\._openFocusedCard\(\s*['"]media['"]\s*\)/,
  'openMedia must open the focused MEDIA workspace');
// A gesture must always answer. With nothing to open, openMediaApp shows the
// popup rather than doing nothing at all.
assert.match(dockCommand,
  /case 'openMediaApp':[\s\S]*?this\.state\.mediaCanLaunch && this\.state\.mediaPackageName[\s\S]*?else this\._openFocusedCard\('media'\);/,
  'openMediaApp must launch the player, falling back to the popup');

const nativeActions = native.slice(
  native.indexOf('BOTTOM_CARD_ACTIONS'),
  native.indexOf('));', native.indexOf('BOTTOM_CARD_ACTIONS')) + 3);
for (const command of ['openMedia', 'openMediaApp']) {
  assert.ok(new RegExp('"' + command + '"').test(nativeActions),
    `native action allow-list must include ${command}, or the command is dropped in transit`);
}

// The studio's destination chooser is keyed on the workspace a card opens, so
// media has to be in that map or the "Change" row silently discards writes.
const popupTypes = html.slice(html.indexOf('const H6_CARD_POPUP_TYPES'),
  html.indexOf('};', html.indexOf('const H6_CARD_POPUP_TYPES')) + 2);
assert.match(popupTypes, /media:\s*'media'/,
  'media must declare its focused workspace in H6_CARD_POPUP_TYPES');

// ---------------------------------------------------------------------------
// 2. One builder feeds all three surfaces.
//
//    The popup gets its fields through `_focusedCardRenderFields`, which
//    re-exports every key starting with the card type, so a second builder
//    would be a second set of strings to drift.
// ---------------------------------------------------------------------------
const itemView = blockFrom(html, '  _widgetItemView(item, side) {', 'widget item view');
assert.match(itemView, /if \(item\.type === 'media'\) Object\.assign\(view, this\._mediaWidgetView\(item\)\);/,
  'the media widget must be built by _mediaWidgetView');

const focusFields = blockFrom(html, '  _focusedCardRenderFields(s) {', 'focused card fields');
includesAll(focusFields, [
  "focusedCardIsMedia: type === 'media'",
  "type === 'media' ? this._mediaWidgetView(entry.item)",
  'base.focusedCardBadge = view.mediaSource;',
], 'media popup wiring');

const dockIndicators = blockFrom(html, '  _syncDockIndicators() {', 'dock indicator payload');
assert.match(dockIndicators, /const media = this\._mediaWidgetView\(\{ type: 'media', w: 2, h: 1 \}\);/,
  'the rail payload must read the same media builder the widget and popup use');
assert.doesNotMatch(dockIndicators, /media:\s*s\.mediaTitle/,
  'the rail must not compose its own media string beside the builder');

// The old page-wide media render fields are gone; a stray one would render in
// the widget markup and quietly bypass the builder.
assert.ok(!html.includes('mediaSourceLabel'), 'the retired global mediaSourceLabel must not come back');
assert.ok(!html.includes('mediaRailDisplay'), 'the dead mediaRailDisplay field must not come back');

// ---------------------------------------------------------------------------
// 3. Truthfulness: no command leaves without a track AND a bridge.
// ---------------------------------------------------------------------------
assert.match(mediaView, /const controlsDisabled = !\(hasTrack && bridge\);/,
  'controls are disabled unless there is both an active track and a MediaBridge');
assert.match(mediaView, /mediaControlsDisabled: controlsDisabled,/,
  'the disabled flag must reach the markup');

const transport = blockFrom(html, '  _mediaTransport(cmd, ev) {', 'media transport');
assert.match(transport, /if \(!this\.state\.mediaHasTrack \|\| !window\.MediaBridge\) \{[\s\S]*?return;/,
  '_mediaTransport must re-check before touching the bridge, not trust the disabled attribute');

// Every transport command offered must be one MediaBridge actually exposes.
const bridgeClass = blockFrom(native, '    public class MediaBridge {', 'native MediaBridge');
for (const method of ['prev', 'playPause', 'next']) {
  assert.ok(bridgeClass.includes(`public void ${method}()`),
    `MediaBridge must expose ${method}()`);
  assert.ok(transport.includes(`window.MediaBridge.${method}()`),
    `_mediaTransport must call MediaBridge.${method}()`);
}
// ...and nothing the bridge does not have. A seek bar, shuffle or repeat would
// be a control that cannot reach the player.
for (const invented of ['seek', 'shuffle', 'repeat', 'setPosition', 'queue']) {
  assert.ok(!transport.includes(invented),
    `_mediaTransport must not offer ${invented}: MediaBridge has no such command`);
  assert.ok(!mediaView.includes(`media${invented.charAt(0).toUpperCase()}${invented.slice(1)}`),
    `the media view must not expose a ${invented} control`);
}
// The progress bar is a readout, so it must not be a control in either surface.
for (const [label, markup] of [['widget', html]]) {
  assert.ok(!/class="hv-media-progress"[^>]*onClick/.test(markup),
    `${label}: the progress bar must stay a readout — there is no seek on the bridge`);
}

// ---------------------------------------------------------------------------
// 4. No invented metadata. Every field traces to a published payload key.
// ---------------------------------------------------------------------------
const payloadKeys = ['title', 'artist', 'album', 'durationMs', 'positionMs',
  'playing', 'appLabel', 'packageName', 'hasTrack', 'artDataUrl', 'needsListener'];
for (const key of payloadKeys) {
  assert.ok(nowPlaying.includes(`o.put("${key}"`),
    `MediaNowPlaying must publish ${key} — the card reads it`);
}
const applyNow = blockFrom(html, '  _applyMediaNowPlayingNow(payload) {', 'now-playing apply');
for (const key of payloadKeys) {
  assert.ok(applyNow.includes(`p.${key}`), `the viewer must read ${key} from the payload`);
}

// ---------------------------------------------------------------------------
// 4b. The playing app names itself, and the card opens it.
//
//     The icon and the label are published by MediaNowPlaying from the real
//     package, never inferred from the package name — and `canLaunch` is what
//     keeps the tap honest: a projection track can name a package with no
//     launcher entry, where launchAppFullscreen is a silent no-op.
// ---------------------------------------------------------------------------
for (const key of ['appIcon', 'canLaunch']) {
  assert.ok(nowPlaying.includes(`o.put("${key}"`),
    `MediaNowPlaying must publish ${key}`);
  assert.ok(applyNow.includes(`p.${key}`), `the viewer must read ${key} from the payload`);
}
assert.match(nowPlaying, /getLaunchIntentForPackage\(pkg\) != null/,
  'canLaunch must be answered by PackageManager, not assumed from a package name');
assert.match(nowPlaying, /private final java\.util\.Map<String, String> appIconCache/,
  'the app icon must be cached per package: a cover changes per track, an icon does not');

assert.match(mediaView, /const canLaunch = !!\(hasTrack && s\.mediaPackageName && s\.mediaCanLaunch\);/,
  'the card must only offer to open a package the native side says is launchable');
assert.match(mediaView, /mediaOpenDisabled: !canLaunch,/,
  "the popup's OPEN button must be disabled when there is nothing to open");
assert.match(mediaView, /if \(!canLaunch\) \{ this\._openFocusedCard\('media'\); return; \}/,
  'a widget tap with nothing to open must fall back to the popup, not do nothing');
assert.match(mediaView, /this\._openMediaApp\(ev\);/,
  'the widget body must open the playing app');

// ---------------------------------------------------------------------------
// 5. Source vocabulary.
//
//    Media is never simulated, so it has no DEMO form at all — and the two idle
//    strings are the ones docs/widget-data-audit.md fixes for this card.
// ---------------------------------------------------------------------------
includesAll(mediaView, [
  "'MEDIA · NO TRACK'",
  "'MEDIA ACCESS REQUIRED'",
], 'idle vocabulary from the widget data audit');
assert.match(mediaView, /source = 'MEDIA UNAVAILABLE · NO MEDIA BRIDGE';/,
  'a shell with no MediaBridge must say so rather than claim there is no track');
assert.match(mediaView, /' · NOW PLAYING' : ' · PAUSED'/,
  'a paused session must not be labelled NOW PLAYING');

assert.ok(!/\bDEMO\b/.test(mediaView),
  'media is never simulated: the builder must have no DEMO state to abbreviate');
assert.ok(!mediaView.includes('_demoPreview'),
  'media must not consult the demo preview flag');
const demoSources = html.slice(html.indexOf('const bottomCardDemoSources'),
  html.indexOf('};', html.indexOf('const bottomCardDemoSources')) + 2);
assert.ok(!/\bmedia:/.test(demoSources),
  'media must not appear in the rail demo-source map');
// If a DEMO badge ever is introduced here it must be the full sentence.
const shortDemo = /DEMO · (?!SIMULATED · NOT VEHICLE)/;
assert.ok(!shortDemo.test(mediaView), 'a DEMO badge must carry the full DEMO · SIMULATED · NOT VEHICLE');

// ---------------------------------------------------------------------------
// 6. Theme, accent and the layout classes.
// ---------------------------------------------------------------------------
const cssStart = html.indexOf('    .hv-media {');
assert.ok(cssStart >= 0, 'missing media CSS block');
const css = html.slice(cssStart, html.indexOf('    .hv-chrome {', cssStart));

assert.ok(css.includes('var(--hv-accent'), 'the media card must follow the configured accent');
assert.ok(css.includes('var(--hv-widget-fg'), 'the media card must follow the widget foreground');
// The retired .hv-modes-chip rules hard-code white and are unreadable on the
// light board. That pattern must not be copied here.
const hardWhite = /(?:color|border(?:-color)?|background)\s*:\s*rgba\(255,\s*255,\s*255/;
for (const rule of css.split('}')) {
  const selector = rule.slice(0, rule.indexOf('{'));
  if (!/\.hv-media/.test(selector)) continue;
  const decls = rule.slice(rule.indexOf('{') + 1);
  if (!hardWhite.test(decls) || decls.includes('var(--hv-')) continue;
  // The one licensed exception: anything sitting ON an album cover. There the
  // ground is the artwork, not the board, so the board theme is not the thing
  // that decides whether white reads.
  assert.match(selector, /\.cover\.has-art/,
    `hard-coded white in a media rule is unreadable on the light board: ${selector.trim().slice(0, 90)}`);
}
// The scrim is the one deliberate exception, and it is keyed on there being
// artwork underneath rather than on the theme.
assert.match(css, /\.hv-media\.cover\.has-art > \.hv-media-body \{[\s\S]*?linear-gradient/,
  'white copy over an album cover needs a scrim, and only when there is art');

// Every size the catalog offers needs its own rule. This is the .cols-N trap:
// the class is emitted either way and a missing rule fails silently.
const catalogEntry = html.match(/media: \{ label: 'MEDIA'[^}]*sizes: (\[.*?\]\])/);
assert.ok(catalogEntry, 'missing media catalog sizes');
const sizes = JSON.parse(catalogEntry[1].replace(/\s+/g, ''));
for (const [w, h] of sizes) {
  assert.ok(css.includes(`.hv-media-${w}x${h}`),
    `.hv-media-${w}x${h} is offered by the catalog but has no CSS rule`);
}
assert.ok(sizes.some(([w, h]) => w === 1 && h === 1), 'media must offer a 1x1 slot');

// The artwork is the card. In `split` it is bled to the tile's own edges, so
// neither layout spends margin on the cover.
assert.match(css, /\.hv-media\.split > \.hv-media-art \{[^}]*aspect-ratio: 1 \/ 1;[^}]*height: 100%;/,
  'the split layout must give the artwork the full card height');
assert.ok(!/\.hv-media\.split \{[^}]*padding:/.test(css),
  'the split card must not pad its own edge, or the artwork stops short of them');

// The app pill needs a ground of its own over an arbitrary album cover.
assert.match(css, /\.hv-media\.cover\.has-art > \.hv-media-app \{[^}]*background:/,
  'the app pill must carry its own fill where it sits over artwork');

// The template engine wraps every {{ value }} in <span class="sc-interp">, so a
// DESCENDANT span rule also restyles interpolated text in a sibling element.
for (const rule of css.split('}')) {
  const selector = rule.slice(rule.lastIndexOf('{') === -1 ? 0 : 0, rule.indexOf('{'));
  if (!/\.hv-media/.test(selector)) continue;
  assert.ok(!/\.hv-media[\w-]*\s+span/.test(selector),
    `descendant span rule restyles interpolated text — use > span: ${selector.trim()}`);
}

// A card full of buttons must opt out of the card-wide :active treatment, or a
// press on the transport lights and scales the whole widget.
assert.match(itemView, /item\.type === 'media' \? ' has-controls' : ''/,
  'the media widget must mark itself has-controls');

// ---------------------------------------------------------------------------
// 7. The widget markup is duplicated per board, and the popup is not.
// ---------------------------------------------------------------------------
const widgetBlocks = html.match(/<sc-if value="\{\{ wg\.isMedia \}\}"/g) || [];
assert.equal(widgetBlocks.length, 2,
  'the left and right widget boards each need the media block — they are separate markup');
const popupBlocks = html.match(/<sc-if value="\{\{ focusedCardIsMedia \}\}"/g) || [];
assert.equal(popupBlocks.length, 1, 'the popup is one shared frame, so it carries one media block');

// Both boards must render the SAME card, not two that drift.
const boardCopies = html.split('<sc-if value="{{ wg.isMedia }}"').slice(1)
  // The delimiter must not care about line endings: this file is checked out
  // with LF on some machines and CRLF on others, and a hardcoded \r\n sliced
  // every board copy down to nothing -- which still passed the equality check
  // below (both were empty) and then failed every disabled-flag assertion.
  .map((chunk) => {
    // Ten spaces exactly: the card nests its own sc-ifs at deeper indentation,
    // so any looser indent match lands on an inner one and cuts the card short.
    const end = chunk.search(/<\/sc-if>\r?\n {10}<sc-if/);
    return chunk.slice(0, end < 0 ? chunk.length : end + '</sc-if>'.length)
      .replace(/\s+/g, ' ').trim();
  });
assert.equal(boardCopies[0], boardCopies[1],
  'the two board copies of the media widget have drifted apart');
assert.ok(boardCopies.length === 2 && boardCopies.every((copy) => copy.length > 400),
  'both board copies of the media widget must be found, not sliced to nothing');

// Every control the widget offers must carry the disabled flag.
for (const field of ['wg.mediaPrev', 'wg.mediaNext', 'wg.mediaPlayPause']) {
  const button = boardCopies[0].slice(boardCopies[0].indexOf(field));
  assert.ok(button.slice(0, 260).includes('disabled="{{ wg.mediaControlsDisabled }}'),
    `${field} must be disabled when the transport is unavailable`);
}

// ---------------------------------------------------------------------------
// 8. The position tick must never reach setState.
//
//    componentDidUpdate asks for a 3D frame after every setState, and the
//    player publishes a position roughly every second.
// ---------------------------------------------------------------------------
const positionDom = blockFrom(html, '  _syncMediaPositionDom(pos, dur) {', 'position DOM sync');
includesAll(positionDom, [
  "document.querySelectorAll('.hv-media-progress > span')",
  "document.querySelectorAll('.hv-media-time-cur')",
], 'the tick pokes the painted nodes');
assert.ok(!positionDom.includes('setState'), 'the position tick must not call setState');

const applyPosition = blockFrom(html, '  applyMediaPosition(ms) {', 'position apply');
assert.match(applyPosition, /Math\.abs\(\(this\.state\.mediaPositionMs \|\| 0\) - pos\) >= 400/,
  'a position setState needs a drift threshold, or it fires at the player rate');

// ---------------------------------------------------------------------------
// 9. Native rail card.
// ---------------------------------------------------------------------------
const mediaCard = blockFrom(native, '    private View makeQuickMediaCard(float density, BottomCardDescriptor descriptor) {',
  'native media rail card');
assert.match(mediaCard, /launchAppForPackage\(quickMediaPackage,/,
  "the rail card's body must open the playing app");
// Clickable unconditionally: a non-clickable native view does not consume its
// touch, and the 3D canvas sits directly underneath the rail.
assert.match(mediaCard, /card\.setClickable\(true\);\s*card\.setFocusable\(true\);\s*final String bodyCommand/,
  'the media rail card must consume its own touch even with nothing to open');
assert.match(mediaCard, /callViewerDock\(bodyCommand\);/,
  'with nothing to open the body must fall back to the card command');
assert.match(mediaCard, /final String longCommand = descriptor\.longAction;[\s\S]*?callViewerDock\(longCommand\);/,
  'a hold on the rail card must reach the MEDIA popup');
assert.match(mediaCard, /quickMediaAppRow = makeQuickMediaAppChip\(density\);/,
  "the rail card must carry the playing app's own chip");
assert.match(native, /private void applyQuickMediaAppChip\(String iconDataUrl, String label\) \{[\s\S]*?setVisibility\(show \? View\.VISIBLE : View\.GONE\);/,
  'an empty app chip must be hidden, not left as a blank pill');
assert.match(native, /resolveMediaChipIcon\(/,
  'the media chip must prefer dock substitutes / branded AA-CarPlay icons');
assert.match(native, /isAndroidAutoMediaSource\(/,
  'Android Auto media must use our branded icon, not MediaCenter\'s generic one');
assert.match(native, /isCarPlayMediaSource\(/,
  'CarPlay media must use our branded icon');
assert.match(native, /quickMediaCanLaunch = payload\.optBoolean\("canLaunch", false\);/,
  'the native card must read canLaunch from the payload');

// A rail rebuild (an accent change rebuilds the whole row) must not blank the
// card until the player happens to publish again.
assert.match(native, /private JSONObject lastMediaPayload;/,
  'the last now-playing payload must be remembered across a rail rebuild');
const populate = blockFrom(native, '    private void populateQuickCardsRow(', 'rail row builder');
assert.equal((populate.match(/replayMediaPayload\(\);/g) || []).length, 2,
  'both branches of the rail builder must replay the remembered payload');
assert.match(native, /private void replayMediaPayload\(\) \{[\s\S]*?updateQuickMediaCard\(lastMediaPayload\);/,
  'replayMediaPayload must re-apply the remembered payload');
assert.match(blockFrom(native, '    private void updateQuickMediaCard(JSONObject payload) {', 'media card update'),
  /lastMediaPayload = payload;/, 'every payload must be remembered as it arrives');

// The native transport stays gated on an active track.
assert.match(native, /quickMediaAvailable = hasTrack && mediaNowPlaying != null;/,
  'native transport availability must require an active track');

console.log('media card contract: OK');
