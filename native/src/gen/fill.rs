//! NoiseBasedChunkGenerator.doFill, minus the part that writes BlockStates
//! (Java does that from the codes we return).

use super::aquifer::*;
use super::batch::{Batch, BatchKind, Memo};
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
    let rows = (l.cells_y as i32 * l.cell_h) as usize;

    // Every cache involved is a pure function of position, so the work can
    // be split into passes over the whole chunk instead of vanilla's single
    // interleaved loop. Index of a block: ((y - first_y) * 16 + z) * 16 + x.
    let index = |x: i32, y: i32, z: i32| (((y - first_y) * 16 + (z & 15)) * 16 + (x & 15)) as usize;

    // 1. cacheAllInCell(add(finalDensity, beardifier)) for every block, one
    // column of cells per batch
    let mut density = vec![0.0f64; rows * 256];
    let final_root = roots[ROOT_FINAL].expect("checked at parse time");
    let (w, h) = (l.cell_w, l.cell_h);
    let per_column = l.cells_y as usize * (h * w * w) as usize;
    let all: Vec<u32> = (0..per_column as u32).collect();
    let mut values = vec![0.0; per_column];
    for cx in 0..l.cells_xz {
        for cz in 0..l.cells_xz {
            let mut b = Batch {
                x: Vec::with_capacity(per_column),
                y: Vec::with_capacity(per_column),
                z: Vec::with_capacity(per_column),
                kind: BatchKind::Cell {
                    cx,
                    cz,
                    cy: Vec::with_capacity(per_column),
                    fx: Vec::with_capacity(per_column),
                    fy: Vec::with_capacity(per_column),
                    fz: Vec::with_capacity(per_column),
                },
            };
            let BatchKind::Cell { cy: ref mut bcy, ref mut fx, ref mut fy, ref mut fz, .. } = b.kind else { unreachable!() };
            for cy in 0..l.cells_y {
                for iy in 0..h {
                    for ix in 0..w {
                        for iz in 0..w {
                            b.x.push(p.min_x + cx as i32 * w + ix);
                            b.y.push((l.cell_min_y + cy as i32) * h + iy);
                            b.z.push(p.min_z + cz as i32 * w + iz);
                            bcy.push(cy);
                            fx.push(ix as f64 / w as f64);
                            fy.push(iy as f64 / h as f64);
                            fz.push(iz as f64 / w as f64);
                        }
                    }
                }
            }
            let mut memo = Memo::new(prog);
            ev.eval_batch(final_root, &b, &all, &mut values, &mut memo);
            let beard_empty = ev.beard.is_empty();
            for k in 0..per_column {
                let (x, y, z) = (b.x[k], b.y[k], b.z[k]);
                // an empty beardifier still adds 0.0 in vanilla (turns -0.0 into 0.0)
                let beard = if beard_empty { 0.0 } else { ev.beard.compute(x, y, z) };
                density[index(x, y, z)] = values[k] + beard;
            }
        }
    }

    // 2. aquifer, then 3. ore veins where the aquifer left solid ground
    let mut pending_ore = Vec::new();
    for_each_block(l, p, |x, y, z, _, block| {
        let i = index(x, y, z);
        let d = density[i];
        let result = if aquifers_on {
            aquifer.compute_substance(&ev, x, y, z, block, d)
        } else {
            aquifer.should_schedule = false;
            if d > 0.0 { None } else { Some(picker.compute(y).at_code(y)) }
        };
        out[i] = match result {
            Some(code) => code | if aquifer.should_schedule { OUT_SCHEDULE } else { 0 },
            None => {
                if ore.is_some() {
                    pending_ore.push((x, y, z, block));
                }
                OUT_DEFAULT_BLOCK
            }
        };
    });

    if let Some(ore) = ore {
        for (x, y, z, block) in pending_ore {
            if let Some(code) = ore_vein(&ev, ore, x, y, z, block) {
                // solid ground, so the aquifer never asked for a fluid update here
                out[index(x, y, z)] = code;
            }
        }
    }
}

/// Visits every block doFill would, handing over both the cell-fill and the
/// per-block interpolation mode for it.
fn for_each_block(l: &Layout, p: &FillParams, mut f: impl FnMut(i32, i32, i32, Mode, Mode)) {
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
                            f(
                                x,
                                y,
                                z,
                                Mode::Cell { cx, cy, cz, fx, fy, fz },
                                Mode::Block { cx, cy, cz, dx: fx, dy: fy, dz: fz },
                            );
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
