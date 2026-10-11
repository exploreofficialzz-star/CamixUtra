"""Reference implementations of the audio DSP used by CamixUltra (Kotlin port mirrors these)."""
import numpy as np
from beat_proto import Fft

SR = 44100

# ---------------------------------------------------------------- Biquad (RBJ cookbook, direct form I -> transposed DF2)
class Biquad:
    def __init__(self): self.b0=1.0; self.b1=self.b2=self.a1=self.a2=0.0; self.z1=self.z2=0.0
    def set(self, b0,b1,b2,a0,a1,a2):
        self.b0=b0/a0; self.b1=b1/a0; self.b2=b2/a0; self.a1=a1/a0; self.a2=a2/a0
    @staticmethod
    def highpass(fc, q=0.70710678):
        w0 = 2*np.pi*fc/SR; c=np.cos(w0); s=np.sin(w0); al = s/(2*q)
        f = Biquad(); f.set((1+c)/2, -(1+c), (1+c)/2, 1+al, -2*c, 1-al); return f
    @staticmethod
    def peaking(fc, gain_db, q=0.9):
        A = 10**(gain_db/40); w0 = 2*np.pi*fc/SR; c=np.cos(w0); s=np.sin(w0); al = s/(2*q)
        f = Biquad(); f.set(1+al*A, -2*c, 1-al*A, 1+al/A, -2*c, 1-al/A); return f
    @staticmethod
    def lowshelf(fc, gain_db, slope=1.0): pass
    def process(self, x):
        y = np.empty_like(x)
        z1=self.z1; z2=self.z2
        for i in range(len(x)):
            xi = x[i]
            yi = self.b0*xi + z1
            z1 = self.b1*xi - self.a1*yi + z2
            z2 = self.b2*xi - self.a2*yi
            y[i] = yi
        self.z1=z1; self.z2=z2
        return y

