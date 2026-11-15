import { inputLevel, testToneFrame } from './walkie-pcm.mjs';

const el = id => document.getElementById(id);
let connection = { connected: false };
let signature = '';
let member = null, generation = 0, joining = false, held = false, starting = false, speaking = false;
let micReady = false, context = null, workletLoaded = false;
let stream = null, captureNode = null, sourceNode = null, silentNode = null;
let talkId = null, sequence = 0, queued = 0, sendChain = Promise.resolve();
let remote = null, nextPlayback = 0, playbackTalk = null, playbackSequence = -1;
let quietMs = 0, meterFrames = 0;
let remoteId = null, supportsMute = false, mutating = false, cooldownUntil = 0;
const mutedSpeakers = new Set();
const playing = new Set();
const message = text => { el('walkie-status').textContent = text; };
const pause = ms => new Promise(resolve => setTimeout(resolve, ms));

function resetInput() {
  quietMs = 0; meterFrames = 0;
  el('walkie-level').value = 0;
  el('walkie-level-hint').textContent = '말하기를 시작하면 입력 크기가 표시됩니다.';
}
function showInput(bytes, testTone = false) {
  if (!held) return;
  const level = inputLevel(bytes);
  quietMs = level < 1 / 6 ? quietMs + 100 : 0;
  if (meterFrames++ % 2 !== 0) return;
  el('walkie-level').value = level;
  el('walkie-level-hint').textContent = testTone ? '테스트음 표시 · 마이크 측정 아님'
    : quietMs >= 1500 ? '입력이 작습니다 · 마이크 설정을 확인하세요.' : '목소리 입력 중';
}

