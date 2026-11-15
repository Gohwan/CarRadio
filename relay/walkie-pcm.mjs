/** Small, bounded PCM converter shared by the audio worklet and microphone-free tests. */
export class PcmFrames {
  constructor(inputRate, onFrame) {
    if (!Number.isFinite(inputRate) || inputRate < 16000 || inputRate > 192000) throw new Error('Unsupported input rate');
    this.inputRate = inputRate;
    this.onFrame = onFrame;
    this.reset();
  }
  reset() {
    this.phase = 0; this.sum = 0; this.count = 0;
    this.bytes = new ArrayBuffer(3200); this.view = new DataView(this.bytes); this.offset = 0;
  }
  push(samples) {
    for (const value of samples) {
      this.sum += Number.isFinite(value) ? value : 0;
      this.count++;
      this.phase += 16000;
      if (this.phase < this.inputRate) continue;
      this.phase -= this.inputRate;
      const sample = Math.max(-1, Math.min(1, this.sum / this.count));
      this.sum = 0; this.count = 0;
      this.view.setInt16(this.offset, Math.round(sample * (sample < 0 ? 32768 : 32767)), true);
      this.offset += 2;
      if (this.offset === 3200) {
        const complete = this.bytes;
        this.bytes = new ArrayBuffer(3200); this.view = new DataView(this.bytes); this.offset = 0;
        this.onFrame(complete);
      }
    }
  }
}

export function testToneFrame(sequence) {
  const bytes = new ArrayBuffer(3200), view = new DataView(bytes);
  for (let i = 0; i < 1600; i++) view.setInt16(i * 2,
    Math.round(Math.sin(2 * Math.PI * 440 * (sequence * 1600 + i) / 16000) * 2600), true);
  return bytes;
}

/** Input activity in -60..0 dBFS, normalized to 0..1; not a physical sound level. */
export function inputLevel(bytes) {
  if (!(bytes instanceof ArrayBuffer) || bytes.byteLength !== 3200) throw new Error('Expected one PCM frame');
  const view = new DataView(bytes);
  let power = 0;
  for (let i = 0; i < 1600; i++) power += view.getInt16(i * 2, true) ** 2;
  if (power === 0) return 0;
  const db = 20 * Math.log10(Math.sqrt(power / 1600) / 32768);
  return Math.max(0, Math.min(1, (db + 60) / 60));
}
