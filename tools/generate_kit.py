#!/usr/bin/env python3
"""Generates the bundled drum kits from scratch (pure DSP synthesis, no third-party audio).
Output: 16-bit mono 44.1 kHz WAV files in app/src/main/assets/kits/<kit>/<instrument>.wav
License of the generated files: CC0 1.0 (public domain) - see assets/kits/LICENSE.txt
Run: python3 tools/generate_kit.py   (needs numpy)"""
import os, wave
import numpy as np

SR = 44100
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "assets", "kits")
rng = np.random.default_rng(20261108)

def t(sec): return np.arange(int(SR * sec)) / SR
def env(x, decay): return np.exp(-x / decay)
def noise(n): return rng.uniform(-1, 1, n)
def biquad(x, kind, f, q=0.8):
    w = 2 * np.pi * f / SR; a = np.sin(w) / (2 * q); c = np.cos(w)
    if kind == "hp": b = [(1 + c) / 2, -(1 + c), (1 + c) / 2]
    elif kind == "lp": b = [(1 - c) / 2, 1 - c, (1 - c) / 2]
    else: b = [a, 0, -a]  # bandpass
    a0 = 1 + a; A = [1, -2 * c / a0, (1 - a) / a0]; B = [v / a0 for v in b]
    y = np.zeros_like(x); x1 = x2 = y1 = y2 = 0.0
    for i, v in enumerate(x):
        o = B[0] * v + B[1] * x1 + B[2] * x2 - A[1] * y1 - A[2] * y2
        x2, x1, y2, y1 = x1, v, y1, o; y[i] = o
    return y
def sweep(x, f0, f1, k):
    f = f1 + (f0 - f1) * np.exp(-x / k); return np.sin(2 * np.pi * np.cumsum(f) / SR)
def fade_end(y, ms=8):
    n = int(SR * ms / 1000); y[-n:] *= np.linspace(1, 0, n); return y
def norm(y, peak=0.89): return y / np.max(np.abs(y)) * peak
def save(kit, name, y):
    d = os.path.join(OUT, kit); os.makedirs(d, exist_ok=True)
    pcm = (np.clip(norm(fade_end(y)), -1, 1) * 32767).astype("<i2")
    with wave.open(os.path.join(d, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())

def kit(name, p):
    x = t(0.6); save(name, "kick", sweep(x, p["kick_hi"], p["kick_lo"], 0.03) * env(x, p["kick_d"]) + 0.3 * biquad(noise(len(x)), "lp", 3000) * env(x, 0.004))
    x = t(0.35); save(name, "snare", p["snare_tone"] * sweep(x, 260, 180, 0.04) * env(x, 0.07) + biquad(noise(len(x)), "hp", 1800) * env(x, p["snare_d"]))
    x = t(0.09); save(name, "closed_hat", biquad(noise(len(x)), "hp", 7000) * env(x, 0.016))
    x = t(0.55); save(name, "open_hat", biquad(noise(len(x)), "hp", 6500) * env(x, 0.12))
    x = t(1.8); save(name, "crash", (biquad(noise(len(x)), "hp", 4500) + 0.4 * biquad(noise(len(x)), "bp", 8000, 2)) * env(x, p["crash_d"]))
    x = t(1.2); ring = sum(np.sin(2 * np.pi * f * x + i) for i, f in enumerate([p["ride_f"], p["ride_f"] * 1.52, p["ride_f"] * 2.31, p["ride_f"] * 3.07]))
    save(name, "ride", 0.15 * ring * env(x, 0.35) + 0.5 * biquad(noise(len(x)), "hp", 7500) * env(x, 0.25))
    for nm, f in (("tom_high", 190), ("tom_mid", 140), ("tom_floor", 95)):
        x = t(0.55); f *= p["tom_k"]; save(name, nm, sweep(x, f * 1.5, f, 0.025) * env(x, 0.16) + 0.2 * biquad(noise(len(x)), "lp", 2500) * env(x, 0.01))
    x = t(0.3); c = sum(biquad(noise(len(x)), "bp", 1200, 1.5) * env(np.maximum(x - i * 0.011, 0), 0.01) * (x >= i * 0.011) for i in range(3))
    save(name, "clap", c + 0.6 * biquad(noise(len(x)), "bp", 1500, 1.2) * env(np.maximum(x - 0.033, 0), 0.07) * (x >= 0.033))
    x = t(0.18); save(name, "rimshot", 0.6 * np.sin(2 * np.pi * 480 * x) * env(x, 0.012) + 0.5 * np.sin(2 * np.pi * 1650 * x) * env(x, 0.01) + 0.5 * biquad(noise(len(x)), "bp", 3500, 1) * env(x, 0.008))
    x = t(0.4); save(name, "percussion", np.sin(2 * np.pi * p["perc_f"] * x + 3 * np.sin(2 * np.pi * p["perc_f"] * 1.41 * x) * env(x, 0.05)) * env(x, 0.12))

kit("studio_synth", dict(kick_hi=140, kick_lo=48, kick_d=0.22, snare_tone=0.8, snare_d=0.09, crash_d=0.55, ride_f=620, tom_k=1.0, perc_f=720))
kit("dry_punch",    dict(kick_hi=110, kick_lo=58, kick_d=0.11, snare_tone=1.1, snare_d=0.045, crash_d=0.35, ride_f=760, tom_k=1.25, perc_f=980))
