//! NoiseBasedChunkGenerator.doFill, minus the part that writes BlockStates
//! (Java does that from the codes we return).

use super::aquifer::*;
use super::beard::{Beard, JUNCTION_INTS, PIECE_INTS};
use super::eval::*;
use super::jmath::*;
use super::program::*;
use super::rng::Positional;

/// Must match GenParams.java.
#[repr(C)]
pub struct FillParams {
    pub min_x: i32,
    pub min_z: i32,
    pub min_y: i32,
    pub height: i32,
    pub cell_w: i32,
    pub cell_h: i32,
    pub sea_level: i32,
    pub flags: i32,
    pub df_flags: i32,
    pub n_pieces: i32,
    pub n_junctions: i32,
    pub _pad: i32,
    pub aquifer_lo: i64,
    pub aquifer_hi: i64,
    pub ore_lo: i64,
    pub ore_hi: i64,
}

pub const FLAG_AQUIFERS: i32 = 1;
pub const FLAG_ORE_VEINS: i32 = 2;

/// Ore vein output codes, after the aquifer ones.
pub const OUT_COPPER_ORE: u8 = 4;
pub const OUT_RAW_COPPER_BLOCK: u8 = 5;
pub const OUT_GRANITE: u8 = 6;
pub const OUT_DEEPSLATE_IRON_ORE: u8 = 7;
pub const OUT_RAW_IRON_BLOCK: u8 = 8;
pub const OUT_TUFF: u8 = 9;
/// set when vanilla would call markPosForPostprocessing (if the block has a fluid)
pub const OUT_SCHEDULE: u8 = 0x80;

pub fn layout(p: &FillParams) -> Layout {
    let cells_xz = (16 / p.cell_w) as u32;
    Layout {
        cell_w: p.cell_w,
        cell_h: p.cell_h,
        cells_xz,
        cells_y: floor_div(p.height, p.cell_h) as u32,
        first_cell_x: floor_div(p.min_x, p.cell_w),
        first_cell_z: floor_div(p.min_z, p.cell_w),
        cell_min_y: floor_div(p.min_y, p.cell_h),
        first_noise_x: p.min_x >> 2,
        first_noise_z: p.min_z >> 2,
        noise_size: (cells_xz as i32 * p.cell_w) >> 2,
    }
}

pub fn output_len(p: &FillParams) -> usize {
    256 * floor_div(p.height, p.cell_h) as usize * p.cell_h as usize
}

/// Output layout: index ((y - first_y) * 16 + local z) * 16 + local x, with
/// first_y = cell_min_y * cell_h, same range doFill walks.
pub fn fill(prog: &Program, p: &FillParams, pieces: &[i32], junctions: &[i32], kernel: &[f32], out: &mut [u8]) {
    let beard = Beard { pieces, junctions, kernel };
    let mut ev = Eval::new(prog, layout(p), beard);
    ev.prepare();
    let ev = ev;
    let l = &ev.layout;

    let picker = Picker { sea_level: p.sea_level, df_flags: p.df_flags };
    let aquifers_on = p.flags & FLAG_AQUIFERS != 0;
    let mut aquifer = Aquifer::new(
        p.min_x,
        p.min_z,
        p.min_y,
        p.height,
        Positional { lo: p.aquifer_lo, hi: p.aquifer_hi },
        Picker { sea_level: p.sea_level, df_flags: p.df_flags },
    );
    let ore = (p.flags & FLAG_ORE_VEINS != 0).then_some(Positional { lo: p.ore_lo, hi: p.ore_hi });
    let roots = prog.roots;
    let first_y = l.cell_min_y * l.cell_h;
    let (w, h) = (l.cell_w, l.cell_h);

    for cx in 0..l.cells_xz {
        for cz in 0..l.cells_xz {
            for cy in 0..l.cells_y {
                for iy in 0..h {
                    let y = (l.cell_min_y + cy as i32) * h + iy;
                    let fy = iy as f64 / h as f64;
                    for ix in 0..w {
                        let x = p.min_x + cx as i32 * w + ix;
                        let fx = ix as f64 / w as f64;
                        for iz in 0..w {
                            let z = p.min_z + cz as i32 * w + iz;
                            let fz = iz as f64 / w as f64;

                            let cell = Mode::Cell { cx, cy, cz, fx, fy, fz };
                            // cacheAllInCell(add(finalDensity, beardifier))
                            let density = eval_root(&ev, roots[ROOT_FINAL], Pt { x, y, z, mode: cell }) + ev.beard.compute(x, y, z);

                            let block = Mode::Block { cx, cy, cz, dx: fx, dy: fy, dz: fz };
                            let mut result = if aquifers_on {
                                aquifer.compute_substance(&ev, x, y, z, block, density)
                            } else {
                                aquifer.should_schedule = false;
                                if density > 0.0 { None } else { Some(picker.compute(y).at_code(y)) }
                            };
                            if result.is_none() {
                                if let Some(ore) = ore {
                                    result = ore_vein(&ev, ore, x, y, z, block);
                                }
                            }

                            let mut code = result.unwrap_or(OUT_DEFAULT_BLOCK);
                            if aquifer.should_schedule {
                                code |= OUT_SCHEDULE;
                            }
                            out[(((y - first_y) * 16 + (z & 15)) * 16 + (x & 15)) as usize] = code;
                        }
                    }
                }
            }
        }
    }
}

/// OreVeinifier, float constants and all.
fn ore_vein(ev: &Eval, ore: Positional, x: i32, y: i32, z: i32, block: Mode) -> Option<u8> {
    let roots = ev.prog.roots;
    let toggle = eval_root(ev, roots[ROOT_VEIN_TOGGLE], Pt { x, y, z, mode: block });
    let copper = toggle > 0.0;
    let (min_y, max_y) = if copper { (0, 50) } else { (-60, -8) };
    let d1 = toggle.abs();
    let j = max_y - y;
    let k = y - min_y;
    if k < 0 || j < 0 {
        return None;
    }
    let d2 = clamped_map(j.min(k) as f64, 0.0, 20.0, -0.2, 0.0);
    if d1 + d2 < 0.4f32 as f64 {
        return None;
    }
    let mut r = ore.at(x, y, z);
    if r.next_float() > 0.7f32 {
        return None;
    }
    if eval_root(ev, roots[ROOT_VEIN_RIDGED], Pt { x, y, z, mode: block }) >= 0.0 {
        return None;
    }
    let d3 = clamped_map(d1, 0.4f32 as f64, 0.6f32 as f64, 0.1f32 as f64, 0.3f32 as f64);
    if (r.next_float() as f64) < d3 && eval_root(ev, roots[ROOT_VEIN_GAP], Pt { x, y, z, mode: block }) > (-0.3f32) as f64 {
        let raw = r.next_float() < 0.02f32;
        Some(match (copper, raw) {
            (true, true) => OUT_RAW_COPPER_BLOCK,
            (true, false) => OUT_COPPER_ORE,
            (false, true) => OUT_RAW_IRON_BLOCK,
            (false, false) => OUT_DEEPSLATE_IRON_ORE,
        })
    } else {
        Some(if copper { OUT_GRANITE } else { OUT_TUFF })
    }
}

pub fn check_beard_lengths(p: &FillParams) -> Option<(usize, usize)> {
    let pieces = usize::try_from(p.n_pieces).ok()? * PIECE_INTS;
    let junctions = usize::try_from(p.n_junctions).ok()? * JUNCTION_INTS;
    Some((pieces, junctions))
}