# ---------------------------------------------------------------- Spectral noise suppressor (streaming STFT)
class Denoiser:
    N = 512; HOP = 256; BINS = 257
    def __init__(self, floor_gain=0.25, over=1.6):
        self.fft = Fft(self.N)
        n = np.arange(self.N)
        self.win = np.sqrt(0.5 - 0.5*np.cos(2*np.pi*n/self.N))   # sqrt-Hann (analysis & synthesis)
        self.inbuf = np.zeros(0); self.outq = list(np.zeros(self.N - self.HOP))  # pre-fill => constant latency
        self.ola = np.zeros(self.N)
        self.noise = np.full(self.BINS, 1e-4); self.init = 0
        self.gprev = np.ones(self.BINS)
        self.floor = floor_gain; self.over = over
        self.nacc = np.zeros(self.BINS)
    def process(self, x):
        self.inbuf = np.concatenate([self.inbuf, x])
        out = []
        while len(self.inbuf) >= self.N:
            frame = self.inbuf[:self.N]; self.inbuf = self.inbuf[self.HOP:]
            self._frame(frame)
            self.outq.extend(self.ola[:self.HOP]); 
            self.ola = np.concatenate([self.ola[self.HOP:], np.zeros(self.HOP)])
        take = min(len(x), len(self.outq))
        res = np.array(self.outq[:take]); del self.outq[:take]
        if take < len(x): res = np.concatenate([res, np.zeros(len(x)-take)])
        return res
    def _frame(self, frame):
        re = list(frame*self.win); im = [0.0]*self.N
        self.fft.forward(re, im)
        re = np.array(re); im = np.array(im)
        mag = np.sqrt(re[:self.BINS]**2 + im[:self.BINS]**2)
        # noise tracking: fast down, slow up; first ~12 frames = initial estimate
        if self.init < 12:
            self.nacc += mag; self.init += 1; self.noise = self.nacc/self.init + 1e-6
        else:
            up = mag > self.noise
            self.noise = np.where(up, self.noise*1.004 + mag*0.0, mag*0.5 + self.noise*0.5)
            self.noise = np.where(up, np.minimum(self.noise, mag), self.noise)  # never above current mag
        g = np.maximum(1.0 - self.over*self.noise/(mag + 1e-9), self.floor)
        # attack/release on gain, then 3-tap smoothing across frequency
        g = np.where(g > self.gprev, g, 0.6*self.gprev + 0.4*g)
        self.gprev = g
        gs = g.copy(); gs[1:-1] = 0.25*g[:-2] + 0.5*g[1:-1] + 0.25*g[2:]
        re[:self.BINS] *= gs; im[:self.BINS] *= gs
        # rebuild the negative-frequency half (conjugate symmetry) and inverse FFT via forward FFT trick
        for k in range(1, self.N//2):
            re[self.N-k] = re[k]; im[self.N-k] = -im[k]
        # inverse FFT: conj -> forward -> conj -> /N
        r2 = list(re); i2 = list(-im)
        self.fft.forward(r2, i2)
        y = np.array(r2)/self.N
        y *= self.win
        self.ola += y

# ---------------------------------------------------------------- Limiter (soft knee, instantaneous attack / slow release)
class Limiter:
    def __init__(self, ceiling=0.89): self.g = 1.0; self.c = ceiling
    def process(self, x):
        y = np.empty_like(x)
        rel = np.exp(-1.0/(SR*0.12))
        for i in range(len(x)):
            a = abs(x[i]); need = self.c/a if a > self.c else 1.0
            if need < self.g: self.g = need
            else: self.g = need if False else self.g*rel + (1-rel)*1.0 if self.g < 1.0 else 1.0
            y[i] = x[i]*self.g
        return y

def snr_db(clean, test):
    n = min(len(clean), len(test)); e = test[:n]-clean[:n]
    return 10*np.log10((clean[:n]**2).sum()/((e**2).sum()+1e-12))

def speech_like(secs, seed=0):
    rng = np.random.default_rng(seed)
    t = np.arange(int(SR*secs))/SR
    f0 = 130 + 25*np.sin(2*np.pi*0.6*t); ph = 2*np.pi*np.cumsum(f0)/SR
    v = sum(np.sin(h*ph)/h**0.9 for h in range(1, 20))
    syl = np.clip(np.sin(2*np.pi*2.2*t), 0, None)**0.7
    return (0.25*v*syl).astype(np.float64), rng

if __name__ == "__main__":
    # --- biquad checks
    x = np.random.default_rng(1).normal(0, 0.2, SR)
    hp = Biquad.highpass(120)
    y = hp.process(x)
    # frequency response at a few points using sine probes
    for f in (30, 60, 120, 500, 3000):
        s = np.sin(2*np.pi*f*np.arange(SR)/SR)
        h = Biquad.highpass(120); o = h.process(s)
        print(f"HP120 gain @ {f:5d} Hz = {20*np.log10(np.abs(o[SR//2:]).max()):6.1f} dB")
    pk = Biquad.peaking(3000, 3.0)
    for f in (500, 3000, 8000):
        s = np.sin(2*np.pi*f*np.arange(SR)/SR); h = Biquad.peaking(3000, 3.0); o = h.process(s)
        print(f"Peak+3dB@3k gain @ {f:5d} Hz = {20*np.log10(np.abs(o[SR//2:]).max()):6.2f} dB")
    # state continuity: block processing equals one-shot
    x = np.random.default_rng(2).normal(0, 0.3, 8192)
    a = Biquad.highpass(120).process(x)
    b = Biquad.highpass(120); bb = np.concatenate([b.process(x[i:i+1024]) for i in range(0, 8192, 1024)])
    print("block==oneshot:", np.allclose(a, bb))

    # --- denoiser
    clean, rng = speech_like(8)
    for snr_in in (20, 10, 5):
        noise = rng.normal(0, 1, len(clean)); noise *= np.sqrt((clean**2).mean()/10**(snr_in/10)/ (noise**2).mean())
        noisy = clean + noise
        dn = Denoiser(); out = []
        for i in range(0, len(noisy), 1024): out.append(dn.process(noisy[i:i+1024]))
        out = np.concatenate(out)
        lat = Denoiser.N - Denoiser.HOP
        # align: output[t] corresponds to input[t-lat]
        c = clean[:len(clean)-lat]; o = out[lat:len(clean)]
        # skip first 0.5 s (noise-profile learning)
        s0 = SR//2
        print(f"in SNR {snr_in:2d} dB -> out SNR {snr_db(c[s0:], o[s0:len(c)]):5.1f} dB  | peak {np.abs(out).max():.2f}  NaN:{np.isnan(out).any()}")
    # pure noise attenuation
    noise = rng.normal(0, 0.05, SR*4)
    dn = Denoiser(); out = np.concatenate([dn.process(noise[i:i+1024]) for i in range(0, len(noise), 1024)])
    print("white-noise attenuation after 1 s: %.1f dB" % (20*np.log10(np.sqrt((out[SR:]**2).mean())/np.sqrt((noise[SR:]**2).mean()))))
    # perfect-reconstruction check with floor=1.0 (gain forced to 1)
    sig = rng.normal(0, 0.1, SR)
    dn = Denoiser(floor_gain=1.0, over=0.0); out = np.concatenate([dn.process(sig[i:i+1024]) for i in range(0, len(sig), 1024)])
    lat = Denoiser.N - Denoiser.HOP
    print("STFT reconstruction error (gain=1):", float(np.abs(out[lat+512:len(sig)] - sig[512:len(sig)-lat]).max()))