async function api(action, fields = {}, identity = member) {
  const response = await fetch('/v1/walkie/' + action, { method: 'POST', credentials: 'omit',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ clientId: connection.clientId, ...identity, ...fields }),
    signal: AbortSignal.timeout(2500) });
  const value = await response.json();
  if (!response.ok) throw new Error(value.error || '워키토키 연결 오류');
  return value;
}
function controls() {
  const cooldown = Math.max(0, Math.ceil((cooldownUntil - performance.now()) / 1000));
  el('walkie-join').disabled = !connection.connected || !connection.supported || !connection.receiving || joining;
  el('walkie-join').textContent = member ? '워키토키 나가기' : joining ? '참여 중' : '음성 수신에 동의하고 참여';
  el('walkie-mic').disabled = !member || held || starting;
  el('walkie-mic').textContent = micReady ? '마이크 사용 허용됨' : '말하기용 마이크 허용';
  el('walkie-talk').disabled = !member || !micReady || !!remote || cooldown > 0;
  el('walkie-talk').textContent = held ? '말하는 중 · 종료' : remote ? '상대가 말하는 중'
    : cooldown > 0 ? '다음 발언까지 ' + cooldown + '초'
      : el('walkie-tap').checked ? '말하기 시작' : '누른 채 말하기';
  el('walkie-talk').setAttribute('aria-pressed', String(speaking));
  el('walkie-tone').disabled = !member || held || starting || !!remote || cooldown > 0;
  el('walkie-mute').hidden = !supportsMute;
  el('walkie-mute').disabled = !member || !remoteId || mutating || mutedSpeakers.size >= 32;
  el('walkie-unmute').hidden = !supportsMute;
  el('walkie-unmute').disabled = !member || mutedSpeakers.size === 0 || mutating;
  el('walkie-unmute').textContent = '음소거 모두 해제 (' + mutedSpeakers.size + '명)';
}
function stopCapture() {
  captureNode?.disconnect(); sourceNode?.disconnect(); silentNode?.disconnect();
  captureNode = null; sourceNode = null; silentNode = null;
  stream?.getTracks().forEach(track => track.stop()); stream = null;
}
function stopPlayback() {
  for (const source of playing) { try { source.stop(); } catch { /* already ended */ } }
  playing.clear(); nextPlayback = 0; playbackTalk = null; playbackSequence = -1;
}
function stopTalking() {
  if (speaking) cooldownUntil = performance.now() + 2000;
  held = false; speaking = false; stopCapture();
  resetInput();
  const old = talkId, identity = member;
  talkId = null;
  if (old && identity) api('finish', { talkId: old }, identity).catch(() => {});
  controls();
}
function leave(text = '워키토키를 나갔습니다.') {
  generation++;
  stopTalking(); stopPlayback();
  const old = member; member = null; joining = false; remote = null;
  remoteId = null; supportsMute = false; mutating = false; cooldownUntil = 0; mutedSpeakers.clear();
  el('walkie-notice').textContent = '';
  if (old) api('leave', {}, old).catch(() => {});
  context?.close().catch(() => {}); context = null; workletLoaded = false; micReady = false;
  message(text); controls();
}
async function join() {
  if (joining || member || !connection.connected) return;
  const epoch = ++generation;
  joining = true; controls();
  let identity = null;
  try {
    context = new AudioContext({ latencyHint: 'interactive' });
    await context.resume();
    const result = await api('join', { consent: true }, null);
    identity = { clientId: connection.clientId, memberToken: result.memberToken };
    if (epoch !== generation) { await api('leave', {}, identity); return; }
    if (result.format.sampleRate !== 16000 || result.format.frameBytes !== 3200) throw new Error('서버 음성 형식 확인 필요');
    member = identity; joining = false;
    supportsMute = result.features?.speakerMute === true;
    cooldownUntil = performance.now() + (result.nextTalkInMs || 0);
    message('듣기 대기 · 참여한 기기의 목소리가 바로 재생됩니다.'); controls();
    poll(epoch);
  } catch (error) {
    if (identity) api('leave', {}, identity).catch(() => {});
    if (epoch === generation) leave(error.message);
  }
}
function play(event) {
  if (!context || held || context.state !== 'running' || mutedSpeakers.has(event.senderId)) return;
  if (event.talkId === playbackTalk && event.sequence <= playbackSequence) return;
  const binary = atob(event.pcmBase64);
  if (binary.length !== 3200) throw new Error('음성 프레임 크기 오류');
  if (nextPlayback - context.currentTime > 0.5) stopPlayback();
  const buffer = context.createBuffer(1, 1600, 16000), samples = buffer.getChannelData(0);
  for (let i = 0; i < 1600; i++) {
    const value = binary.charCodeAt(i * 2) | binary.charCodeAt(i * 2 + 1) << 8;
    samples[i] = (value >= 32768 ? value - 65536 : value) / 32768;
  }
  const source = context.createBufferSource(); source.buffer = buffer; source.connect(context.destination);
  const when = Math.max(context.currentTime + 0.03, nextPlayback);
  source.start(when); nextPlayback = when + 0.1;
  playing.add(source); source.onended = () => playing.delete(source);
  playbackTalk = event.talkId; playbackSequence = event.sequence;
}
async function poll(epoch) {
  try {
    while (member && epoch === generation) {
      const result = await api('poll', { waitMs: 400 });
      if (epoch !== generation) return;
      for (const event of result.events) {
        if (event.type === 'audio') play(event);
        if (event.type === 'end' && event.talkId === talkId) stopTalking();
      }
      const speaker = result.speaker && !result.speaker.mine && !mutedSpeakers.has(result.speaker.senderId)
        ? result.speaker : null;
      remote = speaker?.label || null; remoteId = speaker?.senderId || null;
      cooldownUntil = Math.max(cooldownUntil, performance.now() + (result.nextTalkInMs || 0));
      if (!held && !starting) message(remote ? remote + ' 말하는 중'
        : '듣기 대기 · 함께 참여한 기기 ' + result.participants + '대');
      controls();
    }
  } catch (error) { if (epoch === generation) leave('워키토키 종료 · ' + error.message); }
}
function encode(bytes) {
  let binary = '';
  for (const byte of new Uint8Array(bytes)) binary += String.fromCharCode(byte);
  return btoa(binary);
}
async function sendFrame(bytes, epoch, id) {
  if (!held || epoch !== generation || talkId !== id) return;
  await api('frame', { talkId: id, sequence: sequence++, pcmBase64: encode(bytes) });
}
async function begin(testTone = false) {
  if (!member || held || starting || remote || performance.now() < cooldownUntil || (!testTone && !micReady)) return;
  held = true; starting = true; controls();
  resetInput();
  el('walkie-notice').textContent = '';
  const epoch = generation, identity = member;
  let startedId = null;
  try {
    await context.resume();
    const result = await api('start'); startedId = result.talkId;
    if (!held || epoch !== generation) { await api('finish', { talkId: startedId }, identity); return; }
    talkId = startedId; sequence = 0; queued = 0; sendChain = Promise.resolve();
    speaking = true; stopPlayback(); controls();
    message(testTone ? '1초 테스트음 전송 중 · 마이크 사용 안 함' : '내 목소리 전송 중 · 최대 15초');
    const beganAt = performance.now();
    if (testTone) {
      for (let i = 0; i < 10 && held && epoch === generation; i++) {
        await pause(Math.max(0, beganAt + (i + 1) * 100 - performance.now()));
        const bytes = testToneFrame(i);
        if (epoch === generation) showInput(bytes, true);
        await sendFrame(bytes, epoch, startedId);
      }
      if (epoch === generation) stopTalking();
    } else {
      const acquired = await navigator.mediaDevices.getUserMedia({ audio: { channelCount: 1,
        echoCancellation: true, noiseSuppression: true }, video: false });
      if (!held || epoch !== generation) { acquired.getTracks().forEach(track => track.stop()); return; }
      stream = acquired;
      sourceNode = context.createMediaStreamSource(stream);
      captureNode = new AudioWorkletNode(context, 'carradio-pcm');
      silentNode = context.createGain(); silentNode.gain.value = 0;
      sourceNode.connect(captureNode); captureNode.connect(silentNode); silentNode.connect(context.destination);
      captureNode.port.onmessage = event => {
        if (!held || epoch !== generation) return;
        showInput(event.data);
        if (++queued > 3) { leave('전송이 지연되어 워키토키를 종료했습니다.'); return; }
        sendChain = sendChain.then(() => sendFrame(event.data, epoch, startedId))
          .catch(error => { if (epoch === generation) leave(error.message); }).finally(() => { queued--; });
      };
      setTimeout(() => { if (epoch === generation && talkId === startedId) stopTalking(); }, 15000);
    }
  } catch (error) { if (epoch === generation) { stopTalking(); message(error.message); } }
  finally {
    if (startedId && (!held || epoch !== generation)) api('finish', { talkId: startedId }, identity).catch(() => {});
    starting = false; controls();
  }
}

