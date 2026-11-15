import test from 'node:test';
import assert from 'node:assert/strict';
import { randomUUID } from 'node:crypto';
import { createRelayServer } from './server.mjs';

async function environment(t) {
  let clock = 100000;
  const server = createRelayServer({ now: () => clock });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  const post = async (path, body, status = 200) => {
    const response = await fetch(base + path, { method: 'POST',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const result = await response.json();
    assert.equal(response.status, status, JSON.stringify(result));
    return result;
  };
  const vehicle = overrides => ({ clientId: randomUUID(), latitude: 37.422, longitude: -122.084,
    channel: '일반 대화', rangeKm: 1, receiving: true, plate4: '0007', ...overrides });
  const sync = v => post('/v1/sync', v);
  const join = async v => {
    await sync(v);
    const r = await post('/v1/walkie/join', { clientId: v.clientId, consent: true });
    return { clientId: v.clientId, memberToken: r.memberToken };
  };
  const ptt = (action, member, body = {}, status = 200) => post('/v1/walkie/' + action, { ...member, ...body }, status);
  const poll = member => ptt('poll', member, { waitMs: 0 });
  const pcm = Buffer.alloc(3200, 42).toString('base64');
  const frame = (member, talk, sequence = 0, status = 200) => ptt('frame', member,
    { talkId: talk.talkId, sequence, pcmBase64: pcm }, status);
  return { post, vehicle, sync, join, ptt, poll, frame, pcm, advance: n => { clock += n; } };
}

test('live PCM reaches another participant before the talk ends, with no self echo or inbox recording', async t => {
  const e = await environment(t);
  const a = e.vehicle(), b = e.vehicle({ plate4: '0012' }), observer = e.vehicle();
  const ma = await e.join(a), mb = await e.join(b);
  await e.sync(observer);
  const talk = await e.ptt('start', ma);
  await e.frame(ma, talk);
  const heard = await e.poll(mb);
  assert.equal(heard.speaker.mine, false);
  assert.equal(heard.events.find(x => x.type === 'audio').pcmBase64, e.pcm);
  assert.equal((await e.poll(ma)).events.filter(x => x.type === 'audio').length, 0);
  assert.equal((await e.sync(b)).messages.length, 0);
  await e.ptt('poll', { clientId: observer.clientId, memberToken: randomUUID() }, { waitMs: 0 }, 409);
});

test('consent, receive permission and member tokens are mandatory', async t => {
  const e = await environment(t), a = e.vehicle();
  await e.sync(a);
  await e.post('/v1/walkie/join', { clientId: a.clientId }, 400);
  const ma = await e.join(a);
  await e.ptt('start', { ...ma, memberToken: randomUUID() }, {}, 409);
  a.receiving = false;
  await e.sync(a);
  await e.post('/v1/walkie/join', { clientId: a.clientId, consent: true }, 409);
  await e.ptt('start', ma, {}, 409);
});

test('a channel has one speaker; another channel can talk independently', async t => {
  const e = await environment(t);
  const ma = await e.join(e.vehicle()), mb = await e.join(e.vehicle());
  const mc = await e.join(e.vehicle({ channel: '도로 위험' }));
  const talk = await e.ptt('start', ma);
  await e.ptt('start', mb, {}, 409);
  await e.ptt('start', mc);
  await e.ptt('finish', ma, { talkId: talk.talkId });
  const reply = await e.ptt('start', mb);
  assert.notEqual(reply.talkId, talk.talkId);
});

test('channel and both participants ranges filter live audio', async t => {
  const e = await environment(t);
  const ma = await e.join(e.vehicle({ rangeKm: 5 }));
  const near = await e.join(e.vehicle({ latitude: 37.423 }));
  const far = await e.join(e.vehicle({ latitude: 37.442, rangeKm: 1 }));
  const other = await e.join(e.vehicle({ channel: '도움 요청' }));
  const talk = await e.ptt('start', ma);
  await e.frame(ma, talk);
  assert.ok((await e.poll(near)).events.some(x => x.type === 'audio'));
  assert.deepEqual((await e.poll(far)).events, []);
  assert.deepEqual((await e.poll(other)).events, []);
});

test('a late participant hears future frames only and queued tail audio survives release', async t => {
  const e = await environment(t), ma = await e.join(e.vehicle());
  const talk = await e.ptt('start', ma);
  await e.frame(ma, talk);
  const mb = await e.join(e.vehicle());
  assert.equal((await e.poll(mb)).events.filter(x => x.type === 'audio').length, 0);
  await e.frame(ma, talk, 1);
  await e.ptt('finish', ma, { talkId: talk.talkId });
  const events = (await e.poll(mb)).events;
  assert.deepEqual(events.map(x => x.type), ['audio', 'end']);
  assert.equal(events[0].sequence, 1);
  assert.equal((await e.poll(mb)).events.length, 0);
});

test('a receiver leaving the radius cannot download already queued audio', async t => {
  const e = await environment(t), a = e.vehicle(), b = e.vehicle();
  const ma = await e.join(a), mb = await e.join(b), talk = await e.ptt('start', ma);
  await e.frame(ma, talk);
  b.latitude = 38;
  await e.sync(b);
  const result = await e.poll(mb);
  assert.equal(result.speaker, null);
  assert.equal(result.events.filter(x => x.type === 'audio').length, 0);
});

test('settings changes revoke old participation and discard queued speech', async t => {
  const e = await environment(t), a = e.vehicle(), b = e.vehicle();
  const ma = await e.join(a), mb = await e.join(b), talk = await e.ptt('start', ma);
  await e.frame(ma, talk);
  b.channel = '도로 위험';
  await e.sync(b);
  await e.ptt('poll', mb, { waitMs: 0 }, 409);
  const next = await e.join(b);
  assert.deepEqual((await e.poll(next)).events, []);
});

test('only the speaker can send or release the current floor', async t => {
  const e = await environment(t), ma = await e.join(e.vehicle()), mb = await e.join(e.vehicle());
  const talk = await e.ptt('start', ma);
  await e.frame(mb, talk, 0, 409);
  await e.ptt('finish', mb, { talkId: talk.talkId }, 409);
  await e.ptt('start', mb, {}, 409);
});

test('malformed frames, stale sequence and fast recording dumps are rejected', async t => {
  const e = await environment(t), ma = await e.join(e.vehicle()), talk = await e.ptt('start', ma);
  await e.ptt('frame', ma, { talkId: talk.talkId, sequence: 0, pcmBase64: 'AAAA' }, 400);
  await e.frame(ma, talk, 1, 409);
  await e.frame(ma, talk, 0);
  await e.frame(ma, talk, 0, 409);
  await e.frame(ma, talk, 1);
  await e.frame(ma, talk, 2);
  await e.frame(ma, talk, 3, 429);
});

test('missing frames release the floor and an old finish cannot terminate a new speaker', async t => {
  const e = await environment(t), ma = await e.join(e.vehicle()), mb = await e.join(e.vehicle());
  const old = await e.ptt('start', ma);
  e.advance(2001);
  const next = await e.ptt('start', mb);
  await e.ptt('finish', ma, { talkId: old.talkId });
  await e.frame(mb, next);
  await e.frame(ma, old, 0, 409);
});

test('a single press ends at 15 seconds even with continuing frames', async t => {
  const e = await environment(t), a = e.vehicle(), ma = await e.join(a);
  const talk = await e.ptt('start', ma);
  for (let i = 0; i < 15; i++) {
    if (i) { e.advance(1000); await e.sync(a); }
    await e.frame(ma, talk, i);
  }
  e.advance(1000);
  await e.sync(a);
  await e.frame(ma, talk, 15, 409);
  assert.ok((await e.poll(ma)).events.some(x => x.type === 'end' && x.reason === 'limit'));
});

test('disconnecting a speaker immediately releases the floor', async t => {
  const e = await environment(t), a = e.vehicle(), ma = await e.join(a), mb = await e.join(e.vehicle());
  await e.ptt('start', ma);
  await e.post('/v1/disconnect', { clientId: a.clientId });
  await e.ptt('start', mb);
});

test('slow receivers skip speech older than one second instead of replaying it later', async t => {
  const e = await environment(t), ma = await e.join(e.vehicle()), mb = await e.join(e.vehicle());
  const talk = await e.ptt('start', ma);
  for (let i = 0; i < 20; i++) { e.advance(100); await e.frame(ma, talk, i); }
  const events = (await e.poll(mb)).events;
  assert.ok(events.length <= 16);
  assert.ok(events.filter(x => x.type === 'audio').length <= 11);
  assert.ok(events.find(x => x.type === 'audio').sequence >= 9);
});

test('long polling wakes for live speech rather than waiting for the next 2-second message sync', async t => {
  const e = await environment(t), ma = await e.join(e.vehicle()), mb = await e.join(e.vehicle());
  const waiting = e.ptt('poll', mb, { waitMs: 500 });
  await new Promise(resolve => setTimeout(resolve, 20));
  await e.ptt('start', ma);
  const result = await waiting;
  assert.ok(result.events.some(x => x.type === 'start'));
  assert.ok(result.speaker);
});

test('a sender waits two seconds after release while another sender may answer immediately', async t => {
  const e = await environment(t), ma = await e.join(e.vehicle()), mb = await e.join(e.vehicle());
  const talk = await e.ptt('start', ma);
  await e.ptt('finish', ma, { talkId: talk.talkId });
  assert.equal((await e.poll(ma)).nextTalkInMs, 2000);
  await e.ptt('start', ma, {}, 429);
  const reply = await e.ptt('start', mb);
  await e.ptt('finish', mb, { talkId: reply.talkId });
  e.advance(1999);
  await e.ptt('start', ma, {}, 429);
  e.advance(1);
  await e.ptt('start', ma);
});

test('leaving, rejoining or changing channel does not bypass the same sender cooldown', async t => {
  const e = await environment(t), a = e.vehicle(), ma = await e.join(a);
  const talk = await e.ptt('start', ma);
  await e.ptt('leave', ma);
  a.channel = '도로 위험';
  const next = await e.join(a);
  await e.ptt('start', next, {}, 429);
  await e.ptt('finish', next, { talkId: talk.talkId });
  e.advance(2000);
  await e.ptt('start', next);
});

test('mute drops queued and future audio for only that listener; unmute resumes future frames', async t => {
  const e = await environment(t), a = e.vehicle();
  const ma = await e.join(a), mb = await e.join(e.vehicle()), mc = await e.join(e.vehicle());
  const talk = await e.ptt('start', ma);
  await e.frame(ma, talk);
  const muted = await e.ptt('mute', mb, { senderId: a.clientId });
  assert.equal(muted.speaker, null);
  assert.equal(muted.mutedCount, 1);
  assert.ok(!(await e.poll(mb)).events.some(x => x.type === 'audio' || x.type === 'start'));
  assert.ok((await e.poll(mc)).events.some(x => x.type === 'audio' && x.senderId === a.clientId));
  await e.frame(ma, talk, 1);
  assert.ok(!(await e.poll(mb)).events.some(x => x.type === 'audio'));
  await e.ptt('start', mb, {}, 409);
  await e.ptt('unmute-all', mb);
  assert.ok(!(await e.poll(mb)).events.some(x => x.type === 'audio'));
  await e.frame(ma, talk, 2);
  assert.deepEqual((await e.poll(mb)).events.filter(x => x.type === 'audio').map(x => x.sequence), [2]);
});

test('mute is tied to the sender ID, not an unverified four-digit number, and ends on rejoin', async t => {
  const e = await environment(t), a = e.vehicle({ plate4: '1234' }), c = e.vehicle({ plate4: '1234' }), b = e.vehicle();
  const ma = await e.join(a), mb = await e.join(b), mc = await e.join(c);
  const first = await e.ptt('start', ma);
  await e.ptt('mute', mb, { senderId: a.clientId });
  await e.ptt('finish', ma, { talkId: first.talkId });
  const other = await e.ptt('start', mc);
  await e.frame(mc, other);
  assert.ok((await e.poll(mb)).events.some(x => x.type === 'audio' && x.senderId === c.clientId));
  await e.ptt('leave', mb);
  const next = await e.join(b);
  assert.equal((await e.poll(next)).mutedCount, 0);
  await e.ptt('finish', mc, { talkId: other.talkId });
  e.advance(2000);
  const again = await e.ptt('start', ma);
  await e.frame(ma, again);
  assert.ok((await e.poll(next)).events.some(x => x.type === 'audio' && x.senderId === a.clientId));
});

test('mute controls require the listener token and an actual current speaker', async t => {
  const e = await environment(t), a = e.vehicle(), b = e.vehicle();
  const ma = await e.join(a), mb = await e.join(b);
  await e.ptt('mute', mb, { senderId: a.clientId }, 409);
  await e.ptt('start', ma);
  await e.ptt('mute', { ...mb, memberToken: randomUUID() }, { senderId: a.clientId }, 409);
  await e.ptt('mute', mb, { senderId: b.clientId }, 400);
  await e.ptt('mute', mb, { senderId: randomUUID() }, 409);
  await e.ptt('mute', mb, { senderId: a.clientId });
  await e.ptt('unmute-all', { ...mb, memberToken: randomUUID() }, {}, 409);
  assert.equal((await e.poll(mb)).mutedCount, 1);
});

test('muting a voice does not suppress danger and help text signals', async t => {
  const e = await environment(t), a = e.vehicle({ channel: '도로 위험' }), b = e.vehicle({ channel: '도로 위험' });
  const ma = await e.join(a), mb = await e.join(b);
  await e.ptt('start', ma);
  await e.ptt('mute', mb, { senderId: a.clientId });
  await e.post('/v1/messages', { clientId: a.clientId, requestId: randomUUID(),
    channel: a.channel, rangeKm: a.rangeKm, text: '위험 알림 · 도로 위 낙하물' });
  assert.equal((await e.sync(b)).messages[0].text, '위험 알림 · 도로 위 낙하물');
  assert.equal((await e.poll(mb)).speaker, null);
});
