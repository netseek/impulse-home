// The open POWER popup is painted like any .hv-power card; it may commit the
// root only when its shape changes (chart data/placeholder, caption, DEMO
// badge). Before, every 4 Hz bus refresh and every 1 Hz history tick committed
// the root while it was open, and a root commit costs ~197 ms on the car.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync(new URL('../index.html',import.meta.url),'utf8');
function boot(html){
 const timers=new Map();let now=0,seq=0;
 const clock={
  advance(ms){const end=now+ms;for(;;){
   let next=null,nextId=0;for(const [id,t] of timers)if(t.at<=end&&(!next||t.at<next.at)){next=t;nextId=id}
   if(!next)break;now=next.at;timers.delete(nextId);next.fn()}now=end}};
 const ctx=vm.createContext({DCLogic:class{},
  setTimeout:(fn,ms)=>{timers.set(++seq,{fn,ms,at:now+ms});return seq},clearTimeout:(id)=>{timers.delete(id)},
  performance:{now:()=>now},document:{hidden:false},window:{React:{Component:class{}}}});
 vm.runInContext(html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]+';globalThis.App=Component;',ctx);
 const app=Object.create(ctx.App.prototype);
 app.state={widgetRev:0,shellMode:'appCar',focusedCardType:'power'};
 const h={app,clock,roots:[],paints:0,docks:0,model:{stateKey:'ev',sourceClass:'live',ariaLabel:'EV'}};
 app._powerModel=()=>h.model;
 app._powerHistory=[];
 app._updatePowerCards=()=>{h.paints++};
 app._syncDockIndicators=()=>{h.docks++};
 app._canRefreshDesktopStage=()=>false;
 app.setState=(patch,done)=>{h.roots.push({patch,done,uiOnly:app._uiOnlyStateWrite});app._uiOnlyStateWrite=false};
 // What the popup's render records: the same view the template is given.
 h.render=()=>{app._focusedCardEntry=()=>({item:{type:'power',w:2,h:2}});
  app._powerWidgetView=()=>Object.assign({powerStateKey:h.model.stateKey,powerSourceClass:h.model.sourceClass},
   app._powerHistoryView(Date.now(),h.model.stateKey==='demo'));
  ['_energyPopupWideOn','_climatePopupWideOn','_rangePopupWideOn','_energyAskKeys','_energyAskDefaultPrice'].forEach((k)=>{app[k]=()=>false});
  app._energyAskDisplay=app._energyAskAltLabel=()=>'';
  return app._focusedCardRenderFields(app.state)};
 return h;
}
const sample=(app,ago,kw)=>app._powerHistory.push({at:Date.now()-ago,kw});
function check(html){
 // Open, nothing structural moving: bus refreshes and history ticks only paint.
 let h=boot(html);let {app,clock,roots}=h;
 const fields=h.render();
 assert.equal(fields.focusedPowerHistoryHasData,false);
 for(let i=0;i<20;i++)app._commitPowerRefresh();
 assert.equal(roots.length,0,'bus refreshes with the popup open never reach a root commit');
 assert.equal(h.paints,20,'each refresh still paints the cards, popup included');
 assert.equal(h.docks,20,'and still syncs the dock');
 clock.advance(10000);
 assert.equal(roots.length,0,'the 1 Hz history ticker does not commit the root either');
 assert.ok(h.paints>=29,'the ticker keeps sliding the painted chart, got '+h.paints);
 // The chart gets data: one commit (the sc-if swaps placeholder for the SVG).
 sample(app,1500,12);sample(app,500,14);
 app._commitPowerRefresh();
 assert.equal(roots.length,1,'the chart gaining data commits once');
 assert.deepEqual(Object.keys(roots[0].patch),['widgetRev']);
 assert.equal(roots[0].done,undefined,'with no callback');assert.equal(roots[0].uiOnly,true,'and without dirtying the 3D scene');
 h.render();
 for(let i=0;i<10;i++){sample(app,0,15+i);app._commitPowerRefresh()}
 clock.advance(3000);
 assert.equal(roots.length,1,'new samples on a chart that has data only paint');
 // DEMO: one commit each way. The badge follows the template's rule (either
 // key), so it is checked alone too, not only through the chart caption.
 h.model={stateKey:'demo',sourceClass:'demo',ariaLabel:'DEMO'};
 app._commitPowerRefresh();assert.equal(roots.length,2,'entering the demo commits once');
 h.render();app._commitPowerRefresh();clock.advance(2000);assert.equal(roots.length,2);
 h.model={stateKey:'ev',sourceClass:'live',ariaLabel:'EV'};
 app._commitPowerRefresh();assert.equal(roots.length,3,'leaving the demo commits once');
 h.render();h.model={stateKey:'ev',sourceClass:'demo',ariaLabel:'EV'};
 app._commitPowerRefresh();assert.equal(roots.length,4,'the DEMO badge alone changes the shape');
 // Closed: neither path commits, and the ticker stops.
 h=boot(html);({app,clock,roots}=h);
 h.render();app.state.focusedCardType=null;
 for(let i=0;i<10;i++)app._commitPowerRefresh();clock.advance(5000);
 assert.equal(roots.length,0,'with the popup closed nothing commits');
 // The painter covers what the popup renders that the board card does not.
 checkPainter(html);
}
function checkPainter(html){
 const ctx=vm.createContext({DCLogic:class{},document:{},window:{React:{Component:class{}}}});
 vm.runInContext(html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]+';globalThis.App=Component;',ctx);
 const app=Object.create(ctx.App.prototype);
 const node=(attrs={})=>({attrs,textContent:'',className:'',getAttribute(k){return k in this.attrs?this.attrs[k]:null},setAttribute(k,v){this.attrs[k]=String(v)}});
 const stage=node({'aria-label':'old'}),peak=node();peak.textContent='9';
 const nodes={'.hv-power-stage':stage,'.hv-power-history-peak':peak};
 const card=node({'data-power-size':'popup'});
 card.classList={contains:()=>true,remove(){},add(){}};
 card.querySelector=(sel)=>nodes[sel]||null;card.querySelectorAll=(sel)=>nodes[sel]?[nodes[sel]]:[];
 app._powerLean=true;app._widgetNodes=()=>[card];app._renderPowerGraphic=()=>{};
 app._powerModel=()=>({tone:'ev',ariaLabel:'EV now',stateKey:'ev',sourceClass:'live'});
 app._powerHistory=[];
 app._updatePowerCards(1000);
 assert.equal(stage.attrs['aria-label'],'EV now','the popup stage label is painted');
 assert.equal(peak.textContent,'—','the peak is painted while the chart has no path (placeholder shown)');
}
const mutate=(from,to)=>{assert.ok(source.includes(from),'control anchor not found: '+from.slice(0,60));return source.replace(from,()=>to)};
check(source);
assert.throws(()=>check(mutate("if (this.state.focusedCardType === 'power') this._refreshPowerPopup();",
 "if (this.state.focusedCardType === 'power') this._uiOnlySetState({ widgetRev: (this.state.widgetRev || 0) + 1 });")),'bus refresh back to a root commit per tick');
