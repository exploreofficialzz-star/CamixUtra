"""
Prototype of the CamixUltra beat/onset detector.
The Kotlin port (audio/BeatDetector.kt, audio/Fft.kt) mirrors this file line by line.
"""
import numpy as np
from collections import deque

SR   = 44100
N    = 1024
HOP  = 512
BINS = N // 2 + 1
FPS  = SR / HOP

# ------------------------------------------------------------------ FFT (iterative radix-2, in place)
class Fft:
    def __init__(self, n):
        self.n = n
        self.levels = int(np.log2(n))
        assert 1 << self.levels == n
        self.cos = [np.cos(2*np.pi*i/n) for i in range(n//2)]
        self.sin = [np.sin(2*np.pi*i/n) for i in range(n//2)]
        self.rev = [0]*n
        for i in range(n):
            r = 0; x = i
            for _ in range(self.levels):
                r = (r << 1) | (x & 1); x >>= 1
            self.rev[i] = r
    def forward(self, re, im):
        n = self.n
        for i in range(n):
            j = self.rev[i]
            if j > i:
                re[i], re[j] = re[j], re[i]
                im[i], im[j] = im[j], im[i]
        size = 2
        while size <= n:
            half = size // 2
            step = n // size
            for i in range(0, n, size):
                k = 0
                for j in range(i, i+half):
                    l = j + half
                    tpre =  re[l]*self.cos[k] + im[l]*self.sin[k]
                    tpim = -re[l]*self.sin[k] + im[l]*self.cos[k]
                    re[l] = re[j] - tpre
                    im[l] = im[j] - tpim
                    re[j] += tpre
                    im[j] += tpim
                    k += step
            size *= 2

def test_fft():
    f = Fft(1024)
    x = np.random.randn(1024)
    re = list(x); im = [0.0]*1024
    f.forward(re, im)
    ref = np.fft.fft(x)
    err = max(abs(np.array(re)-ref.real).max(), abs(np.array(im)-ref.imag).max())
    print("FFT max err vs numpy:", err)
    assert err < 1e-8

# ------------------------------------------------------------------ Detector
class BeatDetector:
    BASS_LO, BASS_HI = 30.0, 150.0
    MID_HI  = 1000.0
    HIGH_HI = 8000.0
    def __init__(self):
        self.fft = Fft(N)
        self.win = [0.5 - 0.5*np.cos(2*np.pi*i/N) for i in range(N)]
        self.fifo = []                       # python list of floats
        self.prevLog = [0.0]*BINS
        self.hist = deque(maxlen=43)          # ~0.5 s of flux values
        self.flux1 = 0.0; self.flux2 = 0.0   # flux at t-1, t-2
        self.lastOnsetFrame = -1000
        self.frame = 0
        self.env = deque(maxlen=512)          # ~5.9 s onset envelope
        self.bpm = 0.0; self.conf = 0.0
        self.cand = 0.0; self.candCount = 0
        self.peakBass = 1e-3; self.peakMid = 1e-3; self.peakHigh = 1e-3
        self.bass = 0.0; self.mid = 0.0; self.high = 0.0
        self.sinceTempo = 0
        # bin ranges
        hz = SR / N
        self.b0 = max(1, int(np.ceil(self.BASS_LO / hz)))
        self.b1 = int(self.BASS_HI / hz)
        self.m1 = int(self.MID_HI / hz)
        self.h1 = min(BINS-1, int(self.HIGH_HI / hz))
        self.events = []   # (frame, strength)

    def process(self, samples):
        """feed float samples; returns list of (strength) for each onset detected in this call"""
        self.fifo.extend(samples)
        out = []
        while len(self.fifo) >= N:
            frame = self.fifo[:N]
            del self.fifo[:HOP]
            s = self._analyze(frame)
            if s > 0: out.append(s)
        return out

    def _analyze(self, x):
        re = [x[i]*self.win[i] for i in range(N)]
        im = [0.0]*N
        self.fft.forward(re, im)
        fb = fm = fh = 0.0
        eb = em = eh = 0.0
        for k in range(1, self.h1+1):
            mag = np.sqrt(re[k]*re[k] + im[k]*im[k]) / (N/4)    # ~1.0 for full-scale sine
            lg = np.log1p(20.0*mag)
            d = lg - self.prevLog[k]
            if d < 0: d = 0.0
            self.prevLog[k] = lg
            if k < self.b0:
                pass
            elif k <= self.b1:  fb += d; eb += mag
            elif k <= self.m1:  fm += d; em += mag
            else:               fh += d; eh += mag
        flux = 1.6*fb + 1.0*fm + 0.6*fh
        # band energies (auto-gain-normalised 0..1)
        self.peakBass = max(self.peakBass*0.9985, eb, 1e-3)
        self.peakMid  = max(self.peakMid *0.9985, em, 1e-3)
        self.peakHigh = max(self.peakHigh*0.9985, eh, 1e-3)
        self.bass = min(1.0, eb/self.peakBass); self.mid = min(1.0, em/self.peakMid); self.high = min(1.0, eh/self.peakHigh)

        # peak picking with one-frame delay: candidate is flux1 (previous frame)
        strength = 0.0
        if len(self.hist) >= 8:
            mean = sum(self.hist)/len(self.hist)
            var = sum((v-mean)**2 for v in self.hist)/len(self.hist)
            thr = mean + 1.3*np.sqrt(var) + 0.25
        else:
            thr = 1e9
        refractory = int(0.14*FPS)
        if (self.flux1 > thr and self.flux1 >= self.flux2 and self.flux1 >= flux
                and self.frame - self.lastOnsetFrame > refractory):
            strength = min(1.0, 0.35 + (self.flux1 - thr)/(self.flux1 + 1e-6)*1.2)
            self.lastOnsetFrame = self.frame - 1
            self.events.append((self.frame-1, strength))
        self.hist.append(flux)
        self.flux2 = self.flux1; self.flux1 = flux
        self.env.append(flux)
        self.frame += 1
        self.sinceTempo += 1
        if self.sinceTempo >= 43 and len(self.env) >= 256:   # every ~0.5 s
            self.sinceTempo = 0
            self._tempo()
        return strength

    def _tempo(self):
        e = np.array(self.env)
        e = e - e.mean()
        e0 = float((e*e).sum()) + 1e-9
        lagMin = int(round(60.0/190.0*FPS)); lagMax = int(round(60.0/65.0*FPS))
        best = 0.0; bestLag = 0
        n = len(e)
        for lag in range(lagMin, lagMax+1):
            r = float((e[:n-lag]*e[lag:]).sum()) / e0
            bpm = 60.0*FPS/lag
            # log-normal prior around 118 BPM
            prior = np.exp(-0.5*(np.log2(bpm/118.0)/0.7)**2)
            sc = r*prior
            if sc > best: best = sc; bestLag = lag
        if bestLag == 0: return
        # parabolic interpolation of the lag
        est = 60.0*FPS/bestLag
        conf = min(1.0, best*2.0)
        if self.bpm == 0.0:
            self.bpm = est
        elif abs(est-self.bpm)/self.bpm < 0.04:
            self.bpm += (est-self.bpm)*0.35
            self.candCount = 0
        else:
            if abs(est-self.cand)/max(self.cand,1) < 0.04: self.candCount += 1
            else: self.cand = est; self.candCount = 1
            if self.candCount >= 3: self.bpm = est; self.candCount = 0
        self.conf = conf

# ------------------------------------------------------------------ synthetic music
def make_track(bpm=120, secs=20, kick=True, hat=True, noise=0.01, seed=1):
    rng = np.random.default_rng(seed)
    n = int(SR*secs)
    x = rng.normal(0, noise, n)
    period = 60.0/bpm
    truth = []
    t = 0.5
    while t < secs-0.3:
        i = int(t*SR)
        if kick:
            m = int(0.12*SR)
            tt = np.arange(m)/SR
            k = 0.6*np.sin(2*np.pi*(55 + 90*np.exp(-tt*35))*tt)*np.exp(-tt*28)
            x[i:i+m] += k[:n-i]
            truth.append(t)
        if hat:
            # off-beat hats
            j = int((t+period/2)*SR)
            m = int(0.03*SR)
            if j+m < n:
                x[j:j+m] += 0.12*rng.normal(0,1,m)*np.exp(-np.arange(m)/(0.008*SR))
        t += period
    return x.astype(np.float32), truth

def evaluate(bpm, **kw):
    x, truth = make_track(bpm, **kw)
    d = BeatDetector()
    for i in range(0, len(x), 1024):
        d.process(list(x[i:i+1024]))
    det = [f*HOP/SR for f, s in d.events]
    tp = 0; used = set()
    for t in truth:
        for j, dt in enumerate(det):
            if j in used: continue
            if abs(dt - t) < 0.09:   # onsets are detected up to ~one hop early/late
                tp += 1; used.add(j); break
    prec = tp/max(1,len(det)); rec = tp/max(1,len(truth))
    print(f"bpm={bpm:5.0f}  truth={len(truth):3d} det={len(det):3d}  precision={prec:.2f} recall={rec:.2f}  est_bpm={d.bpm:6.1f} conf={d.conf:.2f}")
    return prec, rec, d.bpm

if __name__ == "__main__":
    test_fft()
    for b in (90, 100, 120, 128, 140, 160):
        evaluate(b)
    print("-- noise only (false positive check)")
    rng = np.random.default_rng(3)
    x = (rng.normal(0, 0.05, SR*10)).astype(np.float32)
    d = BeatDetector()
    for i in range(0, len(x), 1024): d.process(list(x[i:i+1024]))
    print("onsets in 10 s of white noise:", len(d.events))
    print("-- speech-like (amplitude modulated harmonic) false positive check")
    t = np.arange(SR*10)/SR
    f0 = 120 + 20*np.sin(2*np.pi*0.7*t)
    ph = 2*np.pi*np.cumsum(f0)/SR
    voice = sum(np.sin(h*ph)/h for h in range(1, 12))
    am = 0.5 + 0.5*np.sin(2*np.pi*3.5*t)**2
    x = (0.15*voice*am + rng.normal(0, 0.01, len(t))).astype(np.float32)
    d = BeatDetector()
    for i in range(0, len(x), 1024): d.process(list(x[i:i+1024]))
    print("onsets in 10 s of syllabic speech-like signal:", len(d.events), "(syllables at 3.5 Hz*2 = ~70)")
