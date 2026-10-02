import fs from 'node:fs';import path from 'node:path';import http from 'node:http';
import {createRequire} from 'node:module';import {fileURLToPath} from 'node:url';import assert from 'node:assert/strict';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..'),require=createRequire(import.meta.url);
let chromium;try{({chromium}=require('playwright'));}catch{({chromium}=require(path.join(process.env.PLAYWRIGHT_NODE_MODULES||'','playwright')));}
const server=http.createServer((req,res)=>{const p=path.resolve(root,'.'+decodeURIComponent(req.url.split('?')[0]));if(!p.startsWith(root+path.sep)){res.writeHead(403).end();return;}try{res.setHeader('Content-Type',p.endsWith('.html')?'text/html':/\.m?js$/.test(p)?'text/javascript':p.endsWith('.png')?'image/png':p.endsWith('.svg')?'image/svg+xml':'application/json');res.end(fs.readFileSync(p));}catch{res.writeHead(404).end();}});
await new Promise(r=>server.listen(0,'127.0.0.1',r));let browser;
try{
 browser=await chromium.launch({channel:'chrome',headless:true});const page=await browser.newPage({viewport:{width:1320,height:1050}}),errors=[];
 page.on('pageerror',e=>errors.push(String(e)));
 await page.goto(`http://127.0.0.1:${server.address().port}/power-graphics-preview.html`);await page.waitForFunction(()=>window.graphicsReady);
 await page.locator('.art img').evaluateAll(async imgs=>{await Promise.all(imgs.map(i=>i.decode()));});
 const checks=await page.evaluate(()=>{
  const svgs=[...document.querySelectorAll('svg')],ids=[...document.querySelectorAll('[id]')].map(n=>n.id);
  const alpha=[...document.querySelectorAll('.art img')].map(img=>{const c=document.createElement('canvas');c.width=1200;c.height=2000;const g=c.getContext('2d');g.drawImage(img,0,0);const a=g.getImageData(0,0,1200,2000).data;let clear=0,opaque=0;for(let i=3;i<a.length;i+=4){if(a[i]===0)clear++;if(a[i]===255)opaque++;}return {clear,opaque};});
  return {unique:ids.length===new Set(ids).size,xmlValid:svgs.every(s=>!new DOMParser().parseFromString(s.outerHTML,'image/svg+xml').querySelector('parsererror')),alpha};
 });assert(checks.unique);assert(checks.xmlValid);assert(checks.alpha.every(a=>a.clear>100000&&a.opaque>10000));
 await page.screenshot({path:path.join(root,'docs/power-graphics-kit-dark.png'),fullPage:true});
 await page.locator('#state').selectOption('regen');await page.screenshot({path:path.join(root,'docs/power-graphics-kit-regen.png'),fullPage:true});
 await page.locator('#theme').click();await page.locator('#size').click();await page.screenshot({path:path.join(root,'docs/power-graphics-kit-compact-light.png'),fullPage:true});
 assert.equal(await page.locator('[data-layer="soc-label"]').count(),0);assert((await page.locator('.external-soc').allTextContents()).every(s=>s==='Battery 64%'));
 await page.locator('#size').click();
 await page.locator('#state').selectOption('stale');assert.equal(await page.locator('[data-chevron]').count(),0);assert.equal(await page.locator('[data-layer="soc-label"] text').allTextContents().then(x=>x.join(',')),'—,—,—');
 await page.locator('#state').selectOption('idle');for(const value of ['0','100']){await page.locator('#soc').evaluate((el,v)=>{el.value=v;el.dispatchEvent(new Event('input'));},value);const labels=await page.locator('[data-layer="soc-label"] text').allTextContents();assert(labels.every(x=>x===value+'%'));}
 assert.deepEqual(errors,[]);console.log('PASS: browser SVG/XML, unique IDs, real alpha, 0/100/stale states, dark/regen/compact-light screenshots.');
}finally{await browser?.close();await new Promise(r=>server.close(r));}