el('walkie-join').addEventListener('click', () => { if (member || joining) leave(); else join(); });
el('walkie-mute').addEventListener('click', async () => {
  if (!member || !remoteId || !supportsMute || mutating || mutedSpeakers.size >= 32) return;
  const id = remoteId, epoch = generation;
  mutedSpeakers.add(id); stopPlayback(); remote = null; remoteId = null; mutating = true;
  el('walkie-notice').textContent = '이 발언자의 음성을 껐습니다. 이번 참여 동안 유지됩니다.';
  controls();
  try { await api('mute', { senderId: id }); }
  catch { if (epoch === generation) el('walkie-notice').textContent = '이 기기에서 음소거 중 · 서버 반영 실패. 해제 버튼으로 되돌릴 수 있습니다.'; }
  finally { if (epoch === generation) { mutating = false; controls(); } }
});
el('walkie-unmute').addEventListener('click', async () => {
  if (!member || !supportsMute || mutating) return;
  const epoch = generation; mutating = true; controls();
  try {
    await api('unmute-all');
    if (epoch === generation) {
      mutedSpeakers.clear(); el('walkie-notice').textContent = '음소거를 모두 해제했습니다.';
    }
  } catch { if (epoch === generation) el('walkie-notice').textContent = '음소거 해제 실패 · 연결을 확인한 뒤 다시 시도하세요.'; }
  finally { if (epoch === generation) { mutating = false; controls(); } }
});
el('walkie-mic').addEventListener('click', async () => {
  const epoch = generation;
  try {
    if (!context?.audioWorklet) throw new Error('이 브라우저는 실시간 마이크 입력을 지원하지 않습니다.');
    if (!workletLoaded) {
      await context.audioWorklet.addModule('/walkie-worklet.js');
      if (epoch !== generation) return;
      workletLoaded = true;
    }
    const permission = await navigator.mediaDevices.getUserMedia({ audio: true, video: false });
    permission.getTracks().forEach(track => track.stop());
    if (epoch === generation) { micReady = true; message('마이크 허용됨 · 말하기를 누를 때만 사용합니다.'); controls(); }
  } catch (error) { if (epoch === generation) message('마이크 사용 불가 · ' + error.message); }
});
el('walkie-talk').addEventListener('click', () => { if (el('walkie-tap').checked) { if (held) stopTalking(); else begin(); } });
el('walkie-talk').addEventListener('pointerdown', event => {
  if (!el('walkie-tap').checked && !el('walkie-talk').disabled) {
    el('walkie-talk').setPointerCapture(event.pointerId); begin();
  }
});
for (const name of ['pointerup', 'pointercancel', 'lostpointercapture']) {
  el('walkie-talk').addEventListener(name, () => { if (!el('walkie-tap').checked) stopTalking(); });
}
el('walkie-talk').addEventListener('keydown', event => {
  if (!el('walkie-tap').checked && [' ', 'Enter'].includes(event.key)) { event.preventDefault(); if (!event.repeat) begin(); }
});
el('walkie-talk').addEventListener('keyup', event => {
  if (!el('walkie-tap').checked && [' ', 'Enter'].includes(event.key)) { event.preventDefault(); stopTalking(); }
});
el('walkie-talk').addEventListener('blur', () => { if (!el('walkie-tap').checked) stopTalking(); });
el('walkie-tap').addEventListener('change', stopTalking);
el('walkie-tone').addEventListener('click', () => begin(true));
document.addEventListener('visibilitychange', () => { if (document.hidden) leave('화면을 벗어나 워키토키를 종료했습니다.'); });
window.addEventListener('pagehide', () => leave());
window.CarRadioWalkie = { update(value) {
  const next = JSON.stringify([value.connected, value.clientId, value.channel, value.rangeKm, value.receiving]);
  if (signature && signature !== next && (member || joining)) leave('연결·채널·범위·수신 설정이 바뀌어 참여를 종료했습니다.');
  connection = value; signature = next;
  controls();
} };
window.CarRadioWalkie.update(window.CarRadioTestConnection?.() ?? { connected: false });
