import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';
const html=readFileSync(new URL('../index.html',import.meta.url),'utf8');
function check(source){
 const body=source.split('const loop = () => {')[1].split('// Motion: advance reflection flow offset + roll wheels')[0];
 function run(loading,visible){
  let queued=0,work=0;
  const app={state:{loading},_shouldShowCar:()=>visible,camera:{}};
  vm.runInNewContext('(function(){'+body+'; work();}).call(app)',{app,performance:{now:()=>100},requestAnimationFrame:()=>++queued,loop(){},_lastT:90,work:()=>work++});
  assert.equal(queued,1,'UI loop remains scheduled');return work;
 }
 assert.equal(run(false,false),0,'hidden loaded scene skips animation and rendering');
 assert.equal(run(false,true),1,'visible scene resumes normally');
 assert.equal(run(true,false),1,'initial loading still completes');
}
check(html);
assert.throws(()=>check(html.replace('if (!this.state.loading && !this._shouldShowCar()) return;','')),'negative control');
console.log('PASS hidden 3D suspension, visible resume, boot + negative control');
