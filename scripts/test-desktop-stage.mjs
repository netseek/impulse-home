import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const source=readFileSync(new URL('../index.html',import.meta.url),'utf8');
function check(html){
 class ReactComponent{constructor(props){this.props=props}setState(update){this.state={...this.state,...update(this.state,this.props)}}}
 const ctx=vm.createContext({DCLogic:class{},window:{React:{Component:ReactComponent,createElement:(type,props)=>({type,props})}}});
 vm.runInContext(html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1]+';globalThis.App=Component;',ctx);
 const app=Object.create(ctx.App.prototype);app.state={widgetRev:0};app._effectiveWidgetTheme=()=> 'dark';
 app._memoTemplate=()=>({keys:['leftWidgetItems','widgetThemeLabel']});app._desktopBoards=fields=>fields;
 app._widgetRenderFields=()=>({leftWidgetItems:['latest']});app._syncDockIndicators=()=>{};
 let rootCommits=0;app.setState=()=>rootCommits++;
 const el=app._desktopStage({leftWidgetItems:['initial'],widgetThemeLabel:'CLARO'});
 const stage=new el.type(el.props);stage.componentDidMount();
 assert.equal(stage.render().leftWidgetItems[0],'initial');
 app._uiOnlySetState({widgetRev:1});assert.equal(rootCommits,0,'widget refresh avoids root');
 assert.equal(stage.render().leftWidgetItems[0],'latest');assert.equal(stage.render().widgetThemeLabel,'CLARO');
 stage.componentDidUpdate(stage.props);assert.equal(app._desktopStagePaints,1);
 const next=app._desktopStage({leftWidgetItems:['parent'],widgetThemeLabel:'AUTO'});stage.props=next.props;
 assert.equal(stage.render().leftWidgetItems[0],'parent','parent invalidation wins over stale child fields');
 app.state.focusedCardType='status';app._uiOnlySetState({widgetRev:2});assert.equal(rootCommits,1,'open popup uses full render');
 app.state.focusedCardType=null;app._desktopStageTheme='light';app._uiOnlySetState({widgetRev:2});assert.equal(rootCommits,2,'theme change updates root');
 app._desktopStageTheme='dark';app._uiOnlySetState({sunroofOpen:30});assert.equal(rootCommits,3,'vehicle state remains immediate');
 app._uiOnlySetState({widgetRev:3},()=>{});assert.equal(rootCommits,4,'callbacks preserve root lifecycle');
 stage.componentWillUnmount();assert.equal(app._desktopStageRef,null);
}
check(source);
assert.throws(()=>check(source.replace('if (!done && this._canRefreshDesktopStage())','if (false && this._canRefreshDesktopStage())')));
assert.throws(()=>check(source.replace('!s.focusedCardType && !s.desktopStudioOpen','true && !s.desktopStudioOpen')));
console.log('PASS isolated refresh, parent invalidation, popup/theme/command/callback fallback + negative controls');
