const el = id => document.getElementById(id);
const clientId = crypto.randomUUID();
let connected = false;
let busy = false;
let pending = null;
let latestDelivery = null;
const acknowledgedIds = new Set();
const voiceCache = new Map();
let voicePending = null;
let walkieSupported = false;
window.CarRadioTestConnection = () => ({ connected, clientId, supported: walkieSupported,
  channel: el('channel').value, rangeKm: Number(el('range').value), receiving: el('receiving').checked });
el('identity').textContent = '이 창의 기기' + ' ' + clientId.slice(-4);
const status = text => { el('status').textContent = text; };
function controls() {
  window.CarRadioWalkie?.update(window.CarRadioTestConnection());
  el('connect').disabled = busy || connected;
  el('disconnect').disabled = busy || !connected;
  el('send').disabled = busy || !connected || !el('message').value.trim();
  el('plate4').disabled = busy || connected;
  el('send-voice').disabled = busy || !connected || !el('voice-file').files.length;
}
function settings() {
  const latitudeText = el('latitude').value.trim();
  const longitudeText = el('longitude').value.trim();
  const latitude = Number(latitudeText);
  const longitude = Number(longitudeText);
  if (!latitudeText || !longitudeText || !Number.isFinite(latitude) || Math.abs(latitude) > 90 ||
      !Number.isFinite(longitude) || Math.abs(longitude) > 180) {
    throw new Error('위도 -90~90, 경도 -180~180 범위의 좌표를 입력하세요.');
  }
  const plate4 = el('plate4').value.trim();
  if (!/^[0-9]{4}$/.test(plate4)) throw new Error('차량번호 뒤 4자리를 숫자로 입력하세요.');
  el('identity').textContent = '이 창: 차량 ' + plate4 + ' (입력번호)';
  return { clientId, plate4, latitude, longitude, channel: el('channel').value,
    rangeKm: Number(el('range').value), receiving: el('receiving').checked };
}
async function post(path, body) {
  const response = await fetch(path, { method: 'POST', credentials: 'omit',
    headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
    signal: AbortSignal.timeout(3500) });
  const value = await response.json();
  if (!response.ok) throw new Error(value.error || '서버 응답 오류');
  return value;
}
function placeholder(list, text) {
  const li = document.createElement('li');
  li.textContent = text;
  list.replaceChildren(li);
}
function render(snapshot) {
  const peers = el('peers');
  peers.replaceChildren();
  if (!snapshot.peers.length) placeholder(peers, '같은 채널·범위 안에 연결된 기기가 없습니다.');
  for (const peer of snapshot.peers) {
    const li = document.createElement('li');
    li.textContent = peer.label + ' · ' + peer.distanceMeters + 'm' + (peer.testVehicle ? ' · 자동응답 테스트 차량' : '');
    peers.append(li);
  }
  const inbox = el('inbox');
  const retained = new Map([...inbox.children].filter(item => item.dataset.id).map(item => [item.dataset.id, item]));
  for (const item of [...inbox.children]) {
    if (!snapshot.messages.some(message => message.id === item.dataset.id)) item.remove();
  }
  if (!snapshot.messages.length) placeholder(inbox, el('receiving').checked
    ? '받은 메시지가 없습니다.' : '수신 허용이 꺼져 있습니다.');
  for (const message of snapshot.messages) {
    const li = retained.get(message.id) || document.createElement('li');
    li.dataset.id = message.id;
    const meta = li.querySelector('.message-meta') || document.createElement('div');
    meta.className = 'message-meta';
    meta.textContent = message.sender + ' · ' + message.distanceMeters + 'm · ' +
      new Date(message.createdAt).toLocaleTimeString('ko-KR');
    const text = li.querySelector('.message-text') || document.createElement('div');
    text.className = 'message-text';
    text.textContent = message.kind === 'voice'
      ? '음성 메시지 · ' + Math.ceil(message.durationMs / 1000) + '초' : message.text;
    if (!retained.has(message.id)) li.append(meta, text);
    if (message.kind === 'voice' && voiceCache.has(message.id) && !li.querySelector('audio')) {
      const audio = document.createElement('audio');
      audio.controls = true;
      audio.preload = 'metadata';
      audio.src = voiceCache.get(message.id).url;
      audio.setAttribute('aria-label', message.sender + ' 목소리 듣기');
      li.append(audio);
    }
    // Reordering an existing audio node interrupts playback in some browsers.
    if (!retained.has(message.id)) {
      const older = snapshot.messages.slice(snapshot.messages.indexOf(message) + 1)
        .map(item => retained.get(item.id)).find(Boolean);
      inbox.insertBefore(li, older || null);
    }
  }
}
function deliveryText(value) {
  let text = 'PC 서버 접수';
  if (!value.targetCount) text += ' · 수신 대상 없음';
  else if (!value.appTargetCount) text += ' · 앱 수신 확인 대상 없음';
  else {
    text += ' · 앱 수신 확인 ' + value.acknowledgedCount + '/' + value.appTargetCount + '대';
    if (value.acknowledgedCount < value.appTargetCount) text += ' · 확인 대기';
  }
  if (value.testTargetCount) text += ' · 자동응답 테스트 차량 ' + value.testTargetCount + '대 별도';
  return text;
}
function refreshDelivery(values) {
  if (!latestDelivery) return;
  const current = values?.find(value => value.messageId === latestDelivery.messageId);
  if (current) {
    latestDelivery = { ...current,
      acknowledgedCount: Math.max(latestDelivery.acknowledgedCount, current.acknowledgedCount) };
    el('transmission').textContent = deliveryText(latestDelivery);
  } else {
    el('transmission').textContent = '마지막 확인 · ' + deliveryText(latestDelivery) + ' · 현재 상태 조회 불가';
  }
}
async function synchronize() {
  const snapshot = await post('/v1/sync', settings());
  connected = true;
  walkieSupported = snapshot.features?.walkieTalkie === 1;
  const liveIds = new Set(snapshot.messages.map(item => item.id));
  for (const [id, cached] of voiceCache) {
    if (!liveIds.has(id)) { URL.revokeObjectURL(cached.url); voiceCache.delete(id); }
  }
  for (const message of snapshot.messages.filter(item => item.kind === 'voice' && !voiceCache.has(item.id))) {
    try {
      const voice = await post('/v1/voice', { clientId, messageId: message.id });
      const binary = atob(voice.audioBase64);
      if (voice.messageId !== message.id || voice.mimeType !== 'audio/mp4' || binary.length > 131072) throw new Error('음성 파일 오류');
      const bytes = Uint8Array.from(binary, value => value.charCodeAt(0));
      const blob = new Blob([bytes], { type: 'audio/mp4' });
      voiceCache.set(message.id, { blob, url: URL.createObjectURL(blob) });
    } catch { status('음성 파일 수신 재시도 대기'); }
  }
  render(snapshot);
  refreshDelivery(snapshot.deliveries);
  status('연결됨 · 기본 2초 간격 갱신');
  if (snapshot.protocol >= 2) {
    const ids = snapshot.messages.filter(item => item.kind !== 'voice' || voiceCache.has(item.id)).map(item => item.id).filter(id => !acknowledgedIds.has(id)).slice(0, 10);
    if (ids.length) {
      try {
        await post('/v1/ack', { clientId, messageIds: ids });
        ids.forEach(id => acknowledgedIds.add(id));
        while (acknowledgedIds.size > 256) acknowledgedIds.delete(acknowledgedIds.values().next().value);
      } catch {
        status('연결됨 · 수신 확인 재전송 대기');
      }
    }
  }
}
async function action(work) {
  if (busy) return;
  busy = true;
  controls();
  try { await work(); }
  catch (error) { status('오류: ' + error.message + ' · 연결 또는 설정을 확인하세요.'); }
  finally { busy = false; controls(); }
}
el('connect').addEventListener('click', () => action(synchronize));
el('disconnect').addEventListener('click', () => action(async () => {
  try { await post('/v1/disconnect', { clientId }); }
  finally {
    connected = false;
    acknowledgedIds.clear();
    for (const cached of voiceCache.values()) URL.revokeObjectURL(cached.url);
    voiceCache.clear();
    if (latestDelivery) el('transmission').textContent = deliveryText(latestDelivery) + ' · 연결 해제 후 갱신 중단';
    placeholder(el('peers'), '연결 후 확인할 수 있습니다.');
    placeholder(el('inbox'), '연결 후 확인할 수 있습니다.');
    status('미연결');
  }
}));
el('send').addEventListener('click', () => action(async () => {
  if (!connected) return;
  const text = el('message').value.trim();
  if (!text) return;
  const selected = settings();
  await synchronize();
  const fingerprint = JSON.stringify([text, selected.channel, selected.rangeKm]);
  if (!pending || pending.fingerprint !== fingerprint) {
    pending = { fingerprint, body: { clientId, requestId: crypto.randomUUID(),
      text, channel: selected.channel, rangeKm: selected.rangeKm } };
  }
  const result = await post('/v1/messages', pending.body);
  latestDelivery = result.delivery || null;
  el('transmission').textContent = latestDelivery ? deliveryText(latestDelivery)
    : 'PC 서버 접수 · 수신 대상 ' + result.targetCount + '대 · 서버의 수신 확인 기능 갱신 필요';
  if (el('message').value.trim() === text) el('message').value = '';
  pending = null;
  updateLength();
  await synchronize();
}));
function updateLength() {
  el('length').textContent = el('message').value.length + '/200';
  controls();
}
el('message').addEventListener('input', updateLength);
for (const id of ['channel', 'range', 'receiving', 'latitude', 'longitude']) {
  el(id).addEventListener('change', () => { if (connected) action(synchronize); });
}
setInterval(() => { if (connected && !busy) action(synchronize); }, 2000);
window.addEventListener('pagehide', () => {
  if (connected) navigator.sendBeacon('/v1/disconnect',
    new Blob([JSON.stringify({ clientId })], { type: 'application/json' }));
});
controls();

