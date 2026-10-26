// While the camera moves, bloom + streak rebuild on one rendered frame in three, and
// the first frame after it settles always rebuilds. Each rebuild renders the scene
// twice more; measured on the car with the camera orbiting, 29.8 fps against 35.5.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync(new URL('../index.html',import.meta.url),'utf8');
function check(html){
 const ctx=vm.createContext({DCLogic:class{},setTimeout:()=>0,clearTimeout:()=>{}});
 vm.runInContext(html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]+';globalThis.App=Component;globalThis.EVERY=POSTFX_MOTION_EVERY;',ctx);
 assert.equal(ctx.EVERY,3,'one rebuild in three moving frames');
 const app=Object.create(ctx.App.prototype);
 app._perf={};
 const requests=[];app.requestRender=(n,keep)=>{requests.push([n,keep])};
 // Execute the actual loop decision so clean hits and throttle skips cannot mix.
 const decision=html.match(/if \(this\._postFxRebuildNow\(cameraBusy\)\) \{[\s\S]*?(?=\n\n          \/\/ 4\) Additive overlay:)/);
 assert.ok(decision,'render loop rebuilds through _postFxRebuildNow');
 const renderFrame=vm.runInContext(`(function(cameraBusy){const scene={},syncCam=()=>{},anyStreak=false;${decision[0]}})`,ctx);
 app.bloomComposer={render:()=>{}};
 app._darkenNonBloom=()=>{};app._restoreMaterials=()=>{};
 // One loop frame: the camera re-dirties post-FX while it moves; a rebuild clears it.
 const frame=(busy)=>{if(busy)app._postFxDirty=true;const before=app._perf.postFxRenders||0;renderFrame.call(app,busy);return (app._perf.postFxRenders||0)>before};
 app._postFxDirty=true;
 assert.equal(frame(false),true,'camera still: a dirty overlay rebuilds on the next frame');
 assert.equal(frame(false),false,'camera still and clean: cached overlay');
 const moving=Array.from({length:9},()=>frame(true));
 assert.deepEqual(moving,[false,false,true,false,false,true,false,false,true],'moving: frames 3, 6 and 9 rebuild');
 assert.equal(requests.length,6,'every skipped frame queues another frame');
 assert.ok(requests.every(([n,keep])=>n===1&&keep===true),'the queued frame keeps the overlay as it is');
 frame(true);frame(true);
 assert.equal(app._postFxDirty,true,'a skipped frame leaves post-FX dirty');
 assert.equal(app._perf.postFxThrottled,8,'throttle skips have their own counter');
 assert.equal(app._perf.postFxCacheHits,1,'only the clean frame counts as a cache hit');
 assert.equal(frame(false),true,'the first frame after the camera settles rebuilds');
 assert.equal(frame(false),false,'and only that one');
 requests.length=0;app._postFxDirty=false;
 assert.equal(app._postFxRebuildNow(true),false,'moving but clean: nothing to rebuild');
 assert.equal(requests.length,0,'and no frame is queued for it');
 renderFrame.call(app,true);
 assert.equal(app._perf.postFxCacheHits,3,'clean moving frames count as real cache hits too');
 for(const busy of [true,false])for(const dirty of [true,false]){
  app._postFxStale=true;app._postFxDirty=dirty;app._postFxMotionFrames=0;
  const skips=app._perf.postFxThrottled,hits=app._perf.postFxCacheHits;
  assert.equal(frame(busy),true,'stale overlay rebuilds immediately, even moving or clean');
  assert.equal(app._postFxStale,false,'rebuilding consumes the stale flag');
  assert.equal(app._perf.postFxThrottled,skips,'stale rebuild is not throttled');
  assert.equal(app._perf.postFxCacheHits,hits,'stale rebuild is not a cache hit');
  assert.equal(frame(false),false,'the stale flag forces only one rebuild');
 }
 // Tests and early setup can call the helper before performance counters exist.
 delete app._perf;app._postFxDirty=true;app._postFxMotionFrames=0;
 assert.equal(app._postFxRebuildNow(true),false,'throttle tolerates absent performance counters');
 const warmup=html.match(/  _warmupLightKey\(k\) \{[\s\S]*?\n  \}/)[0];
 const resize=html.match(/      this\._resizePostFX = \(W, H\) => \{[\s\S]*?\n      \};/)[0];
 assert.match(warmup,/this\._postFxStale = true;/,'light warmup marks the overlay stale');
 assert.match(resize,/this\._postFxStale = true;/,'resize marks the overlay stale');
 // The loop consults the throttle, with the loop's own cameraBusy, before the bloom pass.
 assert.match(html,/if \(\(anyLight \|\| xrayBloom\) && this\.bloomComposer\) \{\s*if \(this\._postFxRebuildNow\(cameraBusy\)\) \{\s*syncCam\(this\.bloomCam\);/,'render loop rebuilds through _postFxRebuildNow');
}
check(source);
const body=(s)=>source.replace(/  _postFxRebuildNow\(cameraBusy\) \{[\s\S]*?\n  \}\n/,`  _postFxRebuildNow(cameraBusy) {\n${s}\n  }\n`);
assert.throws(()=>check(body('    return !!this._postFxDirty;')),/frames 3, 6 and 9/,'negative control: no throttle');
assert.throws(()=>check(body(`    if (!cameraBusy) { this._postFxMotionFrames = 0; return !!this._postFxDirty; }
    if (!this._postFxDirty) return false;
    this._postFxMotionFrames = (this._postFxMotionFrames || 0) + 1;
    return this._postFxMotionFrames % POSTFX_MOTION_EVERY === 0;`)),/queues another frame/,'negative control: no queued frame for the settle');
assert.throws(()=>check(body(`    if (!cameraBusy) { this._postFxMotionFrames = 0; return !!this._postFxDirty; }
    if (!this._postFxDirty) return false;
    this._postFxMotionFrames = (this._postFxMotionFrames || 0) + 1;
    if (this._postFxMotionFrames % POSTFX_MOTION_EVERY === 0) return true;
    this._postFxDirty = false; if (this.requestRender) this.requestRender(1, true); return false;`)),/leaves post-FX dirty/,'negative control: a skip that drops the dirty flag');
assert.throws(()=>check(source.replace('if (this._postFxRebuildNow(cameraBusy)) {','if (this._postFxDirty) {')),/rebuilds through _postFxRebuildNow/,'negative control: loop bypasses the throttle');
assert.throws(()=>check(source.replace('this.requestRender(1, true);\n    return false;','this.requestRender(1);\n    return false;')),/keeps the overlay/,'negative control: the queued frame invalidates the overlay');
assert.throws(()=>check(source.replace('if (this._postFxStale) {','if (false) {')),/stale overlay rebuilds immediately/,'negative control: stale cache is reused while moving');
assert.throws(()=>check(source.replace('this._postFxStale = false;','this._postFxStale = true;')),/consumes the stale flag/,'negative control: stale flag never clears');
for(const [path,pattern] of [
 ['light warmup',/(_warmupLightKey\(k\) \{[\s\S]*?)this\._postFxStale = true;/],
 ['resize',/(this\._resizePostFX = \(W, H\) => \{[\s\S]*?)this\._postFxStale = true;/]
])assert.throws(()=>check(source.replace(pattern,'$1')),new RegExp(`${path} marks the overlay stale`),`negative control: ${path} does not invalidate the cache`);
assert.throws(()=>check(source.replace('if (this._perf) this._perf.postFxThrottled = (this._perf.postFxThrottled || 0) + 1;','')),/throttle skips have their own counter/,'negative control: throttle skips are not counted');
assert.throws(()=>check(source.replace('} else if (!this._postFxDirty) {\n            this._perf.postFxCacheHits','} else {\n            this._perf.postFxCacheHits')),/only the clean frame counts/,'negative control: throttle skips inflate cache hits');
assert.throws(()=>check(source.replace('if (this._perf) this._perf.postFxThrottled','this._perf.postFxThrottled')),/postFxThrottled/,'negative control: absent performance counters throw');
console.log('PASS post-FX motion throttle: cadence, stale resize/warmup cache, separate counters + 12 negative controls');
