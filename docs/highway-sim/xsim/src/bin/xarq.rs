// xarq: end-to-end XARA ARQ sessions (two full stations) through a streaming
// Watterson channel, plus an offline mode to validate that channel against
// Mercury's watterson_test.
//
//   xarq chan PRESET SEED IN.f32 OUT.f32
//       IN/OUT 48 kHz f32; fading only, no noise (validation against run.py).
//   xarq arq PRESET NOISE_SIGMA SEED BYTES MAX_SECS
//       A connects to B and sends BYTES random bytes. Prints one JSON line:
//       in-burst SNR (A's transmitted power while keyed, over noise in 2500 Hz),
//       link-up time, completion time, bytes delivered, top level reached.
//
// The channel follows Mercury's docs/CHANNEL-MODEL.md: two equal paths, each a
// complex Gaussian tap with a Gaussian Doppler spectrum (spread = 2 sigma),
// generated at a low rate by FIR-filtering white noise and linearly
// interpolated; applied to the analytic signal at 8 kHz; real part out.
use std::collections::VecDeque;
use std::f64::consts::PI;
use std::io::{Read, Write};
use xara::station::hf::Station;
use xara::station::{Modem, Options, Report};
use xara_arq::compression::Compression;
use xara_arq::hf::session::Config;
use xara_arq::host::HostEvent;
use xara_phy::hf::level::Bandwidth;

type C = num_complex::Complex64;

struct Rng(u64);
impl Rng {
    fn new(s: u64) -> Self {
        Self(s.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1)
    }
    fn u(&mut self) -> f64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        (self.0.wrapping_mul(0x2545_F491_4F6C_DD1D) >> 11) as f64 / (1u64 << 53) as f64
    }
    fn g(&mut self) -> f64 {
        let (a, b) = (self.u().max(1e-300), self.u());
        (-2.0 * a.ln()).sqrt() * (2.0 * PI * b).cos()
    }
}

fn blackman(n: usize, i: usize) -> f64 {
    let x = i as f64 / (n - 1) as f64;
    0.42 - 0.5 * (2.0 * PI * x).cos() + 0.08 * (4.0 * PI * x).cos()
}

/// Low-pass FIR for 48k <-> 8k (cutoff 3.45 kHz).
fn lowpass() -> Vec<f64> {
    let n = 289;
    let fc = 3450.0 / 48000.0;
    let m = (n - 1) as f64 / 2.0;
    let mut h: Vec<f64> = (0..n)
        .map(|i| {
            let t = i as f64 - m;
            let s = if t == 0.0 { 2.0 * fc } else { (2.0 * PI * fc * t).sin() / (PI * t) };
            s * blackman(n, i)
        })
        .collect();
    let g: f64 = h.iter().sum();
    h.iter_mut().for_each(|v| *v /= g);
    h
}

/// Hilbert transformer at 8 kHz (odd length, delay (n-1)/2).
fn hilbert() -> Vec<f64> {
    let n = 127;
    let m = (n - 1) / 2;
    (0..n)
        .map(|i| {
            let k = i as i64 - m as i64;
            if k % 2 == 0 { 0.0 } else { 2.0 / (PI * k as f64) * blackman(n, i) }
        })
        .collect()
}

struct Tap {
    rng: Rng,
    fir: Vec<f64>,
    hist: VecDeque<C>,
    r: usize,
    phase: usize,
    a: C,
    b: C,
}