el('plate4').addEventListener('input', () => {
  el('plate4').value = el('plate4').value.replace(/[^0-9]/g, '').slice(0, 4);
});
el('voice-file').addEventListener('change', () => { voicePending = null; controls(); });
el('send-voice').addEventListener('click', () => action(async () => {
  const file = el('voice-file').files[0];
  if (!file || !connected) return;
  const selected = settings();
  if (!file.size || file.size > 131072) throw new Error('128KiB 이하 M4A 파일을 선택하세요.');
  if (!voicePending) {
    const bytes = new Uint8Array(await file.arrayBuffer());
    if (String.fromCharCode(...bytes.slice(4, 8)) !== 'ftyp') throw new Error('M4A 파일 형식을 확인하세요.');
    const url = URL.createObjectURL(file);
    const audio = document.createElement('audio');
    let durationMs;
    try {
      durationMs = await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('음성 길이를 읽을 수 없습니다.')), 3000);
        audio.onloadedmetadata = () => { clearTimeout(timer); resolve(Math.round(audio.duration * 1000)); };
        audio.onerror = () => { clearTimeout(timer); reject(new Error('이 브라우저에서 읽을 수 없는 음성입니다.')); };
        audio.preload = 'metadata'; audio.src = url;
      });
    } finally { audio.removeAttribute('src'); audio.load(); URL.revokeObjectURL(url); }
    if (!Number.isFinite(durationMs) || durationMs < 300 || durationMs > 15000) throw new Error('0.3~15초 음성을 선택하세요.');
    let binary = '';
    for (const byte of bytes) binary += String.fromCharCode(byte);
    voicePending = { clientId, requestId: crypto.randomUUID(), channel: selected.channel,
      rangeKm: selected.rangeKm, mimeType: 'audio/mp4', durationMs, audioBase64: btoa(binary) };
  }
  await synchronize();
  const result = await post('/v1/voice-messages', voicePending);
  latestDelivery = result.delivery;
  el('transmission').textContent = deliveryText(latestDelivery);
  el('voice-status').textContent = '음성 서버 접수 · 파일을 받은 앱의 수신 확인을 기다립니다.';
  el('voice-file').value = '';
  voicePending = null;
  await synchronize();
}));
