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
  async function post(path, body, expected = 200) {
    const response = await fetch(base + path, { method: 'POST',
      headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) });
    const result = await response.json();
    assert.equal(response.status, expected, JSON.stringify(result));
    return result;
  }
  const vehicle = overrides => ({ clientId: randomUUID(), latitude: 37.422, longitude: -122.084,
    channel: '일반 대화', rangeKm: 1, receiving: true, plate4: '1234', ...overrides });
  const sync = vehicle => post('/v1/sync', vehicle);
  const send = (vehicle, overrides = {}) => post('/v1/messages', {
    clientId: vehicle.clientId, requestId: randomUUID(), text: '도로 위 낙하물',
    channel: vehicle.channel, rangeKm: vehicle.rangeKm, ...overrides });
  return { post, vehicle, sync, send, advance: ms => { clock += ms; } };
}

test('nearby devices exchange messages; distant devices and other channels do not receive them', async t => {
  const e = await environment(t);
  const sender = e.vehicle();
  const nearby = e.vehicle({ latitude: 37.423 });
  const far = e.vehicle({ latitude: 37.442 });
  const other = e.vehicle({ channel: '도움 요청' });
  for (const client of [sender, nearby, far, other]) await e.sync(client);
  const sent = await e.send(sender);
  assert.equal(sent.targetCount, 1);
  assert.equal((await e.sync(nearby)).messages[0].text, '도로 위 낙하물');
  assert.equal((await e.sync(far)).messages.length, 0);
  assert.equal((await e.sync(other)).messages.length, 0);
  assert.equal((await e.sync(sender)).messages.length, 0);
});

test('receiver range and receive toggle are enforced by the server', async t => {
  const e = await environment(t);
  const sender = e.vehicle({ rangeKm: 5 });
  const receiver = e.vehicle({ latitude: 37.442 });
  await e.sync(sender);
  await e.sync(receiver);
  assert.equal((await e.send(sender)).targetCount, 0);
  receiver.rangeKm = 3;
  await e.sync(receiver);
  assert.equal((await e.send(sender)).targetCount, 1);
  receiver.receiving = false;
  assert.equal((await e.sync(receiver)).messages.length, 0);
  assert.equal((await e.send(sender)).targetCount, 0);
});

test('retrying one message does not duplicate messages or simulated responses', async t => {
  const e = await environment(t);
  const sender = e.vehicle();
  await e.sync(sender);
  const placed = await e.post('/v1/test-vehicles', { clientId: sender.clientId });
  assert.equal(placed.peers.length, 1);
  assert.equal(placed.peers[0].testVehicle, true);
  const payload = { requestId: randomUUID() };
  const first = await e.send(sender, payload);
  const second = await e.send(sender, payload);
  assert.equal(first.messageId, second.messageId);
  assert.equal((await e.sync(sender)).messages.length, 1);
  await e.post('/v1/messages', { clientId: sender.clientId, ...payload,
    text: '바뀐 내용', channel: sender.channel, rangeKm: 1 }, 409);
  sender.rangeKm = 3;
  const expanded = await e.sync(sender);
  assert.equal(expanded.peers.length, 2);
  assert.ok(expanded.peers[1].distanceMeters >= 1999);
});

test('expired peers and their test vehicles disappear; a late joiner gets no past messages', async t => {
  const e = await environment(t);
  const sender = e.vehicle();
  const receiver = e.vehicle();
  await e.sync(sender);
  await e.sync(receiver);
  await e.post('/v1/test-vehicles', { clientId: sender.clientId });
  await e.send(sender);
  const lateJoiner = e.vehicle();
  assert.equal((await e.sync(lateJoiner)).messages.length, 0);
  e.advance(16000);
  assert.equal((await e.sync(receiver)).peers.length, 0);
  e.advance(121000);
  assert.equal((await e.sync(receiver)).messages.length, 0);
});

test('invalid coordinates and messages without an active presence are rejected', async t => {
  const e = await environment(t);
  await e.post('/v1/sync', e.vehicle({ latitude: 91 }), 400);
  await e.post('/v1/sync', e.vehicle({ latitude: '37.422' }), 400);
  const sender = e.vehicle();
  await e.post('/v1/messages', { clientId: sender.clientId, requestId: randomUUID(),
    text: '미연결', channel: sender.channel, rangeKm: 1 }, 409);
});


