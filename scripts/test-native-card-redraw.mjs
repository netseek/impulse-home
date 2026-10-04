import assert from 'node:assert/strict';
import {readFileSync,mkdtempSync,writeFileSync,rmSync,existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
const source=readFileSync(new URL('../app/src/main/java/com/havalh6/viewer/MainActivity.java',import.meta.url),'utf8');
function wiring(s){
 for(const type of ['QuickClockCardView','QuickCardGraphicView']){
  const start=s.indexOf('    private final class '+type);
  const body=s.slice(s.indexOf('        void setDescriptor(',start),s.indexOf('\n        }',s.indexOf('        void setDescriptor(',start)));
  assert.match(body,/boolean changed = !descriptor.sameVisuals\(next\)/);
  assert.match(body,/descriptor = next/);
  assert.match(body,/if \(changed\) (?:refreshNow|invalidate)\(\)/);
 }
 assert.match(s,/void refreshNow\(\) \{\s*snapshot = ClockSnapshot.from\(descriptor, getContext\(\)\);\s*invalidate\(\)/,'clock ticks remain independent');
}
wiring(source);
assert.throws(()=>wiring(source.replace('if (changed) refreshNow();','refreshNow();')));
assert.throws(()=>wiring(source.replace('if (changed) invalidate();','invalidate();')));
const start=source.indexOf('    private static final class BottomCardDescriptor {');
const end=source.indexOf('\n    /**',start);
const descriptor=source.slice(start,end);
const params=descriptor.match(/BottomCardDescriptor\(([\s\S]*?)\) \{/)[1].split(',').map(s=>s.trim().split(/\s+/)[0]);
const args=params.map(t=>({'String':'"baseline"','int':'1','double':'1.0','boolean':'false','String[]':'new String[]{"a"}'})[t]);
assert.ok(args.every(Boolean));
const program=`import java.lang.reflect.*;
public class CardRedrawCheck {
 static class QuickMenuRow {}
 ${descriptor}
 static BottomCardDescriptor make(){return new BottomCardDescriptor(${args.join(',')});}
 public static void main(String[] args)throws Exception{
  BottomCardDescriptor a=make(),b=make();
  if(!a.sameVisuals(b))throw new AssertionError("equal payload redraws");
  if(a.sameVisuals(null))throw new AssertionError("null payload");
  for(Field f:BottomCardDescriptor.class.getDeclaredFields()){
   if(f.getName().equals("editMenu"))continue;
   f.setAccessible(true);b=make();Object old=f.get(b),next;
   if(f.getType()==String.class)next=old+"changed";
   else if(f.getType()==int.class)next=((Integer)old)+1;
   else if(f.getType()==double.class)next=((Double)old)+1;
   else if(f.getType()==boolean.class)next=!((Boolean)old);
   else if(f.getType()==String[].class)next=new String[]{"changed"};
   else if(f.getType()==int[].class)next=new int[]{7};
   else throw new AssertionError("untested field "+f.getName());
   f.set(b,next);
   if(a.sameVisuals(b))throw new AssertionError("missed "+f.getName());
  }
 }
}`;
const dir=mkdtempSync(join(tmpdir(),'native-card-redraw-'));
const brewJdk='/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home';
const javaHome=process.env.JAVA_HOME || (existsSync(brewJdk) ? brewJdk : '');
const javaBin=name=>javaHome ? join(javaHome,'bin',name) : name;
try{
 function run(text){writeFileSync(join(dir,'CardRedrawCheck.java'),text);execFileSync(javaBin('javac'),[join(dir,'CardRedrawCheck.java')]);execFileSync(javaBin('java'),['-cp',dir,'CardRedrawCheck'],{stdio:'pipe'});}
 run(program);
 assert.throws(()=>run(program.replace('&& java.util.Objects.equals(primary, other.primary)','&& true')));
}finally{rmSync(dir,{recursive:true,force:true});}
console.log('PASS native redraw: equal/changed/all descriptor fields, clock ticks, wiring + negative controls');
