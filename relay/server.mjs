import http from 'node:http';
import { createHash, randomUUID } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { readFile } from 'node:fs/promises';
import { createWalkieTalkie } from './walkie-talkie.mjs';

const TEST_ASSETS = new Map([
  ['/test-client', ['test-client.html', 'text/html; charset=utf-8']],
  ['/test-client.js', ['test-client.js', 'text/javascript; charset=utf-8']],
  ['/test-client.css', ['test-client.css', 'text/css; charset=utf-8']],
  ['/walkie-client.js', ['walkie-client.js', 'text/javascript; charset=utf-8']],
  ['/walkie-worklet.js', ['walkie-worklet.js', 'text/javascript; charset=utf-8']],
  ['/walkie-pcm.mjs', ['walkie-pcm.mjs', 'text/javascript; charset=utf-8']],
]);

const CHANNELS = new Set(['일반 대화', '도로 위험', '도움 요청']);
const RANGES = new Set([1, 3, 5]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const PLATE4 = /^[0-9]{4}$/;
const MAX_VOICE_BYTES = 128 * 1024;
const RAD = Math.PI / 180;

function reject(condition, message, status = 400) {
  if (condition) throw Object.assign(new Error(message), { status });
}

export function distanceKm(a, b) {
  const dLat = (b.latitude - a.latitude) * RAD;
  const dLon = (b.longitude - a.longitude) * RAD;
  const h = Math.sin(dLat / 2) ** 2 +
    Math.cos(a.latitude * RAD) * Math.cos(b.latitude * RAD) * Math.sin(dLon / 2) ** 2;
  return 6371.0088 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, h))));
}

function destination(origin, distance, bearing) {
  const angle = distance / 6371.0088;
  const lat = origin.latitude * RAD;
  const lon = origin.longitude * RAD;
  const heading = bearing * RAD;
  const newLat = Math.asin(Math.sin(lat) * Math.cos(angle) +
    Math.cos(lat) * Math.sin(angle) * Math.cos(heading));
  const newLon = lon + Math.atan2(Math.sin(heading) * Math.sin(angle) * Math.cos(lat),
    Math.cos(angle) - Math.sin(lat) * Math.sin(newLat));
  return { latitude: newLat / RAD, longitude: ((newLon / RAD + 540) % 360) - 180 };
}

