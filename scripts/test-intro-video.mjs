import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';

// Intro clip preference: the offer rule, the three answers, and the Aparência switch.
const html = fs.readFileSync(new URL('../index.html', import.meta.url), 'utf8');

const offerSrc = html.match(/function h6OfferIntroVideoOff\(opts\) \{[\s\S]*?\n\}/);
assert.ok(offerSrc, 'offer rule is a function the splash prompt and the tests share');
const offer = vm.runInNewContext(offerSrc[0] + '\nh6OfferIntroVideoOff', {});
const ready = { skipped: true, loading: false, setupDone: true, introVideo: true, introVideoPrompt: true };
assert.equal(offer(ready), true, 'a skip after the home is ready asks');
assert.equal(offer({ ...ready, skipped: false }), false, 'letting the clip finish does not ask');
assert.equal(offer({ ...ready, loading: true }), false, 'the question stays closed while the home is still loading');
assert.equal(offer({ ...ready, setupDone: false }), false, 'first-run setup is not the moment to ask');
assert.equal(offer({ ...ready, introVideo: false }), false, 'an intro that is already off does not ask');
assert.equal(offer({ ...ready, introVideoPrompt: false }), false, 'não perguntar de novo stays quiet');
assert.equal(offer({}), false);
assert.throws(() => {
  const broken = vm.runInNewContext(offerSrc[0].replace('return true;', 'return false;') + '\nh6OfferIntroVideoOff', {});
  assert.equal(broken(ready), true);
});

assert.match(html, /VÍDEO DE ABERTURA/, 'Aparência names the switch');
assert.match(html, /studioIntroVideoChecked: s\.introVideo !== false/, 'the switch reads the setting');
assert.match(html, /Desligue para ir direto à tela, sem o vídeo\./, 'the switch explains what off does');
assert.match(html, /aria-label="Vídeo de abertura"/, 'the prompt is labelled in Portuguese');
assert.match(html, />Sim<\/button>/, 'yes');
assert.match(html, />Não<\/button>/, 'no');
assert.match(html, />Não perguntar de novo<\/button>/, 'do not ask again');
assert.match(html, /gerenciador de layout, vá em Aparência e use Vídeo de abertura/, 'the prompt says where to change it later');
assert.match(html, /introVideo: s\.introVideo !== false/, 'a later save keeps the setting');
assert.match(html, /introVideoPrompt: s\.introVideoPrompt !== false/, 'a later save keeps the do-not-ask flag');
assert.match(html, /Você pulou o vídeo de abertura\. Quer desativá-lo nas próximas vezes\?/, 'the prompt does not claim the home was already ready');
assert.match(html, /if \(skipped\) this\._armIntroVideoOffer\(\)/, 'any skip arms the prompt once loading has finished');
assert.doesNotMatch(html, /_introSkipDuringLoad/, 'an early skip is not discarded');
assert.match(html, /_scheduleIntroVideoOffer\(\)/, 'the prompt is scheduled when the home is up');
assert.match(html, /saved\.introVideo === false/, 'boot reads the setting before assigning the clip');

const script = html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1];
const context = vm.createContext({ DCLogic: class {}, window: {}, performance, console, setTimeout, clearTimeout });
vm.runInContext(script + ';globalThis.C=Component;', context);
const app = Object.create(context.C.prototype);
app.state = { introVideo: true, introVideoPrompt: true, introSkipOfferOpen: false };
app.setState = (patch) => { Object.assign(app.state, patch); };
const store = { h6_settings_v1: JSON.stringify({ modelTrim: 'hev', introVideo: true }) };
context.window.localStorage = {
  getItem: (k) => (k in store ? store[k] : null),
  setItem: (k, v) => { store[k] = String(v); },
};
vm.runInContext('globalThis.localStorage = window.localStorage;', context);

app._answerIntroVideoOffer('no');
assert.equal(app.state.introSkipOfferOpen, false);
assert.equal(JSON.parse(store.h6_settings_v1).introVideo, true, 'não leaves the clip on');
assert.equal(app.state.introVideo, true);

app.state.introSkipOfferOpen = true;
app._answerIntroVideoOffer('never');
assert.equal(app.state.introVideo, true, 'não perguntar de novo keeps the clip');
assert.equal(app.state.introVideoPrompt, false);
assert.equal(JSON.parse(store.h6_settings_v1).introVideoPrompt, false);
assert.equal(JSON.parse(store.h6_settings_v1).modelTrim, 'hev', 'the patch leaves the other settings');

app._setIntroVideoEnabled(true);
assert.equal(app.state.introVideoPrompt, true, 'turning the clip back on may ask again');
app.state.introSkipOfferOpen = true;
app._answerIntroVideoOffer('yes');
assert.equal(app.state.introVideo, false);
assert.equal(app.state.introVideoPrompt, false);
assert.equal(JSON.parse(store.h6_settings_v1).introVideo, false, 'sim turns the clip off for the next boot');

app._hasCompletedSetup = () => true;
app._introVideoOfferArmed = false;
context.window.HavalSplash = { wasSkipped: () => true };
app.state.loading = true;
app._armIntroVideoOffer();
assert.equal(app._introVideoOfferArmed, false, 'a skip during loading does not open the question yet');

app.state.loading = false;
app.state.introVideo = true;
app.state.introVideoPrompt = true;
app._armIntroVideoOffer();
assert.equal(app._introVideoOfferArmed, true, 'that same skip asks once loading ends');

let scheduled = null;
context.setTimeout = (fn, ms) => { scheduled = { fn, ms }; return 7; };
context.clearTimeout = () => {};
context.document = { body: { classList: { contains: () => false } } };
app._shellChromeTiming = () => ({ readyMs: 4100 });
app._scheduleIntroVideoOffer();
assert.equal(scheduled.ms, 0, 'with the widgets already up the question does not wait out the fade');
context.document.body.classList.contains = () => true;
app._introVideoOfferTimer = null;
app._introVideoOfferArmed = true;
scheduled = null;
app._scheduleIntroVideoOffer();
assert.equal(scheduled.ms, 4100, 'while the widgets are still fading the question waits');
app._introVideoOfferArmed = false;
scheduled = null;
app._scheduleIntroVideoOffer();
assert.equal(scheduled, null, 'an offer that was not armed does not schedule a question');

console.log('PASS intro video setting, late-skip prompt and negative controls');
