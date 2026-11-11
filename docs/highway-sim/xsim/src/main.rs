// xsim: XARA HF PHY frame-success harness.
//   xsim gen BW LEVEL N SEED OUT.f32          clean bursts at 48 kHz, one frame per slot
//   xsim dec BW LEVEL N SEED FADED.f32 REF.f32 NOISESEED SNR...   prints "snr,ok,n"
// SNR is average signal power (REF, over the burst) over noise power in 2500 Hz.
use std::io::{Read, Write};
use xara_phy::hf::level::{Bandwidth, Record};
use xara_phy::hf::rx::receiver::{Event, Receiver};
use xara_phy::hf::tx::Modulator;

const PAD: usize = 24000;

struct Rng(u64);
impl Rng {
    fn new(seed: u64) -> Self {
        Self(seed.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1)
    }
    fn next_u64(&mut self) -> u64 {
        self.0 ^= self.0 >> 12;
        self.0 ^= self.0 << 25;
        self.0 ^= self.0 >> 27;
        self.0.wrapping_mul(0x2545_F491_4F6C_DD1D)
    }
    fn uniform(&mut self) -> f64 {
        (self.next_u64() >> 11) as f64 / (1u64 << 53) as f64
    }
    fn below(&mut self, n: u32) -> u32 {
        (self.uniform() * f64::from(n)) as u32
    }
    fn gauss(&mut self) -> f64 {
        let u = self.uniform().max(1e-300);
        let v = self.uniform();
        (-2.0 * u.ln()).sqrt() * (2.0 * std::f64::consts::PI * v).cos()
    }
}

fn frames(bw: Bandwidth, rec: Record, n: usize, seed: u64) -> Vec<Vec<u8>> {
    let mut rng = Rng::new(seed);
    let len = bw.level(rec).frame_bytes() as usize;
    (0..n)
        .map(|_| {
            let mut f: Vec<u8> = (0..len - 2).map(|_| rng.next_u64() as u8).collect();
            f.extend(xara_phy::crc::crc16_genibus(&f).to_be_bytes());
            f
        })
        .collect()
}

fn tx_len(bw: Bandwidth, rec: Record) -> usize {
    let f = frames(bw, rec, 1, 1);
    let mut r = Rng::new(1);
    Modulator::new(bw).transmission(rec, &[&f[0]], &mut |n| r.below(n)).len()
}

fn read_f32(p: &str) -> Vec<f32> {
    let mut b = Vec::new();
    std::fs::File::open(p).unwrap().read_to_end(&mut b).unwrap();
    b.chunks_exact(4).map(|c| f32::from_le_bytes(c.try_into().unwrap())).collect()
}

fn main() {
    let a: Vec<String> = std::env::args().collect();
    let bw: Bandwidth = a[2].parse().unwrap();
    let rec = Record::new(a[3].parse().unwrap()).unwrap();
    let n: usize = a[4].parse().unwrap();
    let seed: u64 = a[5].parse().unwrap();
    let tl = tx_len(bw, rec);
    let slot = PAD + tl + PAD;
    match a[1].as_str() {
        "info" => println!("{slot} {tl}"),
        "gen" => {
            let m = Modulator::new(bw);
            let fs = frames(bw, rec, n, seed);
            let mut r = Rng::new(seed ^ 0xABCD);
            let mut out = Vec::with_capacity(n * slot);
            for f in &fs {
                let x = m.transmission(rec, &[f], &mut |k| r.below(k));
                assert_eq!(x.len(), tl);
                let rms = (x.iter().map(|v| v * v).sum::<f64>() / x.len() as f64).sqrt();
                out.extend(std::iter::repeat_n(0.0f32, PAD));
                out.extend(x.iter().map(|v| (v / rms * 0.12) as f32));
                out.extend(std::iter::repeat_n(0.0f32, PAD));
            }
            let mut w = std::io::BufWriter::new(std::fs::File::create(&a[6]).unwrap());
            for v in out {
                w.write_all(&v.to_le_bytes()).unwrap();
            }
        }
        "dec" => {
            let fs = frames(bw, rec, n, seed);
            let faded = read_f32(&a[6]);
            let refx = read_f32(&a[7]);
            let nseed: u64 = a[8].parse().unwrap();
            let snrs: Vec<f64> = a[9..].iter().map(|s| s.parse().unwrap()).collect();
            assert!(faded.len() >= n * slot && refx.len() >= n * slot);
            let mut p = 0.0f64;
            for i in 0..n {
                p += refx[i * slot + PAD..i * slot + PAD + tl].iter().map(|&v| f64::from(v).powi(2)).sum::<f64>();
            }
            let pref = p / (n * tl) as f64;
            let threads = std::thread::available_parallelism().map_or(1, |x| x.get());
            for &snr in &snrs {
                let sigma = (pref * 24000.0 / (2500.0 * 10f64.powf(snr / 10.0))).sqrt();
                let next = std::sync::atomic::AtomicUsize::new(0);
                let ok = std::sync::atomic::AtomicUsize::new(0);
                std::thread::scope(|s| {
                    for _ in 0..threads {
                        s.spawn(|| loop {
                            let i = next.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                            if i >= n {
                                break;
                            }
                            let mut r = Rng::new(nseed ^ ((i as u64) << 20) ^ ((snr * 100.0) as i64 as u64));
                            let x: Vec<f64> =
                                faded[i * slot..(i + 1) * slot].iter().map(|&v| f64::from(v) + sigma * r.gauss()).collect();
                            let mut rx = Receiver::new(bw, &[rec], 1, None);
                            let mut ev: Vec<Event> = x.chunks(480).flat_map(|b| rx.feed(b)).collect();
                            ev.extend(rx.flush());
                            let good = ev.iter().any(|e| matches!(e, Event::Frame(f) if f.frame.ok && f.frame.bytes == fs[i]));
                            if good {
                                ok.fetch_add(1, std::sync::atomic::Ordering::Relaxed);
                            }
                        });
                    }
                });
                println!("{snr},{},{n}", ok.load(std::sync::atomic::Ordering::Relaxed));
            }
        }
        _ => panic!("gen|dec|info"),
    }
}
