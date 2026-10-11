"""Auto-exposure controller simulation: original (absolute-index P controller) vs fixed (integral controller)."""
import numpy as np, math

def simulate(controller, scene_b_at_zero_ev, secs=14, tick=0.35, step_ev=1/3, lo=-12, hi=12, target=130.0, tau=0.5):
    """plant: measured brightness (0..255, gamma-encoded) follows 2^(EV/2.2) with first-order camera-AE lag"""
    t = 0.0; idx = 0; ev_eff = 0.0; hist = []; b = scene_b_at_zero_ev
    ctrl = controller(step_ev, lo, hi, target)
    while t < secs:
        # camera AE lag: effective EV approaches idx*step
        for _ in range(10):
            ev_eff += (idx*step_ev - ev_eff)*(0.035/tau)
        b_true = min(255.0, scene_b_at_zero_ev * 2**(ev_eff/2.2))
        b = 0.18*b_true + 0.82*b       # AutoExposureController's own IIR on brightness (ALPHA=0.18)
        idx = ctrl.update(b, idx)
        hist.append((t, b, idx)); t += tick
    return hist

class OriginalP:
    """the shipped algorithm: absolute index = (target-b)/28 stops, treated as raw index"""
    def __init__(self, step, lo, hi, target): self.lo=lo; self.hi=hi; self.target=target
    def update(self, b, idx):
        raw = round((self.target - b)/28.0)
        return int(min(self.hi, max(self.lo, raw)))

class FixedI:
    """integral controller in EV units with dead-band, slew-limit and index conversion by exposureCompensationStep"""
    def __init__(self, step, lo, hi, target): self.step=step; self.lo=lo; self.hi=hi; self.target=target; self.acc=0.0
    def update(self, b, idx):
        err_ev = 2.2*math.log2(self.target/max(b, 4.0))
        if abs(err_ev) < 0.25: return idx
        delta = 0.45*err_ev/self.step
        delta = max(-2.0, min(2.0, delta))
        self.acc += delta
        step_i = int(self.acc); self.acc -= step_i
        return int(min(self.hi, max(self.lo, idx + step_i)))

def report(name, scene):
    h = simulate(name, scene)
    idxs = [x[2] for x in h]; bs = [x[1] for x in h]
    tail = idxs[len(idxs)//2:]
    changes = sum(1 for a, b in zip(tail, tail[1:]) if a != b)
    print(f"  {name.__name__:9s} scene={scene:5.0f}: final idx={idxs[-1]:3d} final b={bs[-1]:6.1f}  index changes in 2nd half={changes:2d}  idx range 2nd half=[{min(tail)},{max(tail)}]")

for scene in (40, 70, 100, 200):
    print(f"scene brightness at EV0 = {scene}")
    report(OriginalP, scene); report(FixedI, scene)
