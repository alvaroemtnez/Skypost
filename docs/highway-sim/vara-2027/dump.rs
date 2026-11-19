// Validate VARA's table files and dump the interleavers for analysis.
use std::fs;
use xara_phy::dat::{PermOrder, RecordOrder, fm_dat, fm_tables, fm_tables_from_dat, hf_dat, hf_tables, hf_tables_from_dat, RecordTables};
use xara_phy::fm::level::FmBandwidth;
use xara_phy::hf::level::Bandwidth;

fn dump(path: &str, t: &[RecordTables]) {
    let mut s = String::new();
    for (i, r) in t.iter().enumerate() {
        s += &format!("R{i} turbo {}\n", r.turbo.iter().map(|v| v.to_string()).collect::<Vec<_>>().join(" "));
        s += &format!("R{i} chan {}\n", r.chan.iter().map(|v| v.to_string()).collect::<Vec<_>>().join(" "));
    }
    fs::write(path, s).unwrap();
}

fn main() {
    let hf = "/w/hf/out/app";
    let fm = "/w/fm/out/app";
    fs::create_dir_all("/w/dump").unwrap();
    for (i, bw) in Bandwidth::ALL.into_iter().enumerate() {
        let legacy_file = fs::read(format!("{hf}/Binary{}.dat", i + 4)).unwrap();
        println!("HF {bw:?}: legacy Binary{}.dat == xara generator: {}", i + 4, legacy_file == hf_dat(bw));
        let new = fs::read(format!("{hf}/Binary{}.dat", i + 1)).unwrap();
        let t = hf_tables_from_dat(bw, &new, PermOrder::TurboFirst).expect("parse 2027");
        println!("HF {bw:?}: 2027 Binary{}.dat parses ({} records, CRC + permutations ok)", i + 1, t.len());
        dump(&format!("/w/dump/hf{i}_2027.txt"), &t);
        dump(&format!("/w/dump/hf{i}_legacy.txt"), &hf_tables(bw));
    }
    match xara_phy::hf::Keying::load(std::path::Path::new(hf)) {
        Ok(_) => println!("HF Keying::load(VARA 4.9.1 dir): ok"),
        Err(e) => println!("HF Keying::load: ERROR {e}"),
    }
    for (i, bw) in [FmBandwidth::Narrow, FmBandwidth::Wide].into_iter().enumerate() {
        let legacy_file = fs::read(format!("{fm}/Binary{}.dat", i + 3)).unwrap();
        println!("FM {bw:?}: legacy Binary{}.dat == xara generator: {}", i + 3, legacy_file == fm_dat(bw));
        let new = fs::read(format!("{fm}/Binary{}.dat", i + 1)).unwrap();
        let t = fm_tables_from_dat(bw, &new, RecordOrder::Reverse).expect("parse fm 2027");
        println!("FM {bw:?}: 2027 Binary{}.dat parses ({} records)", i + 1, t.len());
        dump(&format!("/w/dump/fm{i}_2027.txt"), &t);
        dump(&format!("/w/dump/fm{i}_legacy.txt"), &fm_tables(bw));
    }
    match xara_phy::fm::Keying::load(std::path::Path::new(fm)) {
        Ok(_) => println!("FM Keying::load(VARA FM 4.4.1 dir): ok"),
        Err(e) => println!("FM Keying::load: ERROR {e}"),
    }
}