test('independent clients send manual replies in both directions without fixture auto-replies', async t => {
  const e = await environment(t);
  const first = e.vehicle();
  const second = e.vehicle({ latitude: 37.423 });
  await e.sync(first);
  await e.sync(second);
  const requestId = randomUUID();
  const question = await e.send(first, { requestId, text: '앞쪽 상황을 확인해 주세요.' });
  assert.equal(question.targetCount, 1);
  let received = (await e.sync(second)).messages;
  assert.equal(received.length, 1);
  assert.equal(received[0].id, question.messageId);
  assert.equal(received[0].testVehicle, false);
  assert.equal((await e.sync(first)).messages.length, 0);
  const reply = await e.send(second, { text: '확인했습니다. 앞쪽에 낙하물이 있습니다.' });
  assert.equal(reply.targetCount, 1);
  received = (await e.sync(first)).messages;
  assert.equal(received.length, 1);
  assert.equal(received[0].id, reply.messageId);
  assert.equal(received[0].text, '확인했습니다. 앞쪽에 낙하물이 있습니다.');
  assert.equal(received[0].testVehicle, false);
  assert.equal((await e.send(first, { requestId, text: '앞쪽 상황을 확인해 주세요.' })).messageId, question.messageId);
  assert.equal((await e.sync(second)).messages.length, 1);
});

test('PC test page serves only its fixed local assets with browser isolation headers', async t => {
  const server = createRelayServer();
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  t.after(() => new Promise(resolve => server.close(resolve)));
  const base = 'http://127.0.0.1:' + server.address().port;
  for (const [path, type] of [['/test-client', 'text/html'],
      ['/test-client.js', 'text/javascript'], ['/test-client.css', 'text/css']]) {
    const response = await fetch(base + path);
    assert.equal(response.status, 200);
    assert.ok(response.headers.get('content-type').startsWith(type));
    assert.equal(response.headers.get('x-content-type-options'), 'nosniff');
    assert.match(response.headers.get('content-security-policy'), /connect-src 'self'/);
    assert.ok((await response.text()).length > 100);
  }
  const forbidden = await fetch(base + '/server.mjs');
  assert.notEqual(forbidden.status, 200);
});

test('server acceptance and sync alone do not count as receiver acknowledgement', async t => {
  const e = await environment(t);
  const sender = e.vehicle();
  const receiver = e.vehicle();
  await e.sync(sender);
  await e.sync(receiver);
  const sent = await e.send(sender);
  assert.equal(sent.delivery.appTargetCount, 1);
  assert.equal(sent.delivery.acknowledgedCount, 0);
  await e.post('/v1/ack', { clientId: receiver.clientId, messageIds: [sent.messageId] }, 403);
  assert.equal((await e.sync(receiver)).messages[0].id, sent.messageId);
  assert.equal((await e.sync(sender)).deliveries[0].acknowledgedCount, 0);
  await e.post('/v1/ack', { clientId: receiver.clientId, messageIds: [sent.messageId] });
  assert.equal((await e.sync(sender)).deliveries[0].acknowledgedCount, 1);
});

test('duplicate acknowledgements and message retries never inflate receiver counts', async t => {
  const e = await environment(t);
  const sender = e.vehicle();
  const first = e.vehicle();
  const second = e.vehicle();
  for (const client of [sender, first, second]) await e.sync(client);
  const requestId = randomUUID();
  const sent = await e.send(sender, { requestId });
  await e.sync(first);
  const ack = { clientId: first.clientId, messageIds: [sent.messageId, sent.messageId] };
  await e.post('/v1/ack', ack);
  await e.post('/v1/ack', ack);
  assert.equal((await e.send(sender, { requestId })).delivery.acknowledgedCount, 1);
  assert.equal((await e.sync(sender)).deliveries[0].appTargetCount, 2);
  await e.sync(second);
  await e.post('/v1/ack', { clientId: second.clientId, messageIds: [sent.messageId] });
  assert.equal((await e.sync(sender)).deliveries[0].acknowledgedCount, 2);
});

