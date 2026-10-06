import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const html=readFileSync(new URL('../index.html',import.meta.url),'utf8');
function check(source){
 const classes=new Set();
 const body={classList:{
  toggle:(name,on)=>on?classes.add(name):classes.delete(name),
  remove:(...names)=>names.forEach(name=>classes.delete(name)),
 },style:{setProperty(){throw Error('inherited animation style')},removeProperty(){throw Error('inherited animation style')}}};
 const ctx=vm.createContext({DCLogic:class{},window:{},clearTimeout(){},document:{body,addEventListener(){},querySelectorAll:()=>[]}});
 vm.runInContext(source.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]+';globalThis.App=Component;',ctx);
 const app=Object.create(ctx.App.prototype);const style={};app._deskSlideDom={back:{style}};app._deskSlideWidth=()=>1920;
 for(const dir of [-1,1]){
  app._deskSlideShapeBack(dir);
  assert.equal(style.width,'100%','incoming crop matches viewport');assert.equal(style.left,'0px');assert.equal(style.maskImage,'');
  assert.equal(Math.abs(app._deskBackX(0,dir)),1920);assert.equal(Math.abs(app._deskBackX(960,dir)),960);assert.equal(Math.abs(app._deskBackX(1920,dir)),0);
 }
 app._deskSlideSetVars(0,0);assert.equal(classes.has('hv-desk-faded'),true,'drag fades chrome');
 app._deskSlideSetVars(-960,null);assert.equal(classes.has('hv-desk-faded'),true,'movement keeps fade endpoint');
 app._deskSlideSetVars(-1920,1);assert.equal(classes.has('hv-desk-faded'),false,'commit/cancel restores chrome');
 app._deskSlideSetVars(0,0);app._deskSlideClearDots=()=>{};app._suppressNextWidgetClick=()=>{};
 app._deskSlideTeardown();assert.equal(classes.has('hv-desk-faded'),false,'teardown clears fade state');
 assert.match(source,/body\.hv-desk-sliding\.hv-desk-faded[^{}]+\{ opacity: 0 !important; \}/,'fade class targets chrome');
 assert.match(source,/opacity: 1 !important;\s*transition: opacity 160ms ease;/,'CSS keeps animated restoration');
 assert.match(source,/body\.hv-desk-animating[^{}]+\{ transition: opacity 220ms ease; \}/,'settle animation retained');
 const handlers={},seen=[];const el={addEventListener:(name,fn)=>handlers[name]=fn};
 for(const [method,label] of [['Start','start'],['Move','move'],['End','end'],['Cancel','cancel']])app['_onDesktopGesture'+method]=ev=>seen.push(label);
 app._bindDesktopGestures(el);
 for(const event of ['touchstart','touchmove','touchend','touchcancel','mousedown','mousemove','mouseup','mouseleave'])handlers[event]({});
 assert.equal(seen.join(','),'start,move,end,cancel,start,move,end,cancel');assert.equal(app._desktopGestureEl,el);
 app.state={};app._pointInDesktopSwipeZone=()=>false;app._isDesktopSwipeBlockedTarget=()=>true;app._widgetAddingOn=()=>false;
 app._onDesktopGestureStart=ctx.App.prototype._onDesktopGestureStart;
 let holds=0;app._clearWidgetHold=()=>{holds++};app._cancelCenterHold=()=>{};app._suppressNextWidgetClick=()=>{};
 let moved,ended,prevented=0;
 app._onDesktopGestureMove=ev=>{moved=app._eventClientPoint(ev).x;app._desktopGesture.lastX=moved};
 app._onDesktopGestureEnd=ev=>{ended=app._eventClientPoint(ev).x;app._desktopGesture=null};
 const event=(type,x1,x2)=>({type,touches:x2==null?[]:[{clientX:x1,clientY:200},{clientX:x2,clientY:220}],target:{},cancelable:true,preventDefault:()=>prevented++,stopImmediatePropagation(){}});
 // On the car the zone test fails. Two fingers must reach OrbitControls.
 app._onTwoFingerDesktopGesture(event('touchstart',1400,1600));
 assert.ok(!app._desktopGesture,'two fingers on the car do not start a desktop swipe');
 app._onTwoFingerDesktopGesture(event('touchmove',900,1100));
 app._onTwoFingerDesktopGesture(event('touchend',0,null));
 assert.equal(app._twoFingerDesktopActive,false);assert.equal(prevented,0,'car pan is not captured');assert.equal(holds,0);
 // Off the car, a blocked control still switches desktops from the midpoint.
 prevented=0;
 app._pointInDesktopSwipeZone=()=>true;
 app._onTwoFingerDesktopGesture(event('touchstart',1400,1600));
 assert.equal(app._desktopGesture.x,1500,'centroid starts over blocked controls');
 app._onTwoFingerDesktopGesture(event('touchmove',900,1100));assert.equal(moved,1000);
 app._onTwoFingerDesktopGesture(event('touchend',0,null));assert.equal(ended,1000,'lifting finger keeps last centroid');
 assert.equal(app._twoFingerDesktopActive,false);assert.equal(prevented,3);assert.equal(holds,1);

}
check(html);
assert.throws(()=>check(html.replace("b.width = '100%';","b.width = '130%';")),'crop negative control');
assert.throws(()=>check(html.replace("['touchmove', h.move]","['touchmove', h.end]")),'touchmove negative control');
assert.throws(()=>check(html.replace('(touches[0].clientX + touches[1].clientX) / 2','touches[0].clientX')),'centroid negative control');
assert.throws(()=>check(html.replace('!this._pointInDesktopSwipeZone(p.x, p.y, ev) || ','')),'two-finger car-zone negative control');
assert.throws(()=>check(html.replace("'hv-desk-faded', fade === 0","'hv-desk-faded', fade === 1")),'fade endpoint negative control');
assert.throws(()=>check(html.replace("'hv-desk-animating', 'hv-desk-faded'","'hv-desk-animating'")),'teardown negative control');
assert.throws(()=>check(html.replace('.hv-pop-shrink { opacity: 0 !important; }','.hv-pop-shrink { opacity: 1 !important; }')),'fade CSS negative control');
assert.throws(()=>check(html.replace('transition: opacity 160ms ease;','transition: none;')),'fade transition negative control');
assert.throws(()=>check(html.replace('.hv-pop-shrink { transition: opacity 220ms ease; }','.hv-pop-shrink { transition: none; }')),'settle transition negative control');
console.log('PASS wallpaper crop, scoped animated fade, teardown, native touch/mouse gestures + negative controls');