export function createRelayServer({ now = Date.now, peerTtlMs = 15000, messageTtlMs = 120000 } = {}) {
  const clients = new Map();
  let messages = [];
  const requests = new Map();

  function prune() {
    for (const [id, client] of clients) {
      if (!client.fixture && now() - client.lastSeen > peerTtlMs) clients.delete(id);
    }
    for (const [id, client] of clients) {
      if (client.fixture && !clients.has(client.owner)) clients.delete(id);
    }
    messages = messages.filter(message => now() - message.createdAt <= messageTtlMs).slice(-256);
    const validMessages = new Set(messages.map(message => message.id));
    for (const [key, record] of requests) {
      if (!validMessages.has(record.message.id)) requests.delete(key);
    }
  }

  function realClient(id) {
    reject(typeof id !== 'string' || !UUID.test(id), '기기 ID가 올바르지 않습니다.');
    const client = clients.get(id);
    reject(!client || client.fixture, 'PC 테스트 연결을 먼저 해 주세요.', 409);
    return client;
  }

  function matches(receiver, sender, rangeKm = sender.rangeKm) {
    return receiver.id !== sender.id && receiver.receiving &&
      receiver.channel === sender.channel &&
      distanceKm(receiver, sender) <= Math.min(receiver.rangeKm, rangeKm);
  }

  function snapshot(client) {
    const peers = [...clients.values()]
      .filter(peer => matches({ ...client, receiving: true }, peer, 5))
      .map(peer => ({
        id: peer.id, label: peer.label, plate4: peer.plate4 ?? null, plateVerified: false, testVehicle: !!peer.fixture,
        distanceMeters: Math.round(distanceKm(client, peer) * 1000),
      })).sort((a, b) => a.distanceMeters - b.distanceMeters);
    const available = client.receiving ? messages
      .filter(message => message.recipients.includes(client.id) &&
        message.channel === client.channel &&
        distanceKm(client, message.position) <= Math.min(client.rangeKm, message.rangeKm))
      .slice(-10).reverse() : [];
    for (const message of available) message.offered.add(client.id);
    const inbox = available.map(message => ({
      id: message.id, text: message.text, sender: message.senderLabel,
      plate4: message.plate4, plateVerified: false, kind: message.kind,
      durationMs: message.voice?.durationMs ?? null,
      testVehicle: message.testVehicle, createdAt: message.createdAt,
      distanceMeters: Math.round(distanceKm(client, message.position) * 1000),
    }));
    const deliveries = messages.filter(message => message.senderId === client.id)
      .slice(-5).reverse().map(delivery);
    return { ok: true, protocol: 3, features: { walkieTalkie: 1, walkieControls: 1 }, peers, messages: inbox, deliveries };
  }

  function delivery(message) {
    return {
      messageId: message.id,
      targetCount: message.recipients.length,
      appTargetCount: message.appRecipients.length,
      testTargetCount: message.recipients.length - message.appRecipients.length,
      acknowledgedCount: message.acknowledged.size,
    };
  }

  function publish(sender, text, channel, rangeKm, voice = null) {
    const origin = { ...sender, channel };
    const recipients = [...clients.values()].filter(peer => matches(peer, origin, rangeKm));
    const message = {
      id: randomUUID(), senderId: sender.id, senderLabel: sender.label,
      plate4: sender.plate4 ?? null, kind: voice ? 'voice' : 'text', voice,
      testVehicle: !!sender.fixture, text, channel, rangeKm, createdAt: now(),
      position: { latitude: sender.latitude, longitude: sender.longitude },
      recipients: recipients.map(peer => peer.id),
      appRecipients: recipients.filter(peer => !peer.fixture).map(peer => peer.id),
      offered: new Set(), downloaded: new Set(), acknowledged: new Set(),
    };
    messages.push(message);
    return { message, recipients };
  }

  async function readJson(request, limit = 8192) {
    reject((request.headers['content-type'] ?? '').split(';')[0] !== 'application/json',
      'JSON 요청만 지원합니다.', 415);
    const parts = [];
    let length = 0;
    for await (const part of request) {
      length += part.length;
      reject(length > limit, '요청 크기를 초과했습니다.', 413);
      parts.push(part);
    }
    let body;
    try { body = JSON.parse(Buffer.concat(parts).toString('utf8')); }
    catch { reject(true, 'JSON 형식이 올바르지 않습니다.'); }
    reject(!body || typeof body !== 'object' || Array.isArray(body), '요청 형식이 올바르지 않습니다.');
    return body;
  }

  const walkie = createWalkieTalkie({ clients, realClient, matches, now, peerTtlMs });
  const server = http.createServer(async (request, response) => {
    function json(status, value) {
      response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
      response.end(JSON.stringify(value));
    }
    try {
      prune();
      const path = new URL(request.url, 'http://127.0.0.1').pathname;
      if (request.method === 'GET' && path === '/health') {
        return json(200, { ok: true, service: 'CarRadio local relay', protocol: 3, features: { walkieTalkie: 1, walkieControls: 1 } });
      }
      if (request.method === 'GET' && TEST_ASSETS.has(path)) {
        const [file, mime] = TEST_ASSETS.get(path);
        const bytes = await readFile(new URL(file, import.meta.url));
        response.writeHead(200, {
          'Content-Type': mime, 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff',
          'Content-Security-Policy': "default-src 'none'; script-src 'self'; style-src 'self'; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'none'",
        });
        return response.end(bytes);
      }
      reject(request.method !== 'POST', '지원하지 않는 요청입니다.', 405);
      const body = await readJson(request, path === '/v1/voice-messages' ? 196608 : 8192);
      if (path.startsWith('/v1/walkie/')) return json(200, await walkie.handle(path.slice('/v1/walkie/'.length), body));
      if (path === '/v1/sync') {
        reject(typeof body.clientId !== 'string' || !UUID.test(body.clientId), '기기 ID가 올바르지 않습니다.');
        reject(!Number.isFinite(body.latitude) || Math.abs(body.latitude) > 90 ||
          !Number.isFinite(body.longitude) || Math.abs(body.longitude) > 180, '좌표가 올바르지 않습니다.');
        reject(!CHANNELS.has(body.channel) || !RANGES.has(body.rangeKm) ||
          typeof body.receiving !== 'boolean', '채널 또는 수신 설정이 올바르지 않습니다.');
        reject(typeof body.plate4 !== 'string' || !PLATE4.test(body.plate4), '차량번호 뒤 4자리를 숫자로 입력해 주세요.');
        const previous = clients.get(body.clientId);
        reject(previous && previous.plate4 !== body.plate4, '번호를 바꾸려면 먼저 연결을 해제해 주세요.', 409);
        reject(!clients.has(body.clientId) && clients.size >= 128, '테스트 기기 수 제한입니다.', 503);
        const client = {
          id: body.clientId, plate4: body.plate4, label: '차량 ' + body.plate4 + ' (입력번호)',
          latitude: body.latitude, longitude: body.longitude, channel: body.channel,
          rangeKm: body.rangeKm, receiving: body.receiving, lastSeen: now(), fixture: false,
        };
        clients.set(client.id, client);
        walkie.prune();
        return json(200, snapshot(client));
      }
      if (path === '/v1/test-vehicles') {
        const owner = realClient(body.clientId);
        for (const [id, client] of clients) {
          if (client.fixture && client.owner === owner.id) clients.delete(id);
        }
        for (const [suffix, distance, bearing] of [['A', 0.25, 90], ['B', 2, 0]]) {
          const id = 'test-' + owner.id + '-' + suffix;
          clients.set(id, { id, owner: owner.id, label: '테스트 차량 ' + suffix,
            ...destination(owner, distance, bearing), channel: owner.channel,
            rangeKm: 5, receiving: true, fixture: true });
        }
        return json(200, snapshot(owner));
      }
      if (path === '/v1/messages' || path === '/v1/voice-messages') {
        const sender = realClient(body.clientId);
        reject(typeof body.requestId !== 'string' || !UUID.test(body.requestId),
          '요청 ID가 올바르지 않습니다.');
        let voice = null;
        if (path === '/v1/voice-messages') {
          reject(body.mimeType !== 'audio/mp4' || !Number.isInteger(body.durationMs) ||
            body.durationMs < 300 || body.durationMs > 15000, '음성은 0.3~15초 M4A 파일이어야 합니다.');
          reject(typeof body.audioBase64 !== 'string' || body.audioBase64.length > 174764 ||
            !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(body.audioBase64),
            '음성 데이터 형식 또는 크기가 올바르지 않습니다.');
          const audio = Buffer.from(body.audioBase64, 'base64');
          reject(audio.length < 16 || audio.length > MAX_VOICE_BYTES ||
            audio.toString('ascii', 4, 8) !== 'ftyp', '128KiB 이하 M4A 파일만 전송할 수 있습니다.');
          voice = { audio, mimeType: 'audio/mp4', durationMs: body.durationMs };
        } else {
          reject(typeof body.text !== 'string' || !body.text.trim() || body.text.length > 400,
            '메시지는 1~400자여야 합니다.');
        }
        reject(!CHANNELS.has(body.channel) || !RANGES.has(body.rangeKm), '전송 설정이 올바르지 않습니다.');
        const key = sender.id + '/' + body.requestId;
        const fingerprint = JSON.stringify([voice ? createHash('sha256').update(voice.audio).digest('hex') : body.text,
          body.channel, body.rangeKm, voice?.durationMs ?? null, voice ? 'voice' : 'text']);
        let record = requests.get(key);
        if (record) {
          reject(record.fingerprint !== fingerprint, '같은 요청 ID로 다른 메시지를 보낼 수 없습니다.', 409);
        } else {
          // Retries with the same request ID do not consume this per-device burst limit.
          reject(messages.filter(message => message.senderId === sender.id &&
            now() - message.createdAt < 10000).length >= 5,
            '10초에 5건까지 전송할 수 있습니다. 잠시 후 다시 보내 주세요.', 429);
          const result = publish(sender, voice ? '음성 메시지' : body.text.trim(), body.channel, body.rangeKm, voice);
          record = { message: result.message, targetCount: result.recipients.length, fingerprint };
          requests.set(key, record);
          for (const peer of result.recipients.filter(peer => peer.fixture)) {
            publish(peer, '테스트 자동응답: 요청을 확인했습니다.', body.channel, 5);
          }
        }
        return json(200, { ok: true, messageId: record.message.id, targetCount: record.targetCount,
          delivery: delivery(record.message) });
      }
      if (path === '/v1/voice') {
        const receiver = realClient(body.clientId);
        reject(typeof body.messageId !== 'string' || !UUID.test(body.messageId), '메시지 ID가 올바르지 않습니다.');
        const message = messages.find(item => item.id === body.messageId);
        reject(!message || !message.voice, '음성 메시지가 만료되었거나 없습니다.', 404);
        reject(!message.appRecipients.includes(receiver.id) || !message.offered.has(receiver.id),
          '이 기기에 제공된 음성만 받을 수 있습니다.', 403);
        message.downloaded.add(receiver.id);
        return json(200, { ok: true, messageId: message.id, mimeType: message.voice.mimeType,
          durationMs: message.voice.durationMs, audioBase64: message.voice.audio.toString('base64') });
      }
      if (path === '/v1/ack') {
        const receiver = realClient(body.clientId);
        reject(!Array.isArray(body.messageIds) || body.messageIds.length < 1 ||
          body.messageIds.length > 10 || body.messageIds.some(id => typeof id !== 'string' || !UUID.test(id)),
          '수신 확인 메시지 ID는 1~10개여야 합니다.');
        const ids = [...new Set(body.messageIds)];
        const accepted = ids.map(id => {
          const message = messages.find(item => item.id === id);
          reject(!message, '수신 확인할 메시지를 찾을 수 없습니다.', 404);
          reject(!message.appRecipients.includes(receiver.id) || !message.offered.has(receiver.id),
            '이 기기에 제공된 메시지만 수신 확인할 수 있습니다.', 403);
          reject(message.voice && !message.downloaded.has(receiver.id), '음성 파일을 받은 후 수신 확인할 수 있습니다.', 409);
          return message;
        });
        // Validate the entire batch before recording any confirmation.
        for (const message of accepted) message.acknowledged.add(receiver.id);
        return json(200, { ok: true, acknowledgedIds: ids });
      }
      if (path === '/v1/disconnect') {
        const client = realClient(body.clientId);
        clients.delete(client.id);
        prune();
        walkie.prune();
        return json(200, { ok: true });
      }
      json(404, { error: '지원하지 않는 경로입니다.' });
    } catch (error) {
      json(error.status ?? 500, { error: error.status ? error.message : '중계 서버 오류입니다.' });
    }
  });
  server.requestTimeout = 5000;
  server.headersTimeout = 5000;
  server.on('close', () => walkie.close());
  return server;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const server = createRelayServer();
  server.on('error', error => { console.error('Relay startup failed: ' + error.code); process.exitCode = 1; });
  server.listen(8765, '127.0.0.1', () => console.log('CarRadio local relay ready on 127.0.0.1:8765'));
}
