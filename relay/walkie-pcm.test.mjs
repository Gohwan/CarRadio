import test from 'node:test';
import assert from 'node:assert/strict';
import { PcmFrames, inputLevel, testToneFrame } from './walkie-pcm.mjs';

test('input meter handles silence, signed full-scale and RMS rather than peak', () => {
  const bytes = new ArrayBuffer(3200), view = new DataView(bytes);
  assert.equal(inputLevel(bytes), 0);
  for (let i = 0; i < 1600; i++) view.setInt16(i * 2, -32768, true);
  assert.equal(inputLevel(bytes), 1);
  for (let i = 0; i < 1600; i++) view.setInt16(i * 2, i % 2 ? -16384 : 16384, true);
  assert.ok(Math.abs(inputLevel(bytes) - 0.8996567) < 0.0001);
  new Uint8Array(bytes).fill(0); view.setInt16(0, -32768, true);
  assert.ok(Math.abs(inputLevel(bytes) - 0.46598) < 0.0001);
  assert.throws(() => inputLevel(new ArrayBuffer(3199)));
});

for (const rate of [16000, 44100, 48000]) {
  test(rate + 'Hz input yields exact 100ms signed little-endian PCM frames across worklet blocks', () => {
    const frames = [], converter = new PcmFrames(rate, bytes => frames.push(bytes));
    const samples = new Float32Array(rate).fill(0.5);
    for (let i = 0; i < samples.length; i += 128) converter.push(samples.subarray(i, i + 128));
    assert.equal(frames.length, 10);
    for (const bytes of frames) {
      assert.equal(bytes.byteLength, 3200);
      assert.equal(new DataView(bytes).getInt16(0, true), 16384);
    }
  });
}
test('out-of-range and non-finite input cannot wrap or poison following PCM samples', () => {
  const frames = [], converter = new PcmFrames(16000, bytes => frames.push(bytes));
  const samples = new Float32Array(1600);
  samples.set([-2, 2, NaN, Infinity, -0.5]);
  converter.push(samples);
  const view = new DataView(frames[0]);
  assert.deepEqual(Array.from({ length: 5 }, (_, i) => view.getInt16(i * 2, true)), [-32768, 32767, 0, 0, -16384]);
});
test('a new microphone session discards partial speech and resampling phase from the previous session', () => {
  const frames = [], converter = new PcmFrames(48000, bytes => frames.push(bytes));
  converter.push(new Float32Array(4799).fill(1));
  converter.reset();
  converter.push(new Float32Array(4800));
  assert.equal(frames.length, 1);
  assert.ok(new Uint8Array(frames[0]).every(byte => byte === 0));
});
test('microphone-free fixture is a bounded one-second 440Hz PCM signal, not a recording', () => {
  const shorts = [];
  for (let sequence = 0; sequence < 10; sequence++) {
    const view = new DataView(testToneFrame(sequence));
    for (let i = 0; i < 1600; i++) shorts.push(view.getInt16(i * 2, true));
  }
  const crossings = shorts.filter((v, i) => i > 0 && shorts[i - 1] < 0 && v >= 0).length;
  assert.ok(crossings >= 439 && crossings <= 440);
  assert.ok(Math.max(...shorts) <= 2600 && Math.min(...shorts) >= -2600);
});
