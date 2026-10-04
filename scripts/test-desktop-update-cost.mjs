import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync(new URL('../index.html',import.meta.url),'utf8');
function check(html){
 const timers=[];
 const ctx=vm.createContext({DCLogic:class{},setTimeout:(fn,ms)=>{timers.push({fn,ms});return timers.length}});
 vm.runInContext(html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]+';globalThis.App=Component;',ctx);
 const app=Object.create(ctx.App.prototype);app._androidApp=true;
 let calls=0,latest;app.state={value:1};app._flushDockIndicators=()=>{calls++;latest=app.state.value};
 app._syncDockIndicators();app._syncDockIndicators();assert.equal(timers.length,1);
 app.state.value=2;timers.shift().fn();assert.equal(calls,1);assert.equal(latest,2);
 app._deskSlide={};app._syncDockIndicators();assert.equal(timers[0].ms,100);
 timers.shift().fn();assert.equal(calls,1,'no native rebuild during slide');
 app._deskSlide=null;timers.shift().fn();assert.equal(calls,2);
 app._syncDockIndicators();app._unmounted=true;timers.shift().fn();assert.equal(calls,2);
 assert.doesNotMatch(ctx.App.prototype.renderVals.toString(),/^    this\._syncDockIndicators\(/m,'render has no native side effect');
 const frozen={leftWidgetItems:[]};app._desktops=[{id:'one'}];app._desktopWidgetFields=new Map([['one',frozen]]);app._deskSlide={};
 app._ensureWidgetsLoaded=()=>{throw new Error('rebuild')};
 assert.equal(app._widgetRenderFields({}),frozen,'visited widgets do not rebuild during slide');
 app._deskSlide=null;assert.throws(()=>app._widgetRenderFields({}),/rebuild/,'latest values rebuild after slide');
}
check(source);
assert.throws(()=>check(source.replace('  renderVals() {', '  renderVals() {\n    this._syncDockIndicators();')));
assert.throws(()=>check(source.replace('if (this._deskSlide) { this._syncDockIndicators(); return; }','if (false) { this._syncDockIndicators(); return; }')));
assert.throws(()=>check(source.replace('if (this._deskSlide && cached) return cached;','if (false && cached) return cached;')));
console.log('PASS grouped latest dock sync, slide pause, unmount, pure render, widget freeze + negative controls');
