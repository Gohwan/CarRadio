import { randomUUID } from 'node:crypto';

export const WALKIE_FORMAT = Object.freeze({ sampleRate: 16000, channels: 1, frameMs: 100, frameBytes: 3200 });
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const BASE64 = /^[A-Za-z0-9+/]+={0,2}$/;
const fail = (bad, message, status = 400) => {
  if (bad) throw Object.assign(new Error(message), { status });
};

/** Ephemeral PTT: no audio files, no inbox history, bounded live queues. Local-test identities only. */
export function createWalkieTalkie({ clients, realClient, matches, now, peerTtlMs }) {
  const members = new Map();
  const floors = new Map();
  const cooldowns = new Map();
  let closed = false;

  function eligible(member, talk) {
    const client = clients.get(member.id);
    return !!client && !member.muted.has(talk.owner) && client.receiving && client.channel === member.channel &&
      client.rangeKm === member.rangeKm && matches(client, talk.position, talk.rangeKm);
  }
  function wake(member) { member.waiter?.(); }
  function enqueue(member, event, talk = null) {
    member.queue.push({ event, talk, time: now() });
    // A slow receiver skips old speech instead of accumulating a delayed recording.
    while (member.queue.length > 16) member.queue.shift();
    wake(member);
  }
  function end(talk, reason) {
    if (floors.get(talk.channel) !== talk) return;
    floors.delete(talk.channel);
    cooldowns.set(talk.owner, now() + 2000);
    for (const member of members.values()) {
      if (member.id === talk.owner || talk.listeners.has(member.id)) {
        enqueue(member, { type: 'end', talkId: talk.id, reason }, talk);
      }
    }
  }
  function remove(member, reason) {
    const talk = floors.get(member.channel);
    if (talk?.owner === member.id) end(talk, reason);
    members.delete(member.id);
    member.queue.length = 0;
    wake(member);
  }
  function prune() {
    for (const [id, until] of cooldowns) if (until <= now()) cooldowns.delete(id);
    for (const member of members.values()) {
      const client = clients.get(member.id);
      if (!client || client.fixture || !client.receiving || client.channel !== member.channel ||
          client.rangeKm !== member.rangeKm || now() - client.lastSeen > peerTtlMs ||
          now() - member.lastSeen > 8000) remove(member, 'left');
    }
    for (const talk of floors.values()) {
      const client = clients.get(talk.owner);
      if (client) talk.position = { ...client };
      if (!members.has(talk.owner)) end(talk, 'left');
      else if (now() - talk.startedAt >= 15000) end(talk, 'limit');
      else if (now() - talk.lastFrameAt >= 2000) end(talk, 'timeout');
    }
  }
  function memberFor(body) {
    realClient(body.clientId);
    const member = members.get(body.clientId);
    fail(!member || member.token !== body.memberToken, '워키토키에 다시 참여해 주세요.', 409);
    member.lastSeen = now();
    return member;
  }
  function info(member) {
    const client = clients.get(member.id);
    const talk = floors.get(member.channel);
    const audible = talk && (talk.owner === member.id || talk.listeners.has(member.id) && eligible(member, talk));
    return {
      joined: true,
      features: { speakerMute: true, cooldownMs: 2000 },
      nextTalkInMs: Math.max(0, (cooldowns.get(member.id) || 0) - now()),
      mutedCount: member.muted.size,
      participants: [...members.values()].filter(peer => peer.id !== member.id &&
        clients.has(peer.id) && matches(clients.get(peer.id), client)).length,
      speaker: audible ? { talkId: talk.id, senderId: talk.owner, label: talk.label, plate4: talk.plate4,
        mine: talk.owner === member.id, elapsedMs: Math.max(0, now() - talk.startedAt) } : null,
    };
  }
  function drain(member) {
    const queued = member.queue.splice(0);
    const events = queued.filter(item => {
      if (item.event.type !== 'audio' && item.event.type !== 'start') return true;
      return (item.event.type !== 'audio' || now() - item.time <= 1000) && item.talk && eligible(member, item.talk);
    }).map(item => item.event);
    return { ok: true, ...info(member), events };
  }
  async function handle(action, body) {
    fail(closed, '워키토키 서버가 종료되었습니다.', 503);
    prune();
    if (action === 'join') {
      const client = realClient(body.clientId);
      fail(!client.receiving, '수신 허용을 켠 뒤 참여해 주세요.', 409);
      fail(body.consent !== true, '음성 송수신 동의가 필요합니다.');
      const old = members.get(client.id);
      if (old) remove(old, 'rejoined');
      const member = { id: client.id, token: randomUUID(), channel: client.channel,
        rangeKm: client.rangeKm, lastSeen: now(), queue: [], waiter: null, muted: new Set() };
      members.set(client.id, member);
      const talk = floors.get(client.channel);
      if (talk && eligible(member, talk)) {
        talk.listeners.add(member.id);
        enqueue(member, { type: 'start', talkId: talk.id, senderId: talk.owner, label: talk.label }, talk);
      }
      return { ok: true, memberToken: member.token, format: WALKIE_FORMAT, maxTalkMs: 15000, ...info(member) };
    }
    const member = memberFor(body);
    if (action === 'leave') {
      remove(member, 'left');
      return { ok: true };
    }
    if (action === 'mute') {
      fail(typeof body.senderId !== 'string' || !UUID.test(body.senderId) || body.senderId === member.id,
        '음소거할 발언자가 올바르지 않습니다.');
      const talk = floors.get(member.channel);
      fail(!talk || talk.owner !== body.senderId || !talk.listeners.has(member.id),
        '현재 듣고 있는 발언자만 음소거할 수 있습니다.', 409);
      fail(!member.muted.has(body.senderId) && member.muted.size >= 32,
        '이번 참여에서는 최대 32명을 음소거할 수 있습니다.', 409);
      member.muted.add(body.senderId);
      member.queue = member.queue.filter(item => item.talk?.owner !== body.senderId);
      enqueue(member, { type: 'end', talkId: talk.id, reason: 'muted' });
      return { ok: true, ...info(member) };
    }
    if (action === 'unmute-all') {
      member.queue = member.queue.filter(item => !member.muted.has(item.talk?.owner));
      member.muted.clear();
      return { ok: true, ...info(member) };
    }
    if (action === 'start') {
      const remaining = (cooldowns.get(member.id) || 0) - now();
      fail(remaining > 0, '다음 발언까지 ' + Math.ceil(remaining / 1000) + '초 기다려 주세요.', 429);
      const existing = floors.get(member.channel);
      fail(!!existing, '다른 참여자가 말하고 있습니다. 잠시 후 다시 누르세요.', 409);
      const client = clients.get(member.id);
      const talk = { id: randomUUID(), owner: member.id, memberToken: member.token,
        channel: member.channel, rangeKm: member.rangeKm, position: { ...client },
        label: client.label, plate4: client.plate4, startedAt: now(), lastFrameAt: now(),
        sequence: 0, listeners: new Set() };
      floors.set(talk.channel, talk);
      for (const peer of members.values()) {
        if (peer.id === member.id || eligible(peer, talk)) {
          if (peer.id !== member.id) talk.listeners.add(peer.id);
          enqueue(peer, { type: 'start', talkId: talk.id, senderId: talk.owner, label: talk.label }, talk);
        }
      }
      return { ok: true, talkId: talk.id, maxTalkMs: 15000, ...info(member) };
    }
    if (action === 'frame' || action === 'finish') {
      fail(typeof body.talkId !== 'string' || !UUID.test(body.talkId), '발언 ID가 올바르지 않습니다.');
      const talk = floors.get(member.channel);
      if (action === 'finish' && (!talk || talk.id !== body.talkId)) return { ok: true };
      fail(!talk || talk.id !== body.talkId || talk.owner !== member.id ||
        talk.memberToken !== member.token, '발언권이 없거나 종료되었습니다.', 409);
      if (action === 'finish') {
        end(talk, 'released');
        return { ok: true };
      }
      fail(!Number.isInteger(body.sequence) || body.sequence !== talk.sequence,
        '음성 순서가 올바르지 않습니다.', 409);
      fail(typeof body.pcmBase64 !== 'string' || body.pcmBase64.length !== 4268 ||
        !BASE64.test(body.pcmBase64), '100ms PCM 음성 프레임이 필요합니다.');
      const pcm = Buffer.from(body.pcmBase64, 'base64');
      fail(pcm.length !== WALKIE_FORMAT.frameBytes || pcm.toString('base64') !== body.pcmBase64,
        '음성 프레임 형식이 올바르지 않습니다.');
      // Enforce a real-time budget too: malicious senders cannot dump recordings at network speed.
      fail((talk.sequence + 1) * WALKIE_FORMAT.frameMs > now() - talk.startedAt + 300,
        '음성 전송 속도가 너무 빠릅니다.', 429);
      talk.lastFrameAt = now();
      talk.sequence++;
      for (const id of talk.listeners) {
        const peer = members.get(id);
        if (peer && eligible(peer, talk)) enqueue(peer, {
          type: 'audio', talkId: talk.id, senderId: talk.owner, sequence: body.sequence, pcmBase64: body.pcmBase64,
        }, talk);
      }
      return { ok: true, sequence: body.sequence };
    }
    if (action === 'poll') {
      fail(member.waiter !== null, '이미 음성을 수신 중입니다.', 409);
      const waitMs = body.waitMs === undefined ? 400 : body.waitMs;
      fail(!Number.isInteger(waitMs) || waitMs < 0 || waitMs > 500, '수신 대기 시간이 올바르지 않습니다.');
      if (!member.queue.length && waitMs > 0) {
        await new Promise(resolve => {
          const timer = setTimeout(done, waitMs);
          function done() { clearTimeout(timer); member.waiter = null; resolve(); }
          member.waiter = done;
        });
      }
      prune();
      fail(members.get(member.id) !== member, '워키토키 참여가 종료되었습니다.', 409);
      return drain(member);
    }
    fail(true, '지원하지 않는 워키토키 경로입니다.', 404);
  }
  const timer = setInterval(prune, 250);
  timer.unref();
  return { handle, prune, close() {
    closed = true;
    clearInterval(timer);
    for (const member of [...members.values()]) remove(member, 'closed');
  } };
}
