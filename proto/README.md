# Reference prototypes (Python)

The DSP / geometry used by the app was prototyped and measured here first (numpy + scipy), then ported 1:1 to Kotlin.
The JVM unit tests in `app/src/test` mirror these scripts.

| Script | What it measures |
|---|---|
| `beat_proto.py`, `beat_proto2.py`, `beat_final.py` | FFT port check, onset detector, kick/hit split, tempo (`python3 beat_final.py`) |
| `denoise_final.py`, `dsp_proto.py` | spectral denoiser SNR gain, biquad responses, STFT reconstruction |
| `ae_proto.py`, `ae_proto2.py` | exposure controller: original vs fixed (hunting / steady-state error) |
| `geom_proto.py` | camera buffer → upright frame → centre-crop / mirror / tap mapping |