impl Tap {
    fn new(spread: f64, seed: u64) -> Self {
        let fs = 8000.0;
        let r = ((fs / (64.0 * spread)).floor() as usize).max(1);
        let fl = fs / r as f64;
        let sig = spread / 2.0;
        let mut fir = Vec::new();
        let mut k = 0i64;
        loop {
            let t = k as f64 / fl;
            let v = (-4.0 * PI * PI * sig * sig * t * t).exp();
            if v < 1e-4 {
                break;
            }
            fir.push(v);
            k += 1;
        }
        let half = fir.clone();
        let mut full: Vec<f64> = half.iter().rev().cloned().collect();
        full.extend(&half[1..]);
        let e: f64 = full.iter().map(|v| v * v).sum();
        full.iter_mut().for_each(|v| *v /= e.sqrt());
        let mut rng = Rng::new(seed);
        let hist: VecDeque<C> =
            (0..full.len()).map(|_| C::new(rng.g(), rng.g()) / 2f64.sqrt()).collect();
        let mut t = Self { rng, fir: full, hist, r, phase: 0, a: C::default(), b: C::default() };
        t.a = t.next_low();
        t.b = t.next_low();
        t
    }
    fn next_low(&mut self) -> C {
        self.hist.pop_front();
        self.hist.push_back(C::new(self.rng.g(), self.rng.g()) / 2f64.sqrt());
        self.hist.iter().zip(&self.fir).map(|(x, h)| x * h).sum()
    }
    fn next(&mut self) -> C {
        let f = self.phase as f64 / self.r as f64;
        let v = self.a * (1.0 - f) + self.b * f;
        self.phase += 1;
        if self.phase == self.r {
            self.phase = 0;
            self.a = self.b;
            self.b = self.next_low();
        }
        v
    }
}

/// One direction: 48k real in -> 8k -> analytic -> 2-path fading -> real -> 48k out.
struct Channel {
    lp: Vec<f64>,
    ht: Vec<f64>,
    down: VecDeque<f64>,
    dphase: usize,
    hbuf: VecDeque<f64>,
    line: VecDeque<C>,
    delay: usize,
    taps: Option<(Tap, Tap)>,
    up: VecDeque<f64>,
}

impl Channel {
    fn new(preset: &str, seed: u64) -> Self {
        let (delay_ms, spread) = match preset {
            "awgn" => (0.0, 0.0),
            "ccir-good" => (0.5, 0.1),
            "ccir-moderate" => (1.0, 0.5),
            "ccir-poor" => (2.0, 1.0),
            "itur-mdv" => (7.0, 1.0),
            _ => panic!("preset"),
        };
        let lp = lowpass();
        let ht = hilbert();
        let delay = (delay_ms * 8.0f64).ceil() as usize;
        Self {
            down: VecDeque::from(vec![0.0; lp.len()]),
            hbuf: VecDeque::from(vec![0.0; ht.len()]),
            up: VecDeque::from(vec![0.0; lp.len()]),
            line: VecDeque::from(vec![C::default(); delay + 1]),
            taps: (spread > 0.0).then(|| (Tap::new(spread, seed), Tap::new(spread, seed ^ 0x5555))),
            lp,
            ht,
            dphase: 0,
            delay,
        }
    }

    fn fade8(&mut self, x: f64) -> f64 {
        self.hbuf.pop_front();
        self.hbuf.push_back(x);
        let im: f64 = self.hbuf.iter().zip(&self.ht).map(|(a, h)| a * h).sum();
        let re = self.hbuf[self.ht.len() / 2];
        let z = C::new(re, im);
        self.line.pop_back();
        self.line.push_front(z);
        match &mut self.taps {
            None => z.re,
            Some((t1, t2)) => {
                let g = std::f64::consts::FRAC_1_SQRT_2;
                (t1.next() * self.line[0] * g + t2.next() * self.line[self.delay] * g).re
            }
        }
    }

    fn process(&mut self, x: &[f64]) -> Vec<f64> {
        let mut out = Vec::with_capacity(x.len());
        for &v in x {
            self.down.pop_front();
            self.down.push_back(v);
            // decimate by 6 (filter only on kept samples), fade at 8 kHz, zero-stuff back to 48 kHz
            let z = if self.dphase == 0 {
                let d: f64 = self.down.iter().zip(&self.lp).map(|(a, h)| a * h).sum();
                6.0 * self.fade8(d)
            } else {
                0.0
            };
            self.up.pop_front();
            self.up.push_back(z);
            out.push(self.up.iter().zip(&self.lp).map(|(a, h)| a * h).sum());
            self.dphase = (self.dphase + 1) % 6;
        }
        out
    }
}

