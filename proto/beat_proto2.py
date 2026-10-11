"""Second iteration: separate KICK (bass) and HIT (broadband) detectors, stricter thresholds, level gate."""
import numpy as np
from collections import deque
from beat_proto import Fft, SR, N, HOP, BINS, FPS, make_track

class AdaptivePeak:
    """causal peak picker with one-frame delay, adaptive threshold from the last `hist_len` values"""
    def __init__(self, hist_len, k_std, floor, refractory_s):
        self.hist = deque(maxlen=hist_len); self.k = k_std; self.floor = floor
        self.ref = int(refractory_s*FPS); self.v1 = 0.0; self.v2 = 0.0
        self.last = -10_000; self.t = 0
    def push(self, v):
        """returns (strength, frame_index_of_peak) or (0, -1)"""
        res = (0.0, -1)
        if len(self.hist) >= 8:
            m = sum(self.hist)/len(self.hist)
            sd = (sum((a-m)**2 for a in self.hist)/len(self.hist))**0.5
            thr = m + self.k*sd + self.floor
            if self.v1 > thr and self.v1 >= self.v2 and self.v1 >= v and self.t - self.last > self.ref:
                s = min(1.0, 0.4 + 1.2*(self.v1 - thr)/(self.v1 + 1e-6))
                res = (s, self.t - 1); self.last = self.t - 1
        self.hist.append(v); self.v2 = self.v1; self.v1 = v; self.t += 1
        return res

class Det2:
    def __init__(self):
        self.fft = Fft(N)
        self.win = [0.5 - 0.5*np.cos(2*np.pi*i/N) for i in range(N)]
        self.fifo = []
        self.prev = [0.0]*BINS
        hz = SR/N
        self.b0 = max(1, int(np.ceil(30/hz))); self.b1 = int(150/hz)
        self.m1 = int(1000/hz); self.h1 = min(BINS-1, int(8000/hz))
        self.kick = AdaptivePeak(43, 1.5, 0.35, 0.18)
        self.hit  = AdaptivePeak(43, 1.8, 0.45, 0.10)
        self.kicks = []; self.hits = []
        self.frame = 0
        self.gateRms = 10**(-52/20)
    def process(self, samples):
        self.fifo.extend(samples)
        while len(self.fifo) >= N:
            x = self.fifo[:N]; del self.fifo[:HOP]
            self._an(x)
    def _an(self, x):
        rms = (sum(v*v for v in x)/N)**0.5
        re = [x[i]*self.win[i] for i in range(N)]; im = [0.0]*N
        self.fft.forward(re, im)
        fb = fm = fh = 0.0
        for k in range(1, self.h1+1):
            mag = (re[k]**2 + im[k]**2)**0.5/(N/4)
            lg = np.log1p(20*mag)
            d = max(0.0, lg - self.prev[k]); self.prev[k] = lg
            if k < self.b0: continue
            if k <= self.b1: fb += d
            elif k <= self.m1: fm += d
            else: fh += d
        fAll = 1.6*fb + fm + 0.6*fh
        if rms < self.gateRms: fb = 0.0; fAll = 0.0     # below gate: no onsets
        sk, fk = self.kick.push(1.6*fb)
        sh, fh_ = self.hit.push(fAll)
        if sk > 0: self.kicks.append((fk, sk))
        if sh > 0 and (sk == 0): self.hits.append((fh_, sh))
        self.frame += 1

def score(det_times, truth, tol=0.09):
    tp = 0; used = set()
    for t in truth:
        for j, dt in enumerate(det_times):
            if j in used: continue
            if abs(dt-t) < tol: tp += 1; used.add(j); break
    return tp/max(1,len(det_times)), tp/max(1,len(truth))

if __name__ == "__main__":
    for b in (90, 100, 120, 128, 140, 160):
        x, truth = make_track(b)
        d = Det2()
        for i in range(0, len(x), 1024): d.process(list(x[i:i+1024]))
        kt = [f*HOP/SR for f, s in d.kicks]
        p, r = score(kt, truth)
        print(f"bpm={b:4d} kicks det={len(kt):3d}/{len(truth):3d}  P={p:.2f} R={r:.2f}   other hits={len(d.hits)}")
    # no-hat case: kick only
    x, truth = make_track(120, hat=False)
    d = Det2()
    for i in range(0, len(x), 1024): d.process(list(x[i:i+1024]))
    p, r = score([f*HOP/SR for f, s in d.kicks], truth); print("kick-only 120:", f"P={p:.2f} R={r:.2f}")
    # noisy room: add pink-ish noise at -30 dBFS rms
    x, truth = make_track(120, noise=0.03)
    d = Det2()
    for i in range(0, len(x), 1024): d.process(list(x[i:i+1024]))
    p, r = score([f*HOP/SR for f, s in d.kicks], truth); print("noisy room 120:", f"P={p:.2f} R={r:.2f}")
    rng = np.random.default_rng(3)
    for amp in (0.003, 0.02, 0.05):
        x = rng.normal(0, amp, SR*10).astype(np.float32)
        d = Det2()
        for i in range(0, len(x), 1024): d.process(list(x[i:i+1024]))
        print(f"white noise rms={amp}: kicks={len(d.kicks)} hits={len(d.hits)} in 10 s")
    # speech-like
    t = np.arange(SR*10)/SR
    f0 = 120 + 20*np.sin(2*np.pi*0.7*t); ph = 2*np.pi*np.cumsum(f0)/SR
    voice = sum(np.sin(h*ph)/h for h in range(1, 12)); am = 0.5 + 0.5*np.sin(2*np.pi*3.5*t)**2
    x = (0.15*voice*am + rng.normal(0, 0.01, len(t))).astype(np.float32)
    d = Det2()
    for i in range(0, len(x), 1024): d.process(list(x[i:i+1024]))
    print("speech-like: kicks=", len(d.kicks), "hits=", len(d.hits))
