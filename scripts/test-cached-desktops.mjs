import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const html=readFileSync(new URL('../index.html',import.meta.url),'utf8');
function check(source){
const context=vm.createContext({DCLogic:class{},window:{React:{memo:f=>f,createElement:(type,props,child)=>({type,props,child})},__dcUpdate(){},__dcRegistry:{HavalCachedBoard:{tpl(){}}}},document:{getElementById:()=>({innerHTML:'{{ leftWidgets }}'})}});
vm.runInContext(source.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]+';globalThis.App=Component;',context);
const app=Object.create(context.App.prototype);app._desktops=[{id:'a'},{id:'b'}];app._desktopIndex=0;
const a=app._desktopBoards({leftWidgets:1})[0];
app._desktopIndex=1;app._desktopBoards({leftWidgets:2});
app._deskSlide={};app._desktopIndex=0;
const pages=app._desktopBoards({leftWidgets:3});
assert.equal(pages.length,2);assert.equal(pages[0].child.props.view,a.child.props.view);
assert.equal(pages[1].props['data-desktop-active'],'false');
app._deskSlide=null;assert.equal(app._desktopBoards({leftWidgets:4})[0].child.props.view.leftWidgets,4);
app._desktops=[{id:'a'}];assert.equal(app._desktopBoards({leftWidgets:5}).length,1);assert.equal(app._desktopBoardsCache.has('b'),false);
}
check(html);
assert.throws(()=>check(html.replace('if (!cached || !this._deskSlide)', 'if (true)')));
console.log('PASS retention, slide freeze, active refresh, deletion + negative control');
