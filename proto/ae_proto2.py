import math, itertools
from ae_proto import simulate, OriginalP

class FixedI2:
    def __init__(self, step, lo, hi, target, gain=0.25, dead=0.30, slew=1, max_ev=2.0):
        self.step=step; self.lo=max(lo, int(-max_ev/step)); self.hi=min(hi, int(max_ev/step)); self.target=target
        self.gain=gain; self.dead=dead; self.slew=slew; self.acc=0.0
    def update(self, b, idx):
        err_ev = 2.2*math.log2(self.target/max(b, 4.0))
        if abs(err_ev) < self.dead: self.acc = 0.0; return idx
        self.acc += self.gain*err_ev/self.step
        s = int(self.acc)
        if s == 0: return idx
        self.acc -= s
        s = max(-self.slew, min(self.slew, s))
        return int(min(self.hi, max(self.lo, idx + s)))

def run(gain, dead, slew):
    rows = []
    for scene in (30, 50, 70, 100, 150, 200, 240):
        mk = lambda step, lo, hi, tgt: FixedI2(step, lo, hi, tgt, gain, dead, slew)
        h = simulate(mk, scene, secs=20)
        idxs = [x[2] for x in h]; bs = [x[1] for x in h]
        tail = idxs[len(idxs)//2:]
        ch = sum(1 for a, b in zip(tail, tail[1:]) if a != b)
        rows.append((scene, idxs[-1], bs[-1], ch))
    return rows

best = None
for gain, dead, slew in itertools.product((0.15, 0.25, 0.35), (0.25, 0.35), (1, 2)):
    rows = run(gain, dead, slew)
    hunting = sum(r[3] for r in rows)
    print(f"gain={gain:.2f} dead={dead:.2f} slew={slew}  total index changes (2nd half, 7 scenes)={hunting:2d}   final b: " + " ".join(f"{r[2]:5.0f}" for r in rows))