fn read_f32(p: &str) -> Vec<f64> {
    let mut b = Vec::new();
    std::fs::File::open(p).unwrap().read_to_end(&mut b).unwrap();
    b.chunks_exact(4).map(|c| f64::from(f32::from_le_bytes(c.try_into().unwrap()))).collect()
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    match a[1].as_str() {
        "chan" => {
            let mut ch = Channel::new(&a[2], a[3].parse().unwrap());
            let x = read_f32(&a[4]);
            let y: Vec<f64> = x.chunks(480).flat_map(|b| ch.process(b)).collect();
            let mut w = std::io::BufWriter::new(std::fs::File::create(&a[5]).unwrap());
            for v in y {
                w.write_all(&(v as f32).to_le_bytes()).unwrap();
            }
        }
        "arq" => {
            let preset = &a[2];
            let sigma: f64 = a[3].parse().unwrap();
            let seed: u64 = a[4].parse().unwrap();
            let nbytes: usize = a[5].parse().unwrap();
            let max_secs: f64 = a[6].parse().unwrap();
            let cfg = |call: &str, s| {
                let mut c = Config { seed: s, ..Config::new(Bandwidth::Hz2300, call) };
                c.host.registered = true;
                c.host.compression = Compression::Off;
                c
            };
            let mut st = [Station::new(cfg("N0CALL", 3), Options::default()),
                          Station::new(cfg("N1CALL", 5), Options::default())];
            let mut ch = [Channel::new(preset, seed * 2 + 1), Channel::new(preset, seed * 2 + 2)];
            let mut rng = Rng::new(seed ^ 0xC0FFEE);
            let mut prng = Rng::new(seed + 99);
            let payload: Vec<u8> = (0..nbytes).map(|_| (prng.u() * 256.0) as u8).collect();
            let block = 480usize;
            let mut wire: [VecDeque<f64>; 2] = [VecDeque::from(vec![0.0; 2400]), VecDeque::from(vec![0.0; 2400])];
            let (mut p_on, mut n_on) = (0.0f64, 0usize);
            let mut got: Vec<u8> = Vec::new();
            let (mut t_link, mut t_done, mut top) = (None::<f64>, None::<f64>, 0usize);
            let mut sent = false;
            st[0].connect("N0CALL", "N1CALL", &[]);
            let mut t = 0.0;
            while t < max_secs {
                // what each station hears: the other's output through its channel, plus noise
                let heard: [Vec<f64>; 2] = std::array::from_fn(|i| {
                    let from = 1 - i;
                    let x: Vec<f64> = (0..block).map(|_| wire[from].pop_front().unwrap_or(0.0)).collect();
                    let y = ch[i].process(&x);
                    y.iter().map(|v| v + sigma * rng.g()).collect()
                });
                for i in 0..2 {
                    let mut out = vec![0.0; block];
                    st[i].process(&heard[i], &mut out);
                    if i == 0 {
                        for &v in &out {
                            if v != 0.0 {
                                p_on += v * v;
                                n_on += 1;
                            }
                        }
                    }
                    wire[i].extend(out);
                    while let Some(r) = st[i].pop() {
                        match r {
                            Report::Host(HostEvent::Connected { .. }) if i == 0 && t_link.is_none() => {
                                t_link = Some(t)
                            }
                            Report::Host(HostEvent::Data(d)) if i == 1 => got.extend(d),
                            Report::Bitrate { level, tx: true, .. } if i == 0 => top = top.max(level),
                            _ => {}
                        }
                    }
                }
                if t_link.is_some() && !sent {
                    st[0].send(&payload);
                    sent = true;
                }
                t += block as f64 / 48000.0;
                if got.len() >= nbytes {
                    t_done = Some(t);
                    break;
                }
            }
            assert!(got.len() <= nbytes && got[..] == payload[..got.len()], "corrupted delivery");
            let snr = if n_on > 0 && sigma > 0.0 {
                10.0 * ((p_on / n_on as f64) / (sigma * sigma * 2500.0 / 24000.0)).log10()
            } else {
                f64::NAN
            };
            println!(
                "{{\"chan\":\"{preset}\",\"sigma\":{sigma},\"seed\":{seed},\"bytes\":{nbytes},\"snr_inburst\":{snr:.2},\"t_link\":{},\"t_done\":{},\"t_end\":{t:.1},\"delivered\":{},\"top_level\":{top}}}",
                t_link.map_or("null".into(), |v| format!("{v:.1}")),
                t_done.map_or("null".into(), |v| format!("{v:.1}")),
                got.len()
            );
        }
        _ => panic!("chan|arq"),
    }
}
