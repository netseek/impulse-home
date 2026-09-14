// Deterministic offline bake. No app integration; original GLB remains untouched.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import {createRequire} from 'node:module';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';
import {overlaySVG} from '../assets/power/graphics/graphics.mjs';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const require=createRequire(import.meta.url);
let chromium;try{({chromium}=require('playwright'));}catch{({chromium}=require(path.join(process.env.CODEX_NODE_MODULES||'', 'playwright')));}
const out=path.join(root,'assets/power/graphics');fs.mkdirSync(out,{recursive:true});
const server=http.createServer((req,res)=>{
  const p=path.resolve(root,'.'+decodeURIComponent(req.url.split('?')[0]));
  if(!p.startsWith(root+path.sep)){res.writeHead(403).end();return;}
  try{res.setHeader('Content-Type',p.endsWith('.html')?'text/html':/\.m?js$/.test(p)?'text/javascript':p.endsWith('.png')?'image/png':'application/octet-stream');res.end(fs.readFileSync(p));}catch{res.writeHead(404).end();}
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));let browser;
try{
  browser=await chromium.launch({channel:'chrome',headless:true,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
  const page=await browser.newPage();
  await page.goto(`http://127.0.0.1:${server.address().port}/scripts/power-graphics-render.html`);
  await page.waitForFunction(()=>window.ready||window.failure,null,{timeout:60000});
  const failure=await page.evaluate(()=>window.failure);if(failure)throw Error(failure);
  const manifest={version:1,viewBox:[0,0,600,1000],rasterSize:[1200,2000],front:'top',
    provenance:'Orthographic export of repository procedural model; schematic packaging, not OEM engineering drawings.',
    source:'assets/power/haval-powertrain.glb',sourceSha256:createHash('sha256').update(fs.readFileSync(path.join(root,'assets/power/haval-powertrain.glb'))).digest('hex'),
    variants:{}};
  for(const key of ['phev19','phev34','hev']){
    const {png,...v}=await page.evaluate(key=>window.bake(key),key);
    v.chassis=`${v.key}-chassis.png`;
    const a=v.anchors,mid=(a.batteryFront[1]+a.frontMotor[1])/2;
    v.routes={front:{from:'batteryFront',to:'frontMotor',points:[a.batteryFront,[a.batteryFront[0],mid],[a.frontMotor[0],mid],a.frontMotor]},
      frontLeft:{from:'frontMotor',to:'wheelFL',points:[a.frontMotor,a.wheelFL]},
      frontRight:{from:'frontMotor',to:'wheelFR',points:[a.frontMotor,a.wheelFR]},
      engine:{from:'engine',to:'frontMotor',points:[a.engine,a.frontMotor]}};
    if(v.awd){const m=(a.batteryRear[1]+a.rearMotor[1])/2;
      v.routes.rear={from:'batteryRear',to:'rearMotor',points:[a.batteryRear,[300,m],[a.rearMotor[0],m],a.rearMotor]};
      v.routes.rearLeft={from:'rearMotor',to:'wheelRL',points:[a.rearMotor,a.wheelRL]};
      v.routes.rearRight={from:'rearMotor',to:'wheelRR',points:[a.rearMotor,a.wheelRR]};}
    manifest.variants[v.key]=v;
    fs.writeFileSync(path.join(out,v.chassis),Buffer.from(png.split(',')[1],'base64'));
    fs.writeFileSync(path.join(out,`${v.key}-overlay.svg`),overlaySVG(v,{soc:null},{idPrefix:v.key}));
    for(const [name,dir] of [['drive',1],['regen',-1]])fs.writeFileSync(path.join(out,`${v.key}-${name}-sample.svg`),overlaySVG(v,{soc:64,front:dir,rear:dir},{idPrefix:`${v.key}-${name}`}));
  }
  fs.writeFileSync(path.join(out,'manifest.json'),JSON.stringify(manifest,null,2)+'\n');
  console.log('Baked three transparent chassis + aligned SVG overlays and manifest.');
}finally{await browser?.close();await new Promise(r=>server.close(r));}
