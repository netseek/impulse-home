import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {normalizeState,moduleLevels,activeRoutes,sampleRoute,overlaySVG} from '../assets/power/graphics/graphics.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const manifest=JSON.parse(fs.readFileSync(path.join(root,'assets/power/graphics/manifest.json')));
assert.deepEqual(manifest.viewBox,[0,0,600,1000]);
assert.deepEqual(Object.keys(manifest.variants).sort(),['hev2','phev19','phev34']);
for(const v of Object.values(manifest.variants)){
 assert.equal(v.awd,v.key==='phev34');
 assert.equal(v.battery.moduleCount,({hev2:3,phev19:5,phev34:6})[v.key]);
 const png=fs.readFileSync(path.join(root,'assets/power/graphics',v.chassis));
 assert.equal(png.readUInt32BE(16),1200);assert.equal(png.readUInt32BE(20),2000);assert.equal(png[25],6,'RGBA PNG');
 for(const [id,route] of Object.entries(v.routes)){
  assert.deepEqual(route.points[0],v.anchors[route.from],id+' start');
  assert.deepEqual(route.points.at(-1),v.anchors[route.to],id+' end');
  for(const [x,y] of route.points){assert(x>=0&&x<=600);assert(y>=0&&y<=1000);}
  const forward=sampleRoute(route.points,0),backward=sampleRoute([...route.points].reverse(),0);
  assert.deepEqual([forward.x,forward.y],v.anchors[route.from]);assert.deepEqual([backward.x,backward.y],v.anchors[route.to]);
 }
 for(const bad of [null,undefined,NaN,Infinity,-1,101,'50','',false])assert.equal(normalizeState(v,{soc:bad}).soc,null);
 for(const soc of [0,0.1,16.7,33.3,64,99.9,100]){
  const levels=moduleLevels(soc,v.battery.moduleCount);assert(Math.abs(levels.reduce((a,b)=>a+b,0)-soc/100*levels.length)<1e-9);
  assert(levels.every(n=>n>=0&&n<=1));assert(levels.filter(n=>n>0&&n<1).length<=1);
 }
 assert(activeRoutes(v,{front:1,rear:1}).every(r=>r.direction===1));
 assert(activeRoutes(v,{front:-1,rear:-1}).every(r=>r.direction===-1));
 assert.equal(activeRoutes(v,{front:1,rear:1,flowFresh:false}).length,0);
 assert.equal(activeRoutes(v,{engineActive:true}).length,0,'engine on cannot invent power route');
 assert.equal(normalizeState(v,{soc:60,socFresh:false}).soc,null);
 assert.equal(normalizeState(v,{front:2,rear:'1'}).front,0);
 if(!v.awd){assert(!('rearMotor' in v.anchors));assert(!Object.keys(v.routes).some(id=>id.startsWith('rear')));assert.equal(normalizeState(v,{rear:1}).rear,0);}
 if(v.key==='hev2')assert.equal(normalizeState(v,{charging:true}).charging,false);
 assert(!overlaySVG(v,{soc:64},{showSocLabel:false}).includes('data-layer="soc-label"'));
 const idle=overlaySVG(v,{soc:null});assert(idle.includes('>—</text>'));assert(!idle.includes('data-chevron='));
 assert(overlaySVG(v,{soc:0}).includes('>0%</text>'));assert(overlaySVG(v,{soc:100}).includes('>100%</text>'));
 assert.throws(()=>overlaySVG(v,{}, {idPrefix:'bad"id'}));
}
console.log('PASS: variants, PNG format, anchor endpoints, SOC boundaries/partial modules, stale states, directions, FWD restrictions and SVG prefixes.');
