"""Verify camera-buffer -> upright FBO -> screen geometry (rotation, centre-crop, mirror, tap mapping)."""
import numpy as np

def look_mvp(bw, bh, rot_cw_deg):
    """matrix (3x3 homogeneous 2D) mapping quad coords (a,b) in [-1,1]^2 to NDC, for a bw x bh buffer rotated CW by rot."""
    rot = rot_cw_deg % 360
    dw, dh = (bh, bw) if rot in (90, 270) else (bw, bh)
    phi = np.deg2rad(-rot)                  # GL rotation angle is CCW-positive; CW rotation = negative angle
    c, s = np.cos(phi), np.sin(phi)
    R = np.array([[c, -s, 0], [s, c, 0], [0, 0, 1]])
    Sbuf = np.diag([bw/2, bh/2, 1])
    Sndc = np.diag([2/dw, 2/dh, 1])
    return Sndc @ R @ Sbuf, (dw, dh)

def render_look(buf, rot):
    bh, bw = buf.shape
    M, (dw, dh) = look_mvp(bw, bh, rot)
    Minv = np.linalg.inv(M)
    out = np.zeros((dh, dw))
    for r in range(dh):
        for cx in range(dw):
            x = (cx + 0.5)/dw*2 - 1; y = 1 - (r + 0.5)/dh*2
            a, b, _ = Minv @ np.array([x, y, 1])
            u, v = (a + 1)/2, (b + 1)/2
            ty = 1 - v                      # SurfaceTexture transform flips Y: buffer row 0 = top
            sx = int(np.floor(u*bw)); sy = int(np.floor(ty*bh))
            out[r, cx] = buf[min(max(sy, 0), bh-1), min(max(sx, 0), bw-1)]
    return out

def display_scale(content_w, content_h, view_w, view_h):
    ca = content_w/content_h; va = view_w/view_h
    if va > ca: return 1.0, va/ca            # view wider: content scaled to fill width, crops top/bottom
    return ca/va, 1.0                         # view taller: scale to fill height, crops left/right

def render_display(content, view_w, view_h, mirror):
    ch, cw = content.shape
    sx, sy = display_scale(cw, ch, view_w, view_h)
    out = np.zeros((view_h, view_w))
    for r in range(view_h):
        for c in range(view_w):
            x = (c + 0.5)/view_w*2 - 1; y = 1 - (r + 0.5)/view_h*2
            a = x/sx; b = y/sy
            if mirror: a = -a
            u, v = (a + 1)/2, (b + 1)/2
            if not (0 <= u <= 1 and 0 <= v <= 1): out[r, c] = -1; continue
            # FBO texture: v=0 is bottom row => row index from top = (1-v)*ch
            out[r, c] = content[min(int((1-v)*ch), ch-1), min(int(u*cw), cw-1)]
    return out

def view_to_content(vx, vy, content_w, content_h, view_w, view_h, mirror):
    """view-normalised tap (0..1, origin top-left) -> content-normalised (0..1, origin top-left) in the *unmirrored* frame"""
    sx, sy = display_scale(content_w, content_h, view_w, view_h)
    cx = 0.5 + (vx - 0.5)/sx
    cy = 0.5 + (vy - 0.5)/sy
    if mirror: cx = 1 - cx
    return cx, cy

if __name__ == "__main__":
    bh, bw = 18, 32                                     # 16:9 landscape camera buffer
    buf = np.arange(bh*bw).reshape(bh, bw).astype(float)
    for rot in (0, 90, 180, 270):
        out = render_look(buf, rot)
        expect = np.rot90(buf, k=-(rot//90))             # k<0 => clockwise
        print(f"rotation {rot:3d} CW: shape {out.shape}  matches np.rot90 clockwise: {np.array_equal(out, expect)}")
    # display crop: content 18x32 portrait (9:16), view 20x45 (9:20), 8 x 16 etc.
    content = np.arange(32*18).reshape(32, 18).astype(float)
    for (vw, vh) in ((18, 32), (18, 40), (24, 32), (36, 36)):
        d = render_display(content, vw, vh, mirror=False)
        # centre pixel must show centre of content; top-left of view must be inside content
        print(f"view {vw}x{vh}: centre ok={d[vh//2, vw//2] in (content[16, 8], content[16, 9], content[15, 8], content[15, 9])} none outside={not (d == -1).any()}")
    # mirror: left-right flip of displayed image
    d0 = render_display(content, 18, 32, False); d1 = render_display(content, 18, 32, True)
    print("mirror flips horizontally:", np.array_equal(d0[:, ::-1], d1))
    # tap mapping check: a tap on the view pixel must hit the content pixel that is displayed there
    ok = True
    for (vw, vh, mir) in ((18, 40, False), (18, 40, True), (36, 36, False), (24, 32, True)):
        d = render_display(content, vw, vh, mir)
        for (px, py) in ((3, 4), (vw//2, vh//2), (vw-3, vh-5)):
            cx, cy = view_to_content((px + 0.5)/vw, (py + 0.5)/vh, 18, 32, vw, vh, mir)
            ix, iy = int(cx*18), int(cy*32)
            ix = min(max(ix, 0), 17); iy = min(max(iy, 0), 31)
            if abs(content[iy, ix] - d[py, px]) > 0.5:
                # allow neighbour rounding
                near = {content[min(max(iy+j, 0), 31), min(max(ix+i, 0), 17)] for i in (-1, 0, 1) for j in (-1, 0, 1)}
                if d[py, px] not in near: ok = False; print("  mismatch", vw, vh, mir, px, py, d[py, px], content[iy, ix])
    print("tap mapping consistent with what is displayed:", ok)