test('a non-recipient cannot acknowledge and a mixed invalid batch is atomic', async t => {
  const e = await environment(t);
  const sender = e.vehicle();
  const receiver = e.vehicle();
  const outsider = e.vehicle({ channel: '도로 위험' });
  for (const client of [sender, receiver, outsider]) await e.sync(client);
  const sent = await e.send(sender);
  await e.sync(receiver);
  await e.sync(outsider);
  await e.post('/v1/ack', { clientId: outsider.clientId, messageIds: [sent.messageId] }, 403);
  await e.post('/v1/ack', { clientId: receiver.clientId, messageIds: [sent.messageId, randomUUID()] }, 404);
  assert.equal((await e.sync(sender)).deliveries[0].acknowledgedCount, 0);
});

test('automatic test vehicles are reported separately and never count as real app acknowledgements', async t => {
  const e = await environment(t);
  const sender = e.vehicle({ rangeKm: 3 });
  const receiver = e.vehicle();
  await e.sync(sender);
  await e.sync(receiver);
  await e.post('/v1/test-vehicles', { clientId: sender.clientId });
  const sent = await e.send(sender);
  assert.equal(sent.delivery.targetCount, 3);
  assert.equal(sent.delivery.appTargetCount, 1);
  assert.equal(sent.delivery.testTargetCount, 2);
  assert.equal(sent.delivery.acknowledgedCount, 0);
  assert.equal((await e.sync(sender)).messages.filter(item => item.testVehicle).length, 2);
  await e.sync(receiver);
  await e.post('/v1/ack', { clientId: receiver.clientId, messageIds: [sent.messageId] });
  const delivery = (await e.sync(sender)).deliveries[0];
  assert.equal(delivery.acknowledgedCount, 1);
  assert.equal(delivery.testTargetCount, 2);
});

test('acknowledgement records disappear with expired messages and invalid batches are rejected', async t => {
  const e = await environment(t);
  const sender = e.vehicle();
  const receiver = e.vehicle();
  await e.sync(sender);
  await e.sync(receiver);
  const sent = await e.send(sender);
  await e.sync(receiver);
  await e.post('/v1/ack', { clientId: receiver.clientId, messageIds: [] }, 400);
  await e.post('/v1/ack', { clientId: receiver.clientId, messageIds: ['invalid'] }, 400);
  await e.post('/v1/ack', { clientId: receiver.clientId, messageIds: Array(11).fill(sent.messageId) }, 400);
  e.advance(121000);
  await e.sync(receiver);
  await e.post('/v1/ack', { clientId: receiver.clientId, messageIds: [sent.messageId] }, 404);
  assert.equal((await e.sync(sender)).deliveries.length, 0);
});

test('an acknowledgement confirms a prior delivery even if the receiver subsequently changes channel', async t => {
  const e = await environment(t);
  const sender = e.vehicle();
  const receiver = e.vehicle();
  await e.sync(sender);
  await e.sync(receiver);
  const sent = await e.send(sender);
  await e.sync(receiver);
  receiver.channel = '도움 요청';
  await e.sync(receiver);
  await e.post('/v1/ack', { clientId: receiver.clientId, messageIds: [sent.messageId] });
  assert.equal((await e.sync(sender)).deliveries[0].acknowledgedCount, 1);
});

test('four digits preserve leading zeroes; client claims never turn into verified identity', async t => {
  const e = await environment(t);
  const sender = e.vehicle({ plate4: '0007', plateVerified: true, label: '인증 차량' });
  const receiver = e.vehicle({ plate4: '0007' });
  await e.sync(sender);
  const peers = (await e.sync(receiver)).peers;
  assert.equal(peers[0].plate4, '0007');
  assert.equal(peers[0].plateVerified, false);
  assert.equal(peers[0].label, '차량 0007 (입력번호)');
  await e.send(sender);
  const message = (await e.sync(receiver)).messages[0];
  assert.equal(message.plate4, '0007');
  assert.equal(message.plateVerified, false);
  assert.equal(message.sender, '차량 0007 (입력번호)');
});

test('plate editing requires disconnect and old messages keep the original sender number', async t => {
  const e = await environment(t);
  const sender = e.vehicle({ plate4: '0007' });
  const receiver = e.vehicle();
  await e.sync(sender); await e.sync(receiver);
  await e.send(sender);
  await e.post('/v1/sync', { ...sender, plate4: '9876' }, 409);
  await e.post('/v1/disconnect', { clientId: sender.clientId });
  await e.sync({ ...sender, plate4: '9876' });
  await e.send({ ...sender, plate4: '9876' });
  const inbox = (await e.sync(receiver)).messages;
  assert.deepEqual(inbox.map(item => item.plate4), ['9876', '0007']);
});

