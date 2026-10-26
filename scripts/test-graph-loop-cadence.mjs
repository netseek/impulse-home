// The widget rAF loop (_drawGraphs) runs for graphs and power cards only, and it
// samples the graph series only while a graph canvas is mounted: nothing else
// reads the series. Power cards keep their 100 ms paint either way. Sampling
// stays per-frame under a canvas, since _graphSmooth's EMA and the 40 ms sample
// bucket are tuned to the frame loop. Sampling pauses while no canvas is up, so
// the series restarts when one comes back instead of bridging the gap.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync(new URL('../index.html',import.meta.url),'utf8');
const T0=1000,DT=16.7,FRAMES=60;
function boot(html){
 const ctx=vm.createContext({DCLogic:class{},
  setTimeout:()=>0,clearTimeout(){},requestAnimationFrame:()=>1,cancelAnimationFrame(){},performance:{now:()=>T0},
  document:{body:{classList:{remove(){}}},querySelectorAll:()=>[]},
  window:{React:{Component:class{},createElement:(type,props)=>({type,props})}}});
 vm.runInContext(html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]+';globalThis.App=Component;',ctx);
 return ctx;
}
// A live bus (power and fuel), so every sampler would really move if it ran.
function rig(ctx,{canvases=[],item=null,lean=true}={}){
 const P=ctx.App.prototype,app=Object.create(P);
 const h={app,power:[],paint:[],smooth:{},derive:0};
 Object.assign(app,{_powerLean:lean,_powerCardPaintAt:0,_graphCanvasCacheAt:0,_graphDrawAt:0,
  _graphAnyLive:true,_graphLiveKeys:{evPowerKw:true,fuelInst:true},_graphValues:{evPowerKw:24,fuelInst:15},
  _graphDisplay:{},_graphSeries:{},_graphEvInstEma:null,_powerLive:{},_carSpeedKmh:60});
 app._updatePowerCards=(now)=>{h.power.push(now)};
 app._widgetNodes=(sel)=>{assert.equal(sel,'.hv-graphs-canvas');return canvases};
 app._graphActiveItem=()=>item;
 app._paintGraphCanvas=(canvas,pane,now)=>{h.paint.push(now)};
 app._graphSmooth=function(key,now){h.smooth[key]=(h.smooth[key]||0)+1;return P._graphSmooth.call(this,key,now)};
 app._graphDeriveConsumption=function(now){h.derive++;return P._graphDeriveConsumption.call(this,now)};
 for(let i=0;i<FRAMES;i++)app._drawGraphs(T0+i*DT);
 return h;
}
// present -> absent -> present on one app: the same loop, the canvas set swapped between phases.
function absence(ctx){
 const P=ctx.App.prototype,app=Object.create(P);let canvases=[canvas(0)];
 Object.assign(app,{_powerLean:true,_powerCardPaintAt:0,_graphCanvasCacheAt:0,_graphDrawAt:0,
  _graphAnyLive:true,_graphLiveKeys:{evPowerKw:true,fuelInst:true},_graphValues:{evPowerKw:24,fuelInst:15},
  _graphDisplay:{},_graphSeries:{},_graphEvInstEma:null,_powerLive:{},_carSpeedKmh:60});
 app._updatePowerCards=()=>{};app._widgetNodes=()=>canvases;
 app._graphActiveItem=()=>({type:'graphs',w:2,h:1,graph:{cols:1,rows:1,panes:[{kind:'series',vars:['evPowerKw'],graphStyle:'line'}]}});
 app._paintGraphCanvas=()=>{};
 const run=(from,n)=>{for(let i=0;i<n;i++)app._drawGraphs(from+i*DT)};
 run(T0,60);
 const before=app._graphSeries.evPowerKw.slice();
 // Off the graph desktop for 2 s (the canvas cache refreshes every 500 ms), the bus moves on.
 canvases=[];app._graphValues.evPowerKw=-8;
 run(T0+1500,60);
 const during=app._graphSeries.evPowerKw.slice();
 const RESUME=T0+3000;canvases=[canvas(0)];
 run(RESUME,40);
 return {before,during,after:app._graphSeries.evPowerKw,resume:RESUME,display:app._graphDisplay.evPowerKw};
}
const gaps=(ts)=>ts.slice(1).map((t,i)=>t-ts[i]);
const canvas=(pane)=>({getAttribute:(name)=>name==='data-pane'?String(pane):null});
// The gate argument evaluated as written, against real _hasWidgetType and _syncGraphLoop.
function gate(ctx,html){
 const call='this._syncGraphLoop(';
 assert.equal(html.split(call).length,2,'one loop gate call site');
 const start=html.indexOf(call)+call.length;let depth=1,i=start;
 while(depth&&i<html.length){const c=html[i++];if(c==='(')depth++;else if(c===')')depth--}
 const fn=vm.runInContext('(function (mode, appsOnly) { return ('+html.slice(start,i-1).trim().replace(/,$/,'')+'); })',ctx);
 return (types,{loading=false,appsOnly=false}={})=>{
  const app=Object.create(ctx.App.prototype);app.state={loading};
  app._currentBoards=()=>({left:{items:types.map((type)=>({type}))},right:{items:[]}});
  app._syncGraphLoop(fn.call(app,'appCar',appsOnly));
  assert.equal(!!app._graphRaf,app._graphLoopWanted,'the loop runs exactly when wanted');
  return app._graphLoopWanted;
 };
}
function check(html){
 const ctx=boot(html);
 // --- (a) power only, no canvas: power paints at its cadence, nothing is sampled ---
 let h=rig(ctx);
 assert.equal(h.power.length,10,'60 frames at 16.7 ms paint the power card every 100 ms, got '+h.power.length);
 assert.equal(h.power[0],T0,'the first frame paints');
 assert.ok(gaps(h.power).every((g)=>g>=100&&g<100+DT),'power paints are 100 ms apart: '+gaps(h.power));
 assert.equal(h.derive,0,'no consumption derivation without a canvas');
 assert.deepEqual(h.smooth,{},'no _graphSmooth without a canvas');
 assert.deepEqual(Object.keys(h.app._graphSeries),[],'the series gets no samples');
 assert.deepEqual(Object.keys(h.app._graphDisplay),[],'and no smoothed values');
 assert.equal(h.app._graphEvInstEma,null);assert.equal(h.app._powerLive.evInst,undefined);
 h=rig(ctx,{lean:false});
 assert.equal(h.power.length,FRAMES,'the _powerLean=false arm still paints every frame');
 assert.equal(h.derive,0);assert.deepEqual(h.smooth,{});
 // --- (b) a canvas is mounted: per-frame sampling, minDt paint throttle ---
 const series={type:'graphs',w:2,h:1,graph:{cols:1,rows:1,panes:[{kind:'series',vars:['evPowerKw','fuelTrip'],graphStyle:'line'}]}};
 h=rig(ctx,{canvases:[canvas(0)],item:series});
 assert.equal(h.derive,FRAMES,'consumption is derived every frame');
 assert.deepEqual(h.smooth,{evPowerKw:FRAMES,evInst:FRAMES,fuelInst:FRAMES,fuelTrip:FRAMES},'each key, base and per-pane, is smoothed once per frame');
 assert.equal(h.app._graphSeries.evPowerKw.length,20,'per-frame samples land in 40 ms buckets');
 assert.equal(h.paint.length,30,'a line pane paints at the 33 ms minDt, got '+h.paint.length);
 assert.ok(gaps(h.paint).every((g)=>g>=33),'paints are at least 33 ms apart');
 assert.equal(h.power.length,10,'the power paint is unchanged under a canvas');
 const fast={type:'graphs',w:2,h:1,graph:{cols:2,rows:1,panes:[{kind:'viz'},{kind:'series',vars:['evTrip'],graphStyle:'dots'}]}};
 h=rig(ctx,{canvases:[canvas(0),canvas(1)],item:fast});
 assert.equal(h.derive,FRAMES);assert.equal(h.smooth.evTrip,FRAMES,'per-pane keys are smoothed every frame');
 assert.equal(h.paint.length,2*FRAMES,'viz and dots paint at the 16 ms minDt, every canvas every frame');
 // --- (c) the gate: graphs and power want the loop, range does not ---
 const wants=gate(ctx,html);
 assert.equal(wants(['range']),false,'a range-only board leaves the loop off');
 assert.equal(wants([]),false);
 assert.equal(wants(['power']),true,'power wants the loop');
 assert.equal(wants(['graphs']),true,'graphs want the loop');
 assert.equal(wants(['range','power']),true);
 assert.equal(wants(['power'],{loading:true}),false,'not while loading');
 assert.equal(wants(['graphs'],{appsOnly:true}),false,'not on the apps-only board');
 // --- (d) back from an absence: the series restarts, nothing is drawn across the gap ---
 const a=absence(ctx);
assert.ok(a.before.length>10&&a.before.every((p)=>p.v===24),'the first presence sampled the bus');
 assert.deepEqual(a.during,a.before,'no sampling while absent: the old samples just sit there');
 assert.ok(a.after.length>10,'sampling resumed');
 assert.ok(a.after.every((p)=>p.t>=a.resume),'no sample from before the absence survives, so no segment spans the gap');
 assert.equal(a.after[0].v,-8,'the first new sample is the raw value, not an EMA step from the stale display');
 assert.equal(a.display,-8);
}
// The mutation must apply, otherwise a "control" would only prove the source did not change.
const mutate=(from,to,html=source)=>{assert.equal(html.split(from).length,2,'control anchor not found once: '+from.slice(0,60));return html.replace(from,()=>to)};
const SAMPLE="    this._graphDeriveConsumption(now);\n    this._graphSmooth('evPowerKw', now);\n    this._graphSmooth('evInst', now);\n    if (this._graphLiveKeys.fuelInst) this._graphSmooth('fuelInst', now);\n";
check(source);
assert.throws(()=>check(mutate("        || this._hasWidgetType(mode, 'power')),","        || this._hasWidgetType(mode, 'power')\n        || this._hasWidgetType(mode, 'range')),")),'range back in the gate');
assert.throws(()=>check(mutate('  _drawGraphs(now) {\n','  _drawGraphs(now) {\n'+SAMPLE,mutate(SAMPLE,''))),'sampling back above the canvas check');
assert.throws(()=>check(mutate('      this._powerCardPaintAt = now;\n      this._updatePowerCards(now);\n','      this._powerCardPaintAt = now;\n')),'power paint dropped');
assert.throws(()=>check(mutate('now - this._powerCardPaintAt >= POWER_CARD_PAINT_MS','true')),'power paint unthrottled');
assert.throws(()=>check(mutate(SAMPLE,'')),'sampling deleted');
assert.throws(()=>check(mutate('    if (this._graphSeriesGap) {\n      this._graphSeriesGap = false;\n      this._graphSeries = {};\n      this._graphDisplay = {};\n    }\n','')),'series not restarted after an absence');
assert.throws(()=>check(mutate('      this._graphSeries = {};\n      this._graphDisplay = {};\n','      this._graphSeries = {};\n')),'smoothed display kept across the gap');
assert.throws(()=>check(mutate('      this._graphSeriesGap = true;\n','')),'absence never recorded');
assert.throws(()=>check(mutate('    if (this._graphDrawAt && now - this._graphDrawAt < minDt) return;\n','')),'minDt paint throttle dropped');
console.log('PASS graph loop cadence: power paints every 100 ms, series sampled per frame only under a canvas, range off the gate + negative controls');
