// Review the packaged app using explicit, temporary simulated readings.
// Uses the existing CDP harness; never writes emulator preferences or vehicle bus.
import {execFileSync} from 'node:child_process';
const serial=process.argv[2]||'emulator-5554';
const run=(...args)=>execFileSync(process.execPath,['scripts/device-cdp.mjs','--serial',serial,...args],{encoding:'utf8',timeout:30000});
const evaluate=expression=>JSON.parse(run('eval',expression));
for(let i=0;i<20;i++){
  if(evaluate('!!window.__app'))break;
  await new Promise(r=>setTimeout(r,500));
}
evaluate('(() => {window.HavalSplash?.skip?.();return true;})()');
await new Promise(r=>setTimeout(r,1500));
if(!evaluate('typeof window.__app?._powerBatteryVisual === "function"'))throw Error('Emulator has another APK installed; install this worktree build before review.');
evaluate(`(() => {
  const a=window.__app;
  a._desktopBottomCards=['power','range','tires'];
  a._powerStatus=()=>({key:'live',source:'DEMO · SIMULATED · NOT VEHICLE',flowFresh:true,voltageFresh:true,currentFresh:true});
  a._powerLive={flow:'v1|charge|0|0|0',soc:'64',voltage:'360',current:'18'};
  a._carSignalAt=a._carSignalAt||{};
  a._carSignalAt['car.ev_info.cur_battery_power_percentage']=Date.now();
  a.setState({dockMode:'cards',modelTrim:'phev34'},()=>a._syncDockIndicators());
  return true;
})()`);
await new Promise(r=>setTimeout(r,1500));
console.log(run('shot','docs/power-approved-emulator-native.png'));
evaluate(`(() => {const a=window.__app;a._openFocusedCard('power');return true;})()`);
await new Promise(r=>setTimeout(r,1200));
const check=evaluate(`(() => {
 const a=window.__app;a._powerCardCacheAt=0;a._updatePowerCards(performance.now());
 const c=document.querySelector('.hv-power[data-power-size="popup"] .hv-power-canvas');
 return {cells:c?.querySelectorAll('[data-module]').length,pulses:c?.querySelectorAll('.hv-power-cell-pulse').length,
 mode:c?.querySelector('[data-battery-mode]')?.dataset.batteryMode,asset:c?.querySelector('img')?.naturalWidth};
})()`);
if(check.cells!==10||check.pulses!==1||check.mode!=='charging'||check.asset!==1145)throw Error(JSON.stringify(check));
console.log(run('shot','docs/power-approved-emulator-popup.png'));
console.log('Packaged WebView: ten cells, one charging pulse, approved artwork loaded.',check);
