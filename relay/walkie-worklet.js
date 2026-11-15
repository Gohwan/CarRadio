import { PcmFrames } from './walkie-pcm.mjs';

class CarRadioPcm extends AudioWorkletProcessor {
  constructor() {
    super();
    this.frames = new PcmFrames(sampleRate, bytes => this.port.postMessage(bytes, [bytes]));
  }
  process(inputs) {
    if (inputs[0]?.[0]) this.frames.push(inputs[0][0]);
    return true;
  }
}
registerProcessor('carradio-pcm', CarRadioPcm);
