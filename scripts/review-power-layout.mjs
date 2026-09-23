// Render the production Power markup/CSS at each supported size, then exercise
// the full app's popup. CODEX_NODE_MODULES may point to a bundled Playwright.
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
let chromium;
try { ({ chromium } = require('playwright')); } catch {
  if (!process.env.CODEX_NODE_MODULES) throw Error('Install Playwright or set CODEX_NODE_MODULES to the directory containing it.');
  ({ chromium } = require(path.join(process.env.CODEX_NODE_MODULES, 'playwright')));
}
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8');
const script = html.match(/<script type="text\/x-dc" data-dc-script>([\s\S]*?)<\/script>/)[1];
const ctx = vm.createContext({ DCLogic: class {}, window: {}, performance, setTimeout, clearTimeout, console });
vm.runInContext(script + ';globalThis.App=Component;globalThis.keys=CAR_SIGNALS;', ctx);
const app = Object.create(ctx.App.prototype);
app.state = { modelTrim:'phev34' };
app._powerLive = { flow:'v1|hybrid|1|1|0', soc:'64', voltage:'360', current:'68.9' };
app._carSignalAt = Object.fromEntries(['powerFlow','batterySoc','batteryVoltage','batteryCurrent'].map(k=>[ctx.keys[k], Date.now()]));
const widget = html.match(/<sc-if value="\{\{ wg\.isPower \}\}"[^>]*>([\s\S]*?)<\/sc-if>/)[1];
const popupStart = html.indexOf('<sc-if value="{{ focusedCardIsPower }}"');
const popup = html.slice(html.indexOf('>',popupStart)+1, html.indexOf('<sc-if value="{{ focusedCardIsStatus }}"',popupStart)).replace(/<\/sc-if>\s*$/, '');
const styles = [...html.matchAll(/<style>([\s\S]*?)<\/style>/g)].map(m=>m[1]).join('\n');
const fixture = (size) => {
  const [w,h] = size.split('x').map(Number);
  app._powerLive.current = String(app._powerDemoKw(Date.now()) * 1000 / 360);
  const view = app._powerWidgetView({w,h});
  const model = app._powerModel(Date.now());
  return { ...view, powerGraphicMarkup:app._powerGraphicMarkup(model,size,'review-'+size.replace('x','-')),
    powerSource:'DEMO · SIMULATED · NOT VEHICLE', powerSourceClass:'demo', ...app._powerHistoryView(Date.now(), true) };
};
const fill = (markup, data) => markup.replace(/onClick="[^"]*"/g,'').replace(/\{\{\s*([^}]+?)\s*\}\}/g, (_,expr) => {
  let key = expr.trim();
  const inverse = key.startsWith('!'); if (inverse) key=key.slice(1);
  key = key.replace(/^wg\./,'').replace(/^focusedPower/,'power');
  const value = key in data ? data[key] : '';
  return inverse ? String(!value) : String(value);
}).replace('class="hv-power-canvas"','class="hv-power-canvas" data-graphic-key="fixture"').replace(/(<svg class="hv-power-overlay"[^>]*>)[\s\S]*?<\/svg>/,
  (_,open)=>open+data.powerGraphicMarkup+'</svg>');
