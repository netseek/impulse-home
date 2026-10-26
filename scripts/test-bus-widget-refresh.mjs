// Bus-driven widget refreshes (tyres, navigation) must never reach a root
// commit when the child desktop stage can refresh alone. Measured in headless
// Chrome at 4x CPU throttle: one root commit per tpms_status frame, while every
// other simulated bus key produced none. A root commit costs ~197 ms on the car.
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync(new URL('../index.html',import.meta.url),'utf8');
const TPMS='{2.48922,24.0,2.48922,24.0,2.28335,23.0,2.48922,24.0}';
function boot(html){
 // Fake clock: timers run only when the test advances time, in due order.
 const timers=new Map();let now=0,seq=0;
 const clock={
  count:(ms)=>[...timers.values()].filter(t=>t.ms===ms).length,
  advance(ms){const end=now+ms;for(;;){
   let next=null,nextId=0;for(const [id,t] of timers)if(t.at<=end&&(!next||t.at<next.at)){next=t;nextId=id}
   if(!next)break;now=next.at;timers.delete(nextId);next.fn()}now=end}};
 // The real stage runs its real componentDidUpdate, so a stage-only refresh
 // is seen to sync the dock the way it does in the app.
 class ReactComponent{constructor(props){this.props=props;this.state={}}
  setState(update){const before=this.props;this.state={...this.state,...update(this.state,this.props)};if(this.componentDidUpdate)this.componentDidUpdate(before)}}
 const ctx=vm.createContext({DCLogic:class{},
  setTimeout:(fn,ms)=>{timers.set(++seq,{fn,ms,at:now+ms});return seq},clearTimeout:(id)=>{timers.delete(id)},
  cancelAnimationFrame(){},document:{body:{classList:{remove(){}}},querySelectorAll:()=>[]},
  window:{React:{Component:ReactComponent,createElement:(type,props)=>({type,props})}}});
 vm.runInContext(html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]+';globalThis.App=Component;',ctx);
 const K=vm.runInContext('({tireStatus:CAR_SIGNALS.tireStatus,tireWarnings:CAR_SIGNALS.tireWarnings.slice(),navDirections:CAR_SIGNALS.navDirections})',ctx);
 const app=Object.create(ctx.App.prototype);
 app.state={widgetRev:0,shellMode:'appCar'};app._shellMode='appCar';
 app._effectiveWidgetTheme=()=> 'dark';app._memoTemplate=()=>({keys:['leftWidgetItems']});app._desktopBoards=f=>f;
 app._widgetRenderFields=()=>({leftWidgetItems:['latest']});
 app._hasWidgetType=()=>true;app._deskSlideClearDots=()=>{};app._suppressNextWidgetClick=()=>{};
 app._tireWarningsRaw={};app._tireRefreshTimer=0;app._tireRefreshPending=false;app._navRefreshTimer=0;app._navRefreshPending=false;
 const h={app,K,ctx,clock,roots:[],docks:0,paints:()=>app._desktopStagePaints||0};
 app._syncDockIndicators=()=>{h.docks++};
 app.setState=(patch,done)=>{h.roots.push({patch,done})};
 const el=app._desktopStage({leftWidgetItems:['initial']});
 h.stage=new el.type(el.props);h.stage.componentDidMount();
 return h;
}
const navFrame=(turn,distanceM)=>JSON.stringify({active:true,street:'Main',turn,distance_m:distanceM,remaining_m:5000,remaining_s:600,eta:'10:35'});
function check(html){
 // --- A1: tyres ---
 let h=boot(html);let {app,K,clock,roots}=h;
 for(let i=0;i<50;i++)app._applyCarSignal(K.tireStatus,TPMS);
 K.tireWarnings.forEach((key)=>app._applyCarSignal(key,'{0,0,1,0}'));
 assert.equal(roots.length,0,'a tyre burst never reaches a root commit');
 assert.equal(h.paints(),0,'the refresh trails: nothing before the window closes');
 assert.equal(clock.count(250),1,'one pending refresh per burst');
 assert.equal(clock.count(120050),1,'one stale timer, re-armed by every frame');
 clock.advance(250);
 assert.equal(h.paints(),1,'the trailing refresh still happens, once');assert.equal(roots.length,0);
 assert.equal(h.docks,1,'the stage refresh syncs the dock itself (no callback needed)');
 clock.advance(10000);assert.equal(h.paints(),1,'nothing more without a new frame');
 for(let i=0;i<5;i++)app._applyCarSignal(K.tireStatus,TPMS);
 clock.advance(250);assert.equal(h.paints(),2,'the next burst gets its own refresh');
 clock.advance(120050);
 assert.equal(h.paints(),3,'the live to stale flip still refreshes with no frame to trigger it');assert.equal(roots.length,0);
 const before=h.paints();
 for(let i=0;i<100;i++){app._applyCarSignal(K.tireStatus,TPMS);clock.advance(20)}
 assert.ok(h.paints()-before>=1&&h.paints()-before<=9,'100 frames in 2 s cost at most one refresh per 250 ms, got '+(h.paints()-before));
 assert.equal(roots.length,0);
 // A popup is open: no child stage, so the root path is the fallback. Still a
 // UI-only write (the scene is not dirtied) and still with no callback.
 clock.advance(300);app.state.focusedCardType='status';const rootsBefore=roots.length;
 app._applyCarSignal(K.tireStatus,TPMS);clock.advance(250);
 assert.equal(roots.length-rootsBefore,1,'open popup falls back to one root commit');
 assert.equal(roots[roots.length-1].done,undefined,'the fallback carries no callback (componentDidUpdate syncs the dock)');
 assert.equal(app._uiOnlyStateWrite,true,'the fallback does not dirty the 3D scene');
 // A desktop slide drops the refresh; teardown refreshes the stage and the dock.
 h=boot(html);({app,K,clock,roots}=h);
 app._deskSlide={};app._applyCarSignal(K.tireStatus,TPMS);clock.advance(250);
 assert.equal(h.paints()+roots.length,0,'dropped while a desktop slide is running');
 app._desktopBoardsCache=new Map();app._deskSlideTeardown();
 assert.equal(h.paints(),1,'slide teardown refreshes the widgets');assert.equal(h.docks,1,'and re-syncs the dock');assert.equal(roots.length,0);
 // Unmount stops it, even where only the root path would still be reachable.
 h=boot(html);({app,K,clock,roots}=h);
 app.state.focusedCardType='status';app._applyCarSignal(K.tireStatus,TPMS);app._unmounted=true;clock.advance(250);
 assert.equal(roots.length+h.paints(),0,'a pending refresh does nothing after unmount');
 app._applyCarSignal(K.tireStatus,TPMS);assert.equal(clock.count(250),0,'no new refresh is scheduled after unmount');
 assert.match(h.ctx.App.prototype.componentWillUnmount.toString(),/clearTimeout\(this\._tireRefreshTimer\)/,'unmount clears the pending refresh');
 assert.match(h.ctx.App.prototype.componentDidUpdate.toString(),/^    this\._syncDockIndicators\(\);/m,'root componentDidUpdate syncs the dock');
 // --- A2: navigation ---
 h=boot(html);({app,K,clock,roots}=h);
 app._applyNavDirections(navFrame('TURN_LEFT',500));
 assert.equal(h.paints(),1,'an identity change refreshes immediately');assert.equal(roots.length,0,'and not through a root commit');
 assert.equal(h.docks,1);
 for(let m=499;m>=470;m--)app._applyNavDirections(navFrame('TURN_LEFT',m));
 assert.equal(clock.count(250),1,'distance-only updates share one trailing refresh');
 clock.advance(250);assert.equal(h.paints(),2);assert.equal(roots.length,0,'guidance distance never reaches a root commit');
 app._applyNavDirections(navFrame('TURN_RIGHT',460));assert.equal(h.paints(),3);assert.equal(roots.length,0);
 app.state.focusedCardType='navigation';
 app._applyNavDirections(navFrame('TURN_LEFT',455));
 assert.equal(roots.length,1,'a focused card falls back to one root commit');
 assert.equal(roots[0].done,undefined,'with no callback');assert.equal(app._uiOnlyStateWrite,true,'and without dirtying the 3D scene');
 app.state.focusedCardType=null;app._hasWidgetType=()=>false;const docks=h.docks,paints=h.paints();
 app._applyNavDirections(navFrame('STRAIGHT',450));
 assert.equal(h.docks-docks,1,'without a navigation widget only the dock syncs');
 assert.equal(h.paints(),paints);assert.equal(roots.length,1);
 app._hasWidgetType=()=>true;app._deskSlide={};
 app._applyNavDirections(navFrame('TURN_LEFT',440));
 assert.equal(h.paints(),paints,'dropped while a desktop slide is running');assert.equal(roots.length,1);
}
// The mutation must apply, otherwise a "control" would only prove the source did not change.
const mutate=(from,to)=>{assert.ok(source.includes(from),'control anchor not found: '+from.slice(0,60));return source.replace(from,()=>to)};
const TIRE_REFRESH='this._uiOnlySetState((s) => ({ widgetRev: (s.widgetRev || 0) + 1 }));';
const OLD_TIRE_REFRESH='this.setState((s) => ({ widgetRev: (s.widgetRev || 0) + 1 }), () => this._syncDockIndicators());';
check(source);
// Tyres.
assert.throws(()=>check(mutate('if (this._unmounted || this._tireRefreshPending) return;','if (this._unmounted) return;')),'no coalescing');
assert.throws(()=>check(mutate('if (this._unmounted || this._tireRefreshPending) return;','if (this._tireRefreshPending) return;')),'no unmount guard on schedule');
assert.throws(()=>check(mutate('if (!this._unmounted) '+TIRE_REFRESH+'\n    }, 250);','if (!this._unmounted) '+OLD_TIRE_REFRESH+'\n    }, 250);')),'trailing refresh back to a root commit');
assert.throws(()=>check(mutate('if (!this._unmounted) '+TIRE_REFRESH+'\n    }, 250);',TIRE_REFRESH+'\n    }, 250);')),'no unmount guard on the trailing refresh');
assert.throws(()=>check(mutate('if (!this._unmounted) '+TIRE_REFRESH+'\n    }, 250);','if (false) '+TIRE_REFRESH+'\n    }, 250);')),'trailing refresh never fires');
assert.throws(()=>check(mutate('      if (this._unmounted) return;\n      '+TIRE_REFRESH+'\n    }, 120050);','      if (this._unmounted) return;\n      '+OLD_TIRE_REFRESH+'\n    }, 120050);')),'stale flip back to a root commit');
assert.throws(()=>check(mutate('    clearTimeout(this._tireRefreshTimer);\n    clearInterval(this._demoDoorTicker);','    clearInterval(this._demoDoorTicker);')),'unmount leaves the refresh pending');
// Navigation.
assert.throws(()=>check(mutate('      this._uiOnlySetState((s) => ({ widgetRev: (s.widgetRev || 0) + 1 }));\n    } else {\n      this._syncDockIndicators();',
 '      this._uiOnlyStateWrite = true;\n      this.setState((s) => ({ widgetRev: (s.widgetRev || 0) + 1 }), () => this._syncDockIndicators());\n    } else {\n      this._syncDockIndicators();')),'nav back to a root commit with a callback');
assert.throws(()=>check(mutate('    } else {\n      this._syncDockIndicators();\n    }\n  }\n\n  /**\n   * setState for a change the 3D scene cannot show.','    } else {\n    }\n  }\n\n  /**\n   * setState for a change the 3D scene cannot show.')),'nav without a widget stops syncing the dock');
// What the dropped / callback-free paths rely on.
assert.throws(()=>check(mutate('if (this._deskSlide && !done) {','if (false && !done) {')),'refresh no longer dropped during a slide');
assert.throws(()=>check(mutate('if (this._desktopBoardsCache) this._uiOnlySetState(','if (false) this._uiOnlySetState(')),'teardown no longer refreshes');
assert.throws(()=>check(mutate('          owner._syncDockIndicators();\n        }\n        render() {','        }\n        render() {')),'stage refresh no longer syncs the dock');
assert.throws(()=>check(mutate('    this._setStateCount = (this._setStateCount || 0) + 1;\n    this._syncDockIndicators();','    this._setStateCount = (this._setStateCount || 0) + 1;')),'root componentDidUpdate no longer syncs the dock');
console.log('PASS tyre and navigation bus refreshes stay off the root commit: coalesced, stage-only, slide/unmount-safe + negative controls');