test('missing, malformed and non-ASCII plate numbers are rejected', async t => {
  const e = await environment(t);
  for (const plate4 of [undefined, null, 1234, '123', '12345', '１２３４', '12a4', ' 1234']) {
    await e.post('/v1/sync', e.vehicle({ plate4 }), 400);
  }
});

function voiceBody(vehicle, overrides = {}) {
  // A bounded MP4-shaped byte fixture tests transport, not codec or microphone quality.
  const audio = Buffer.alloc(80);
  audio.writeUInt32BE(24, 0); audio.write('ftypM4A ', 4, 'ascii');
  return { clientId: vehicle.clientId, requestId: randomUUID(), channel: vehicle.channel,
    rangeKm: vehicle.rangeKm, mimeType: 'audio/mp4', durationMs: 1000,
    audioBase64: audio.toString('base64'), ...overrides };
}

test('voice bytes round trip unchanged; receipt requires offered file download before ack', async t => {
  const e = await environment(t);
  const sender = e.vehicle({ plate4: '0007' }), receiver = e.vehicle();
  await e.sync(sender); await e.sync(receiver);
  const body = voiceBody(sender);
  const sent = await e.post('/v1/voice-messages', body);
  const message = (await e.sync(receiver)).messages[0];
  assert.equal(message.kind, 'voice');
  assert.equal(message.plate4, '0007');
  assert.equal(message.audioBase64, undefined);
  await e.post('/v1/ack', { clientId: receiver.clientId, messageIds: [message.id] }, 409);
  const downloaded = await e.post('/v1/voice', { clientId: receiver.clientId, messageId: message.id });
  assert.equal(downloaded.audioBase64, body.audioBase64);
  assert.equal(downloaded.durationMs, 1000);
  await e.post('/v1/ack', { clientId: receiver.clientId, messageIds: [message.id] });
  const retried = await e.post('/v1/voice-messages', body);
  assert.equal(retried.messageId, sent.messageId);
  assert.equal(retried.delivery.acknowledgedCount, 1);
  assert.equal((await e.sync(receiver)).messages.length, 1);
});

test('voice access is limited to original recipients and expires with the message', async t => {
  const e = await environment(t);
  const sender = e.vehicle(), receiver = e.vehicle(), other = e.vehicle({ channel: '도로 위험' });
  for (const client of [sender, receiver, other]) await e.sync(client);
  const sent = await e.post('/v1/voice-messages', voiceBody(sender));
  const query = { clientId: receiver.clientId, messageId: sent.messageId };
  await e.post('/v1/voice', query, 403); // Must first be offered in receiver's inbox.
  await e.sync(receiver);
  await e.post('/v1/voice', { ...query, clientId: other.clientId }, 403);
  e.advance(120001);
  await e.sync(receiver);
  await e.post('/v1/voice', query, 404);
});

test('voice limits reject long, oversized, malformed and wrong-format uploads', async t => {
  const e = await environment(t);
  const sender = e.vehicle(); await e.sync(sender);
  for (const overrides of [
    { durationMs: 299 }, { durationMs: 15001 }, { durationMs: 1000.1 },
    { mimeType: 'audio/wav' }, { audioBase64: 'not base64!' },
    { audioBase64: Buffer.alloc(131073).toString('base64') },
    { audioBase64: Buffer.alloc(80).toString('base64') },
  ]) await e.post('/v1/voice-messages', voiceBody(sender, overrides), 400);
});

test('burst limit spans text and voice while retries remain idempotent', async t => {
  const e = await environment(t);
  const sender = e.vehicle(); await e.sync(sender);
  const requestId = randomUUID();
  const first = await e.send(sender, { requestId });
  for (let index = 0; index < 4; index++) await e.post('/v1/voice-messages', voiceBody(sender));
  await e.post('/v1/messages', { clientId: sender.clientId, requestId: randomUUID(),
    channel: sender.channel, rangeKm: 1, text: '초과' }, 429);
  assert.equal((await e.send(sender, { requestId })).messageId, first.messageId);
  e.advance(10001);
  await e.sync(sender);
  assert.equal((await e.send(sender)).ok, true);
});