const sizes = [[1,1],[1,2],[2,1],[3,1],[2,2],[3,2]];
const gallery = (theme, focus=false) => `<!doctype html><html><head><meta charset="utf-8"><base href="/"><style>${styles}
html,body{height:auto;overflow:auto;background:${theme==='light'?'#e7ecf0':'#101820'};font-family:Arial,sans-serif}
#hv-root{position:relative;inset:auto;min-height:100vh;height:auto;background:transparent;padding:28px;box-sizing:border-box;display:block}
.review-grid{display:flex;flex-wrap:wrap;gap:24px;align-items:flex-start}
.review-item{flex:none}.review-label{margin:0 0 10px;font:12px Arial;letter-spacing:.08em;color:${theme==='light'?'#53616b':'#a8bac8'}}
.review-card{position:relative;border:1px solid ${theme==='light'?'#c9d4da':'#34424c'};border-radius:18px;background:${theme==='light'?'#f8fafb':'#1c2831'};overflow:hidden}
.review-popup{width:960px;padding:18px}.review-popup .hv-power{color:${theme==='light'?'#243540':'#eaf2f8'}}
</style></head><body><main id="hv-root" class="${theme==='light'?'hv-widgets-light':'hv-widgets-dark'}"><div class="review-grid">${focus?`<div class="review-card review-popup"><p class="review-label">ENERGY FLOW · EXPANDED VIEW</p>${fill(popup,fixture('3x2'))}</div>`:sizes.map(([w,h])=>`<article class="review-item"><p class="review-label">${w} × ${h}</p><div class="review-card" style="width:${w*240+(w-1)*8}px;height:${h*180+(h-1)*8}px">${fill(widget,fixture(w+'x'+h))}</div></article>`).join('')}</div></main><script>document.querySelectorAll('sc-if').forEach(e=>{if(e.getAttribute('value')==='false')e.remove();else e.replaceWith(...e.childNodes)});</script></body></html>`;
const server = http.createServer((req,res)=>{
  const url = new URL(req.url,'http://localhost');
  if(url.pathname==='/battery-review'){
    let cards='';
    for(const trim of ['phev19','phev34','hev2'])for(const [label,flow,soc] of [['Standby','v1|idle|0|0|0','64'],['Supplying','v1|ev|0|1|0','64'],['Recovering','v1|regen|0|-1|0','64'],['Full','v1|idle|0|0|0','100'],['Empty','v1|idle|0|0|0','0'],['Unavailable',null,null],['Charging','v1|charge|0|0|0','64']]){
      if(trim==='hev2'&&label==='Charging')continue;
      app.state.modelTrim=trim;app._powerLive.flow=flow;app._powerLive.soc=soc;
      const model=app._powerModel(Date.now());
      cards+=`<article><h3>${trim.toUpperCase()} · ${label}</h3><div class="hv-power-canvas" data-graphic-key="fixture" style="height:350px"><div class="hv-power-art"><img class="hv-power-chassis" src="${model.graphic.asset}" style="${model.graphic.artStyle}"></div><svg class="hv-power-overlay" viewBox="0 0 350 770">${app._powerGraphicMarkup(model,'popup','matrix-'+trim+'-'+label)}</svg></div></article>`;
    }
    app.state.modelTrim='phev34';app._powerLive.flow='v1|hybrid|1|1|0';app._powerLive.soc='64';
    res.setHeader('Content-Type','text/html; charset=utf-8');res.end(`<html><head><meta charset="utf-8"><base href="/"><style>${styles}html,body{height:auto;overflow:auto;background:#18232b;color:#d9e5ec;font:12px Arial}main{display:grid;grid-template-columns:repeat(7,210px);gap:12px;padding:16px}article{background:#202e38;border-radius:12px;padding:12px}h3{font:12px Arial}</style></head><body><main>${cards}</main></body></html>`);return;
  }
  if(url.pathname==='/review'){res.setHeader('Content-Type','text/html');res.end(gallery(url.searchParams.get('theme')||'dark',url.searchParams.has('popup')));return;}
  const file=path.resolve(root,'.'+decodeURIComponent(url.pathname));
  if(!file.startsWith(root+path.sep)){res.writeHead(403).end();return;}
  try{const ext=path.extname(file);res.setHeader('Content-Type',({'.html':'text/html','.js':'application/javascript','.png':'image/png','.css':'text/css','.json':'application/json'})[ext]||'application/octet-stream');res.end(fs.readFileSync(file));}catch{res.writeHead(404).end();}
});
await new Promise(r=>server.listen(0,'127.0.0.1',r));
let browser;
try{
  browser=await chromium.launch({channel:'chrome',headless:true,args:['--use-angle=swiftshader','--enable-unsafe-swiftshader']});
  const page=await browser.newPage({viewport:{width:1540,height:1100}});
  const base=`http://127.0.0.1:${server.address().port}`;
  await page.goto(base+'/battery-review');
  await page.locator('.hv-power-chassis').last().waitFor();
  await page.waitForFunction(()=>Array.from(document.images).every(i=>i.complete));
  await page.screenshot({path:path.join(root,'docs/power-battery-states.png'),fullPage:true});
  for(const theme of ['dark','light']){
    for(const popup of [false,true]){
      await page.goto(`${base}/review?theme=${theme}${popup?'&popup':''}`);
      await page.waitForFunction(()=>Array.from(document.images).every(i=>i.complete));
      const target=popup?page.locator('.review-popup'):page.locator('#hv-root');
      await target.screenshot({path:path.join(root,`docs/power-redesign-${popup?'popup':'sizes'}-${theme}.png`)});
    }
  }
  const transparentChassis=await page.evaluate(()=>{
    const images=[...document.querySelectorAll('.hv-power-chassis')];
    return images.map(image=>{
      const canvas=document.createElement('canvas'),ctx=canvas.getContext('2d');
      canvas.width=image.naturalWidth;canvas.height=image.naturalHeight;ctx.drawImage(image,0,0);
      const corner=ctx.getImageData(0,0,1,1).data[3];
      const vehicle=ctx.getImageData((canvas.width/2)|0,((canvas.height * 0.16)|0),1,1).data[3];
      return {corner,vehicle};
    });
  });
  if(!transparentChassis.length||transparentChassis.some(sample=>sample.corner!==0||sample.vehicle===0)){
    throw Error('approved chassis crop lost its transparent ground: '+JSON.stringify(transparentChassis));
  }
  await page.setViewportSize({width:1920,height:720});
  page.on('pageerror',e=>console.error('APP ERROR:',e.message));
  await page.goto(`${base}/index.html?demo=1`);
  await page.waitForFunction(()=>window.__app,{timeout:60000});
  await page.evaluate(()=>{
    window.HavalSplash?.skip?.();
    const app=window.__app,realStatus=app._powerStatus.bind(app);
    app._powerStatus=()=>({key:'live',source:'LIVE · REPORTED FLOW',flowFresh:true,voltageFresh:true,currentFresh:true});
    app._powerLive={flow:'v1|hybrid|1|1|1',soc:'64',voltage:'360',current:'68.9'};
    app._carSignalAt=app._carSignalAt||{};
    app._carSignalAt['car.ev_info.cur_battery_power_percentage']=Date.now();
    app._openFocusedCard('power');
    app._reviewRestorePowerStatus=()=>{app._powerStatus=realStatus;};
  });
  await page.waitForFunction(()=>{
    const canvas=document.querySelector('.hv-power[data-power-size="popup"] .hv-power-canvas');
    return canvas&&canvas.getBoundingClientRect().width>0;
  });
  await page.evaluate(()=>window.__app._updatePowerCards(performance.now()));
  await page.waitForFunction(()=>document.querySelectorAll('.hv-power[data-power-size="popup"] [data-module]').length===10);
  await page.waitForTimeout(500);
  const liveGraphic = await page.evaluate(()=>{
    const canvas=document.querySelector('.hv-power[data-power-size="popup"] .hv-power-canvas');
    return canvas ? {variant:canvas.dataset.powerVariant,running:canvas.classList.contains('is-running'),
      cells:canvas.querySelectorAll('[data-module]').length,routes:canvas.querySelectorAll('.hv-power-route.active').length} : null;
  });
  if(!liveGraphic||liveGraphic.variant!=='phev34'||!liveGraphic.running||liveGraphic.cells!==10||liveGraphic.routes!==8){
    throw Error('production Power graphic did not reach the expected live AWD state: '+JSON.stringify(liveGraphic));
  }
  const flowEdges=await page.evaluate(()=>{
    const svg=document.querySelector('.hv-power[data-power-size="popup"] .hv-power-overlay');
    // No filters: each animated frame repaints the SVG and a blur would be
    // re-rasterised every time. Glows are layered strokes instead.
    const running=[...svg.querySelectorAll('.hv-power-flow-track,.hv-power-flow-head,.hv-power-hub')]
      .filter(el=>el.getAnimations().some(a=>a.playState==='running')).length;
    return {filters:svg?.querySelectorAll('filter').length||0, visible:getComputedStyle(svg).overflow,
      routes:svg?.querySelectorAll('.hv-power-route.active').length||0, hubs:svg?.querySelectorAll('.hv-power-hub').length||0, running};
  });
  if(flowEdges.filters!==0||flowEdges.visible!=='visible'||flowEdges.routes!==8||flowEdges.hubs!==4
    ||flowEdges.running!==8*2+4){
    throw Error('wheel-end flow paint regression: '+JSON.stringify(flowEdges));
  }
  await page.screenshot({path:path.join(root,'docs/power-redesign-app-popup.png')});
  // Sample actual CSS animation at known times, rather than just checking its class.
  const pulseCheck=await page.evaluate(()=>{
    const app=window.__app;
    app._powerLive.flow='v1|charge|0|0|0';app._powerLive.soc='12';
    app._updatePowerCards(performance.now());
    const canvas=document.querySelector('.hv-power[data-power-size="popup"] .hv-power-canvas');
    const cells=[...canvas.querySelectorAll('.hv-power-cell-fill')];
    const active=cells.filter(c=>c.classList.contains('hv-power-cell-pulse'));
    const anim=active[0]?.getAnimations()[0];
    if(!anim)return {error:'charging pulse missing'};
    anim.pause();
    const values=[0,800,1600].map(t=>{anim.currentTime=t;return Number(getComputedStyle(active[0]).opacity);});
    const staticLevels=cells.map(c=>Number(c.style.getPropertyValue('--cell-level')));
    const filter=getComputedStyle(active[0]).filter,shadow=getComputedStyle(active[0]).boxShadow;
    const card=canvas.closest('.hv-power');card.style.display='none';app._syncPowerGraphicMotion(canvas);
    const paused=!canvas.classList.contains('is-running');card.style.display='';app._syncPowerGraphicMotion(canvas);
    return {active:active.length,values,staticLevels,filter,shadow,paused};
  });
  if(pulseCheck.error||pulseCheck.active!==1||Math.abs(pulseCheck.values[0]-.06)>.001||Math.abs(pulseCheck.values[1]-.2)>.001||Math.abs(pulseCheck.values[2]-.06)>.001||pulseCheck.filter!=='none'||pulseCheck.shadow!=='none'||!pulseCheck.paused){
    throw Error('Battery pulse regression: '+JSON.stringify(pulseCheck));
  }
  await page.emulateMedia({reducedMotion:'reduce'});
  const reduced=await page.evaluate(()=>{
    const cell=document.querySelector('.hv-power[data-power-size="popup"] [data-module="1"] .hv-power-cell-fill');
    return {opacity:Number(getComputedStyle(cell).opacity),animation:getComputedStyle(cell).animationName};
  });
  if(Math.abs(reduced.opacity-.2)>.001||reduced.animation!=='none')throw Error('Reduced motion must preserve proportional SOC: '+JSON.stringify(reduced));
  console.log('Pulse: 6% → 20% → 6% for the 12% boundary cell; no glow; hidden and reduced-motion states OK');
  console.log('transparent chassis, unclipped wheel-end flow, production markup size/theme captures + app popup captured');
}finally{await browser?.close();server.close();}