assert.throws(()=>check(mutate('        this._updatePowerCards(performance.now());\n        this._refreshPowerPopup();',
 '        this._updatePowerCards(performance.now());\n        this._uiOnlySetState((s) => ({ widgetRev: (s.widgetRev || 0) + 1 }));')),'history ticker back to a root commit per second');
assert.throws(()=>check(mutate('    if (shape === this._powerPopupShape) return;\n','')),'no shape comparison');
assert.throws(()=>check(mutate('    if (shape === this._powerPopupShape) return;\n','    return;\n')),'shape changes never commit');
assert.throws(()=>check(mutate('      this._powerPopupShape = this._powerPopupShapeOf(view);\n','')),'render no longer records the drawn shape');
assert.throws(()=>check(mutate("view.powerSourceClass === 'demo' || view.powerStateKey === 'demo' ? 1 : 0].join('|');","0].join('|');")),'DEMO badge left out of the shape');
assert.throws(()=>check(mutate('view.powerHistoryHasData ? 1 : 0, view.powerHistoryLabel,','0, 0,')),'chart data left out of the shape');
assert.throws(()=>check(mutate("      if (stage && (force || stage.getAttribute('aria-label') !== model.ariaLabel)) stage.setAttribute('aria-label', model.ariaLabel);\n",'')),'popup stage label no longer painted');
assert.throws(()=>check(mutate("if (historyPath || el.querySelector('.hv-power-history-peak')) {",'if (historyPath) {')),'peak painted only with a chart path');
console.log('PASS POWER popup refreshes paint instead of committing the root, and commit once per shape change + negative controls');
