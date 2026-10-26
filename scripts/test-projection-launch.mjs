#!/usr/bin/env node
// Android adapter contracts; CarPlayUiRequestTest exercises the protocol on the JVM.
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';

const read = path => readFileSync(new URL(`../${path}`, import.meta.url), 'utf8').replace(/\r\n/g, '\n');
const main = read('app/src/main/java/com/havalh6/viewer/MainActivity.java');
const presence = read('app/src/main/java/com/havalh6/viewer/ProjectionPresence.java');

function method(source, signature) {
  const start = source.indexOf(signature);
  assert.ok(start >= 0, `missing ${signature}`);
  const open = source.indexOf('{', start);
  let depth = 0;
  for (let i = open; i < source.length; i++) {
    if (source[i] === '{') depth++;
    if (source[i] === '}' && --depth === 0) return source.slice(open + 1, i);
  }
  throw new Error(`unterminated ${signature}`);
}

assert.match(method(main, 'public void launchAppInPopup(String packageName)'),
  /mainHandler.post\(\(\) -> launchAppForPackage\(packageName, ""\)\);/,
  'WebView app launches must mutate pending state only on the main thread');

const show = method(presence, 'void requestShow(Kind kind)');
assert.match(show, /else if \(kind == Kind.CARPLAY\) \{\s*requestCarPlayShow\(\);/,
  'CarPlay must reach the service show path');
assert.match(show, /sendAaTransact\(0x17\)/, 'Android Auto must keep its own show command');

const carPlay = method(presence, 'private void requestCarPlayShow()');
assert.match(carPlay, /final Handler queue = worker;[\s\S]*queue.post\(pendingCarPlayShowTick\)/,
  'synchronous CarPlay IPC must be posted to the projection worker');
assert.match(carPlay, /new CarPlayUiRequest\(android.os.SystemClock::uptimeMillis\)/,
  'request expiry must use a monotonic clock');
assert.match(carPlay, /if \(pendingCarPlayShow != request\) return;[\s\S]*request.attempt\(/,
  'obsolete queued requests must not touch the service');
assert.match(carPlay, /ProjectionPresence.readInt\(binder, CP_DESCRIPTOR, transaction\)/,
  'status must use the CarPlay descriptor and protocol transaction');
assert.match(carPlay, /data.writeInterfaceToken\(CP_DESCRIPTOR\);\s*data.writeInt\(value\);\s*if \(!binder.transact\(transaction, data, reply, 0\)\) return false;\s*reply.readException\(\);\s*return true;/,
  'requestUi must write exactly its int payload and consume a synchronous reply');
assert.match(carPlay, /finally \{\s*reply.recycle\(\);\s*data.recycle\(\);/,
  'requestUi must recycle both parcels');
assert.match(carPlay, /result == CarPlayUiRequest.Result.RETRY && pendingCarPlayShow == request[\s\S]*queue.postDelayed\(this, CarPlayUiRequest.RETRY_MS\)/,
  'only a current, retryable request may schedule another bind attempt');

const readInt = method(presence, 'private static Integer readInt(');
assert.match(readInt, /reply.readException\(\);\s*if \(reply.dataAvail\(\) < 4\) return null;\s*return reply.readInt\(\);/,
  'failed or truncated status replies must not become an activated phone');
const cancel = method(presence, 'void cancelShow()');
assert.match(cancel, /pendingCarPlayShow = null;[\s\S]*request.cancel\(\);[\s\S]*worker.removeCallbacks\(pendingCarPlayShowTick\)/,
  'cancellation must invalidate in-flight status reads and remove queued attempts');
assert.match(method(presence, 'void stop()'), /cancelShow\(\);/, 'stopping presence must cancel showing');

for (const signature of ['private void onAppLaunched()', 'protected void onPause()', 'protected void onDestroy()']) {
  assert.match(method(main, signature), /^\s*clearPendingProjection\(\);/,
    `${signature} must cancel obsolete projection launches first`);
}
const clear = method(main, 'private void clearPendingProjection()');
assert.match(clear, /pendingProjectionGeneration\+\+;[\s\S]*projectionPresence.cancelShow\(\);[\s\S]*mainHandler.removeCallbacks\(pendingProjectionTimeout\)/,
  'pending cleanup must cancel both service and intent fallback and invalidate queued replies');
assert.match(main, /final long generation = pendingProjectionGeneration;\s*mainHandler.post\(\(\) -> completeProjectionRaise\(taskId, pkg, generation\)\);/,
  'posted resolver callbacks must retain their request generation');
const complete = method(main, 'private void completeProjectionRaise(');
assert.match(complete, /if \(taskId < 0 \|\| generation != pendingProjectionGeneration\s*\|\| pendingProjectionKind == null \|\| !pendingProjectionPackages.contains\(packageName\)\) return;/,
  'late or unrelated task replies must not raise another app');
assert.match(complete, /if \(moveTaskToFrontNoAnim\(taskId\)\) \{\s*clearPendingProjection\(\);/,
  'a failed raise must retain its launch fallback');
assert.ok(!complete.slice(0, complete.indexOf('if (moveTaskToFrontNoAnim')).includes('clearPendingProjection();'),
  'pending state must survive until the raise actually succeeds');
const move = method(main, 'private boolean moveTaskToFrontNoAnim(');
assert.match(move, /am.moveTaskToFront\([^;]+;\s*lastRaisedTaskId = taskId;\s*lastRaiseCompletedMs = now;/,
  'only successful raises may enter the success cooldown');
assert.ok(!move.slice(0, move.indexOf('am.moveTaskToFront')).includes('lastRaisedTaskId = taskId;'),
  'a failed raise must not manufacture a later cooldown success');

const resolve = method(main, 'private void beginProjectionResolve(');
assert.match(resolve, /projectionPresence.requestShow\(kind\)/, 'resolution must request the projection UI');
assert.match(resolve, /clearPendingProjection\(\);\s*List<Intent> candidates = projectionPresence != null\s*\? projectionPresence.launchIntents\(watch\)/,
  'expiry must cancel the service request before preserving exported intent fallback');
console.log('projection launch contracts: ok');
