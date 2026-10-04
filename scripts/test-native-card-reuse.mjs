import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
const source=readFileSync(new URL('../app/src/main/java/com/havalh6/viewer/MainActivity.java',import.meta.url),'utf8');
function check(s){
 const apply=s.slice(s.indexOf('    private void applyDockIndicators('),s.indexOf('    private void applyBottomCardsConfiguration('));
 assert.doesNotMatch(apply,/rebuildQuickCardsRow\(/,'color changes keep cards');
 const reconcile=s.slice(s.indexOf('    private boolean reconcileQuickCardsRow('),s.indexOf('    private String cleanBottomCardText('));
 assert.match(reconcile,/quickCardHosts\.get\(descriptor\.id\)/);assert.doesNotMatch(reconcile,/removeAllViews\(/);
 assert.match(reconcile,/if \(!desired\.contains\(card\)\) quickCardsRow\.removeViewAt\(i\)/,'matching cards stay attached');
 assert.match(reconcile,/quickCardsRow\.getChildAt\(i\) == card\) continue/,'stable order does not detach');
 assert.match(reconcile,/previous\.iconAction\.equals\(card\.iconAction\)/);assert.match(reconcile,/previous\.longAction\.equals\(card\.longAction\)/);
 assert.match(reconcile,/if \(ids\.size\(\) > 16\) return false/,'cache bound');assert.match(reconcile,/!requested\.add\(card\.id\)/,'duplicates use safe fallback');
 const theme=s.slice(s.indexOf('    private void refreshQuickCardsTheme('),s.indexOf('    private void applyQuickClockFace('));
 assert.match(theme,/signature\.equals\(quickCardsThemeSig\)\) return/,'unchanged themes do not repaint');
 assert.match(s,/if \(reused\) \{\s*updateBottomCardValues\(next\)/,'reused cards receive current values');
}
check(source);
for(const [from,to]of [['// Existing graphic views read dockAccentColor','rebuildQuickCardsRow(); // Existing graphic views read dockAccentColor'],['if (!desired.contains(card)) quickCardsRow.removeViewAt(i)','quickCardsRow.removeViewAt(i)'],['if (ids.size() > 16) return false','if (false) return false'],['signature.equals(quickCardsThemeSig)) return','false) return']])assert.throws(()=>check(source.replace(from,to)));
console.log('PASS native card reuse/theme/update/action/cache contracts + negative controls; view identity requires device');
