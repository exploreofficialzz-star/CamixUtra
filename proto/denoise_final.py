"""Reference for audio/SpectralDenoiser (Kotlin port mirrors this). Minimum-statistics noise tracker + spectral subtraction."""
import numpy as np
from beat_proto import Fft
from dsp_proto import SR, snr_db, speech_like

class Denoiser2:
    N = 512; HOP = 256; BINS = 257
    SUB = 43           # frames per sub-window (~0.25 s)
    NSUB = 6           # sub-windows kept (~1.5 s)
    def __init__(self, floor_gain=0.2, over=1.5, bias=1.8):
        self.fft = Fft(self.N)
        n = np.arange(self.N)
        self.win = np.sqrt(0.5 - 0.5*np.cos(2*np.pi*n/self.N))
        self.inbuf = np.zeros(0)
        self.outq = list(np.zeros(self.N - self.HOP))
        self.ola = np.zeros(self.N)
        self.P = None                       # smoothed power per bin
        self.subMin = np.full(self.BINS, 1e9)
        self.ring = []                      # list of sub-window minima
        self.subCount = 0
        self.noise = None                   # noise power estimate
        self.gprev = np.ones(self.BINS)
        self.floor = floor_gain; self.over = over; self.bias = bias
    def process(self, x):
        self.inbuf = np.concatenate([self.inbuf, x]); 
        while len(self.inbuf) >= self.N:
            frame = self.inbuf[:self.N]; self.inbuf = self.inbuf[self.HOP:]
            self._frame(frame)
            self.outq.extend(self.ola[:self.HOP])
            self.ola = np.concatenate([self.ola[self.HOP:], np.zeros(self.HOP)])
        take = min(len(x), len(self.outq))
        res = np.array(self.outq[:take]); del self.outq[:take]
        if take < len(x): res = np.concatenate([res, np.zeros(len(x)-take)])
        return res
    def _frame(self, frame):
        re = list(frame*self.win); im = [0.0]*self.N
        self.fft.forward(re, im)
        re = np.array(re); im = np.array(im)
        pw = re[:self.BINS]**2 + im[:self.BINS]**2
        if self.P is None: self.P = pw.copy()
        else: self.P = 0.85*self.P + 0.15*pw
        self.subMin = np.minimum(self.subMin, self.P)
        self.subCount += 1
        if self.noise is None: self.noise = self.subMin*self.bias        # early estimate
        if self.subCount >= self.SUB:
            self.ring.append(self.subMin.copy()); 
            if len(self.ring) > self.NSUB: self.ring.pop(0)
            self.noise = np.min(np.array(self.ring), axis=0)*self.bias
            self.subMin = np.full(self.BINS, 1e9); self.subCount = 0
        else:
            self.noise = np.minimum(self.noise, self.subMin*self.bias) if len(self.ring) == 0 else self.noise
        # power spectral subtraction gain
        g2 = 1.0 - self.over*self.noise/(pw + 1e-12)
        g = np.sqrt(np.maximum(g2, self.floor**2))
        g = np.where(g > self.gprev, g, 0.55*self.gprev + 0.45*g)
        self.gprev = g
        gs = g.copy(); gs[1:-1] = 0.25*g[:-2] + 0.5*g[1:-1] + 0.25*g[2:]
        re[:self.BINS] *= gs; im[:self.BINS] *= gs
        for k in range(1, self.N//2):
            re[self.N-k] = re[k]; im[self.N-k] = -im[k]
        r2 = list(re); i2 = list(-im)
        self.fft.forward(r2, i2)
        y = np.array(r2)/self.N * self.win
        self.ola += y

if __name__ == "__main__":
    rng = np.random.default_rng(0)
    clean, _ = speech_like(10)
    lat = Denoiser2.N - Denoiser2.HOP
    for snr_in in (20, 10, 5, 0):
        noise = rng.normal(0, 1, len(clean)); noise *= np.sqrt((clean**2).mean()/10**(snr_in/10)/(noise**2).mean())
        noisy = clean + noise
        dn = Denoiser2(); out = np.concatenate([dn.process(noisy[i:i+1024]) for i in range(0, len(noisy), 1024)])
        s0 = SR*2
        c = clean[s0:len(clean)-lat]; o = out[s0+lat:len(clean)]
        n_ = noisy[s0:len(clean)-lat]
        print(f"in SNR {snr_in:2d} dB -> out SNR {snr_db(c, o):5.1f} dB (input {snr_db(c, n_):5.1f})   peak {np.abs(out).max():.2f}")
    # noise-only attenuation
    for amp in (0.01, 0.05):
        noise = rng.normal(0, amp, SR*6)
        dn = Denoiser2(); out = np.concatenate([dn.process(noise[i:i+1024]) for i in range(0, len(noise), 1024)])
        a = 20*np.log10(np.sqrt((out[SR*3:]**2).mean())/np.sqrt((noise[SR*3:]**2).mean()))
        print(f"noise-only amp={amp}: attenuation after 3 s = {a:.1f} dB")
    # pink-ish (low-passed) noise
    from scipy.signal import lfilter
    pn = lfilter([1], [1, -0.95], rng.normal(0, 0.05, SR*6))
    dn = Denoiser2(); out = np.concatenate([dn.process(pn[i:i+1024]) for i in range(0, len(pn), 1024)])
    print("coloured noise attenuation: %.1f dB" % (20*np.log10(np.sqrt((out[SR*3:]**2).mean())/np.sqrt((pn[SR*3:]**2).mean()))))
    # speech must survive: clean speech through denoiser (noise estimate learned from tiny noise floor)
    sp = clean + rng.normal(0, 0.002, len(clean))
    dn = Denoiser2(); out = np.concatenate([dn.process(sp[i:i+1024]) for i in range(0, len(sp), 1024)])
    s0 = SR*2
    print("clean speech passthrough SNR: %.1f dB" % snr_db(clean[s0:len(clean)-lat], out[s0+lat:len(clean)]))
    # speech starting immediately (no leading noise-only period)
    noisy = clean + rng.normal(0, 0.03, len(clean))
    dn = Denoiser2(); out = np.concatenate([dn.process(noisy[i:i+1024]) for i in range(0, len(noisy), 1024)])
    print("speech from t=0, SNR:", round(snr_db(clean[s0:len(clean)-lat], noisy[s0:len(clean)-lat]),1), "->", round(snr_db(clean[s0:len(clean)-lat], out[s0+lat:len(clean)]),1))
    # steady tone (music-like) gets eaten => documented limitation (VOICE preset only)
    t = np.arange(SR*6)/SR; tone = 0.3*np.sin(2*np.pi*440*t)
    dn = Denoiser2(); out = np.concatenate([dn.process(tone[i:i+1024]) for i in range(0, len(tone), 1024)])
    print("steady 440 Hz tone level after 5 s: %.1f dB (limitation: stationary sounds are treated as noise)" % (20*np.log10(np.abs(out[-SR:]).max()/0.3)))
