"""Final reference for audio/BeatDetector.kt (Kotlin port mirrors this)."""
import numpy as np
from collections import deque
from beat_proto import Fft, SR, N, HOP, BINS, FPS
from beat_proto2 import AdaptivePeak

class BeatDetectorRef:
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
        self.gate = 10**(-52/20)
        self.env = deque(maxlen=512)
        self.sinceTempo = 0
        self.bpm = 0.0; self.conf = 0.0; self.cand = 0.0; self.candN = 0
        self.pb = self.pm = self.ph = 1e-3
        self.bass = self.mid = self.high = 0.0
        self.rms = 0.0
        self.events = []    # (time_s, kind, strength)  kind: 'K' or 'H'
        self.frame = 0
    def process(self, samples):
        self.fifo.extend(samples)
        while len(self.fifo) >= N:
            x = self.fifo[:N]; del self.fifo[:HOP]
            self._an(x)
    def _an(self, x):
        rms = (sum(v*v for v in x)/N)**0.5; self.rms = rms
        re = [x[i]*self.win[i] for i in range(N)]; im = [0.0]*N
        self.fft.forward(re, im)
        fb = fm = fh = 0.0; eb = em = eh = 0.0
        for k in range(1, self.h1+1):
            mag = (re[k]**2 + im[k]**2)**0.5/(N/4)
            lg = np.log1p(20*mag); d = max(0.0, lg - self.prev[k]); self.prev[k] = lg
            if k < self.b0: continue
            if k <= self.b1: fb += d; eb += mag
            elif k <= self.m1: fm += d; em += mag
            else: fh += d; eh += mag
        self.pb = max(self.pb*0.9985, eb, 1e-3); self.pm = max(self.pm*0.9985, em, 1e-3); self.ph = max(self.ph*0.9985, eh, 1e-3)
        self.bass = min(1.0, eb/self.pb); self.mid = min(1.0, em/self.pm); self.high = min(1.0, eh/self.ph)
        fAll = 1.6*fb + fm + 0.6*fh
        if rms < self.gate: fb = 0.0; fAll = 0.0
        sk, fk = self.kick.push(1.6*fb)
        sh, fhh = self.hit.push(fAll)
        if sk > 0: self.events.append((fk*HOP/SR, 'K', sk))
        elif sh > 0: self.events.append((fhh*HOP/SR, 'H', sh))
        self.env.append(fAll)
        self.frame += 1; self.sinceTempo += 1
        if self.sinceTempo >= 43 and len(self.env) >= 256:
            self.sinceTempo = 0; self._tempo()
    def _tempo(self):
        e = np.array(self.env, dtype=np.float64); e -= e.mean()
        e0 = float((e*e).sum()) + 1e-9
        lagMin = int(round(60/190*FPS)); lagMax = int(round(60/65*FPS))
        n = len(e); best = 0.0; bl = 0
        for lag in range(lagMin, lagMax+1):
            r = float((e[:n-lag]*e[lag:]).sum())/e0
            bpm = 60*FPS/lag
            prior = np.exp(-0.5*(np.log2(bpm/118.0)/0.7)**2)
            if r*prior > best: best = r*prior; bl = lag
        if bl == 0: return
        est = 60*FPS/bl
        if self.bpm == 0.0: self.bpm = est
        elif abs(est-self.bpm)/self.bpm < 0.04: self.bpm += (est-self.bpm)*0.35; self.candN = 0
        else:
            if abs(est-self.cand)/max(self.cand, 1) < 0.04: self.candN += 1
            else: self.cand = est; self.candN = 1
            if self.candN >= 3: self.bpm = est; self.candN = 0
        self.conf = min(1.0, best*2)

def pattern_track(bpm, secs=24, snare=True, seed=5):
    rng = np.random.default_rng(seed)
    n = SR*secs; x = rng.normal(0, 0.01, n); per = 60/bpm
    b = 0; t = 0.4
    while t < secs-0.4:
        i = int(t*SR); m = int(0.12*SR); tt = np.arange(m)/SR
        if b % 2 == 0:       # kick on 1 and 3
            x[i:i+m] += 0.6*np.sin(2*np.pi*(55+90*np.exp(-tt*35))*tt)*np.exp(-tt*28)
        elif snare:          # snare on 2 and 4
            m2 = int(0.09*SR); tt2 = np.arange(m2)/SR
            x[i:i+m2] += 0.35*(rng.normal(0,1,m2)*0.7 + 0.3*np.sin(2*np.pi*190*tt2))*np.exp(-tt2*38)
        j = int((t+per/2)*SR); m3 = int(0.025*SR)
        x[j:j+m3] += 0.1*rng.normal(0,1,m3)*np.exp(-np.arange(m3)/(0.006*SR))
        t += per; b += 1
    return x.astype(np.float32)

if __name__ == "__main__":
    for bpm in (75, 90, 100, 110, 124, 140, 150):
        x = pattern_track(bpm)
        d = BeatDetectorRef()
        for i in range(0, len(x), 1024): d.process(list(x[i:i+1024]))
        ratio = d.bpm/bpm
        tag = "OK" if abs(ratio-1) < 0.04 else ("half" if abs(ratio-0.5) < 0.04 else ("double" if abs(ratio-2) < 0.08 else "??"))
        print(f"true {bpm:4d} -> est {d.bpm:6.1f}  conf={d.conf:.2f}  [{tag}]  events={len(d.events)}")
