import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync(new URL('../index.html',import.meta.url),'utf8');
function check(html){
 const ctx=vm.createContext({DCLogic:class{},window:{React:{createElement:(type,props)=>({type,props})}}});
 vm.runInContext(html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]+';globalThis.App=Component;',ctx);
 const app=Object.create(ctx.App.prototype),panel={keys:['desktopStudioClass','items']};app._memoTemplate=()=>panel;
 const open=app._closedPanel('studioPanelView',true,{desktopStudioClass:'on',items:[1]}).props.view;
 const closed=app._closedPanel('studioPanelView',false,{desktopStudioClass:'',items:[]}).props.view;
 assert.notEqual(open,closed);assert.equal(closed.desktopStudioClass,'');assert.equal(closed.items,open.items,'closing preserves animated contents');
 assert.equal(app._closedPanel('studioPanelView',false,{items:[2]}).props.view,closed,'closed panel freezes');
 assert.equal(app._closedPanel('studioPanelView',true,{items:[3]}).props.view.items[0],3,'reopen refreshes');
 let commits=0;app.state={widgetRev:1};app.setState=()=>commits++;app._deskSlide={};
 app._uiOnlySetState(s=>({widgetRev:s.widgetRev+1}));assert.equal(commits,0,'visual telemetry waits');
 app._uiOnlySetState({sunroofOpen:50});assert.equal(commits,1,'vehicle state remains immediate');
 app._uiOnlySetState({widgetRev:2},()=>{});assert.equal(commits,2,'commit callbacks keep their semantics');
 app._deskSlide=null;app._uiOnlySetState({widgetRev:2});assert.equal(commits,3,'refresh resumes');
}
check(source);
assert.throws(()=>check(source.replace('if (!panel.view || open)', 'if (true)')));
assert.throws(()=>check(source.replace('if (this._deskSlide && !done)', 'if (false && !done)')));
console.log('PASS hidden panel freeze, exit contents, reopening, visual-only deferral + negative controls');

const clocks=readFileSync(new URL('../assets/clock-elements.js',import.meta.url),'utf8');
function checkClock(text){
 const body=text.match(/visible: function \(element\) \{([\s\S]*?)\n    \},/)[1];
 const ctx=vm.createContext({document:{visibilityState:'visible'},root:{getComputedStyle:()=>({display:'block',visibility:'visible'})}});
 const visible=vm.runInContext('(function(element){'+body+'})',ctx);
 const node={isConnected:true,getClientRects:()=>[{}],closest:s=>s.includes('.hv-desktop-studio')?{}:null};
 assert.equal(visible(node),false,'closed gallery clock is paused');
 node.closest=()=>null;assert.equal(visible(node),true,'open gallery clock resumes');
}
checkClock(clocks);
assert.throws(()=>checkClock(clocks.replace('.hv-desktop-studio:not(.on)', '.missing-panel')));
