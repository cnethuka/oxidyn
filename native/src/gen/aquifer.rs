//! Port of Aquifer.NoiseBasedAquifer, plus the disabled variant.
//!
//! Its caches (aquifer centres, fluid status per grid cell) only ever store
//! pure functions of position, so evaluation order doesn't matter and we're
//! free to walk the chunk however we like.

use super::eval::{Eval, Mode, Pt};
use super::jmath::*;
use super::program::*;
use super::rng::Positional;
use std::collections::HashMap;

/// What a FluidStatus can hold: the settings' default fluid or lava.
#[derive(Clone, Copy, PartialEq, Eq)]
pub enum Fluid {
    Default,
    Lava,
}

/// Output codes, shared with the Java side.
pub const OUT_DEFAULT_BLOCK: u8 = 0;
pub const OUT_AIR: u8 = 1;
pub const OUT_DEFAULT_FLUID: u8 = 2;
pub const OUT_LAVA: u8 = 3;

/// Properties of the settings' default fluid block, computed in Java.
pub const DF_IS_LAVA: i32 = 1;
pub const DF_IS_WATER: i32 = 2;
pub const DF_IS_AIR: i32 = 4;
pub const DF_IS_LAVA_DEFAULT_STATE: i32 = 8;

const WAY_BELOW_MIN_Y: i32 = -2032 << 4;
const LAVA_LEVEL: i32 = -54;

#[derive(Clone, Copy)]
pub struct FluidStatus {
    level: i32,
    fluid: Fluid,
}

impl FluidStatus {
    #[inline]
    pub fn at_code(&self, y: i32) -> u8 {
        self.at(y)
    }

    #[inline]
    fn at(&self, y: i32) -> u8 {
        if y < self.level {
            match self.fluid {
                Fluid::Default => OUT_DEFAULT_FLUID,
                Fluid::Lava => OUT_LAVA,
            }
        } else {
            OUT_AIR
        }
    }
}

pub struct Picker {
    pub sea_level: i32,
    pub df_flags: i32,
}

impl Picker {
    #[inline]
    pub fn compute(&self, y: i32) -> FluidStatus {
        if y < LAVA_LEVEL.min(self.sea_level) {
            FluidStatus { level: LAVA_LEVEL, fluid: Fluid::Lava }
        } else {
            FluidStatus { level: self.sea_level, fluid: Fluid::Default }
        }
    }

    #[inline]
    fn is_lava(&self, code: u8) -> bool {
        code == OUT_LAVA || (code == OUT_DEFAULT_FLUID && self.df_flags & DF_IS_LAVA != 0)
    }

    #[inline]
    fn is_water(&self, code: u8) -> bool {
        code == OUT_DEFAULT_FLUID && self.df_flags & DF_IS_WATER != 0
    }

    #[inline]
    fn is_air(&self, code: u8) -> bool {
        code == OUT_AIR || (code == OUT_DEFAULT_FLUID && self.df_flags & DF_IS_AIR != 0)
    }
}

const SURFACE_SAMPLING_OFFSETS: [(i32, i32); 13] = [
    (0, 0), (-2, -1), (-1, -1), (0, -1), (1, -1), (-3, 0), (-2, 0), (-1, 0), (1, 0), (-2, 1), (-1, 1), (0, 1), (1, 1),
];

pub struct Aquifer {
    random: Positional,
    picker: Picker,
    min_y: i32,
    height: i32,
    min_grid_x: i32,
    min_grid_y: i32,
    min_grid_z: i32,
    size_x: i32,
    size_z: i32,
    locations: Vec<Option<(i32, i32, i32)>>,
    statuses: Vec<Option<FluidStatus>>,
    prelim: HashMap<(i32, i32), i32>,
    pub should_schedule: bool,
}

#[inline]
fn grid_x(v: i32) -> i32 {
    v.div_euclid(16)
}
#[inline]
fn grid_y(v: i32) -> i32 {
    v.div_euclid(12)
}

#[inline]
fn similarity(a: i32, b: i32) -> f64 {
    1.0 - (b.wrapping_sub(a)).wrapping_abs() as f64 / 25.0
}

impl Aquifer {
    pub fn new(min_x: i32, min_z: i32, min_y: i32, height: i32, random: Positional, picker: Picker) -> Self {
        let min_grid_x = grid_x(min_x) - 1;
        let size_x = grid_x(min_x + 15) + 1 - min_grid_x + 1;
        let min_grid_y = grid_y(min_y) - 1;
        let size_y = grid_y(min_y + height) + 1 - min_grid_y + 1;
        let min_grid_z = grid_x(min_z) - 1;
        let size_z = grid_x(min_z + 15) + 1 - min_grid_z + 1;
        let total = (size_x * size_y * size_z) as usize;
        Aquifer {
            random,
            picker,
            min_y,
            height,
            min_grid_x,
            min_grid_y,
            min_grid_z,
            size_x,
            size_z,
            locations: vec![None; total],
            statuses: vec![None; total],
            prelim: HashMap::new(),
            should_schedule: false,
        }
    }

    #[inline]
    fn index(&self, x: i32, y: i32, z: i32) -> usize {
        (((y - self.min_grid_y) * self.size_z + (z - self.min_grid_z)) * self.size_x + (x - self.min_grid_x)) as usize
    }

    /// Returns None for "solid" (vanilla's null), otherwise an OUT_* code.
    pub fn compute_substance(&mut self, ev: &Eval, x: i32, y: i32, z: i32, block: Mode, density: f64) -> Option<u8> {
        if density > 0.0 {
            self.should_schedule = false;
            return None;
        }
        let global = self.picker.compute(y);
        if self.picker.is_lava(global.at(y)) {
            self.should_schedule = false;
            return Some(OUT_LAVA);
        }

        let gx = (x - 5).div_euclid(16);
        let gy = (y + 1).div_euclid(12);
        let gz = (z - 5).div_euclid(16);
        let (mut d1st, mut d2nd, mut d3rd) = (i32::MAX, i32::MAX, i32::MAX);
        let (mut p1st, mut p2nd, mut p3rd) = ((0, 0, 0), (0, 0, 0), (0, 0, 0));
        for ox in 0..=1 {
            for oy in -1..=1 {
                for oz in 0..=1 {
                    let (cx, cy, cz) = (gx + ox, gy + oy, gz + oz);
                    let idx = self.index(cx, cy, cz);
                    let loc = match self.locations[idx] {
                        Some(l) => l,
                        None => {
                            let mut r = self.random.at(cx, cy, cz);
                            let l = (
                                cx * 16 + r.next_int_bounded(10),
                                cy * 12 + r.next_int_bounded(9),
                                cz * 16 + r.next_int_bounded(10),
                            );
                            self.locations[idx] = Some(l);
                            l
                        }
                    };
                    let (dx, dy, dz) = (loc.0 - x, loc.1 - y, loc.2 - z);
                    let d = dx * dx + dy * dy + dz * dz;
                    if d1st >= d {
                        p3rd = p2nd;
                        p2nd = p1st;
                        p1st = loc;
                        d3rd = d2nd;
                        d2nd = d1st;
                        d1st = d;
                    } else if d2nd >= d {
                        p3rd = p2nd;
                        p2nd = loc;
                        d3rd = d2nd;
                        d2nd = d;
                    } else if d3rd >= d {
                        p3rd = loc;
                        d3rd = d;
                    }
                }
            }
        }

        let s1 = self.status(ev, p1st);
        let sim12 = similarity(d1st, d2nd);
        let state = s1.at(y);
        if sim12 <= 0.0 {
            // FLOWING_UPDATE_SIMULARITY = similarity(10^2, 12^2)
            self.should_schedule = sim12 >= similarity(100, 144);
            return Some(state);
        }
        if self.picker.is_water(state) && self.picker.is_lava(self.picker.compute(y - 1).at(y - 1)) {
            self.should_schedule = true;
            return Some(state);
        }

        let mut barrier = f64::NAN;
        let s2 = self.status(ev, p2nd);
        let d2 = sim12 * self.pressure(ev, x, y, z, block, &mut barrier, s1, s2);
        if density + d2 > 0.0 {
            self.should_schedule = false;
            return None;
        }
        let s3 = self.status(ev, p3rd);
        let sim13 = similarity(d1st, d3rd);
        if sim13 > 0.0 {
            let d3 = sim12 * sim13 * self.pressure(ev, x, y, z, block, &mut barrier, s1, s3);
            if density + d3 > 0.0 {
                self.should_schedule = false;
                return None;
            }
        }
        let sim23 = similarity(d2nd, d3rd);
        if sim23 > 0.0 {
            let d5 = sim12 * sim23 * self.pressure(ev, x, y, z, block, &mut barrier, s2, s3);
            if density + d5 > 0.0 {
                self.should_schedule = false;
                return None;
            }
        }
        self.should_schedule = true;
        Some(state)
    }

    #[allow(clippy::too_many_arguments)]
    fn pressure(&self, ev: &Eval, x: i32, y: i32, z: i32, block: Mode, barrier: &mut f64, a: FluidStatus, b: FluidStatus) -> f64 {
        let ba = a.at(y);
        let bb = b.at(y);
        let p = &self.picker;
        if (p.is_lava(ba) && p.is_water(bb)) || (p.is_water(ba) && p.is_lava(bb)) {
            return 2.0;
        }
        let j = (a.level.wrapping_sub(b.level)).wrapping_abs();
        if j == 0 {
            return 0.0;
        }
        let d0 = 0.5 * a.level.wrapping_add(b.level) as f64;
        let d1 = y as f64 + 0.5 - d0;
        let d2 = j as f64 / 2.0;
        let d9 = d2 - d1.abs();
        let d10 = if d1 > 0.0 {
            let d11 = 0.0 + d9;
            if d11 > 0.0 { d11 / 1.5 } else { d11 / 2.5 }
        } else {
            let d15 = 3.0 + d9;
            if d15 > 0.0 { d15 / 3.0 } else { d15 / 10.0 }
        };
        let d12 = if !(d10 < -2.0) && !(d10 > 2.0) {
            if barrier.is_nan() {
                let root = ev.prog.roots[ROOT_BARRIER];
                *barrier = eval_root(ev, root, Pt { x, y, z, mode: block });
            }
            *barrier
        } else {
            0.0
        };
        2.0 * (d12 + d10)
    }

    fn status(&mut self, ev: &Eval, loc: (i32, i32, i32)) -> FluidStatus {
        let idx = self.index(grid_x(loc.0), grid_y(loc.1), grid_x(loc.2));
        if let Some(s) = self.statuses[idx] {
            return s;
        }
        let s = self.compute_fluid(ev, loc.0, loc.1, loc.2);
        self.statuses[idx] = Some(s);
        s
    }

    fn compute_fluid(&mut self, ev: &Eval, x: i32, y: i32, z: i32) -> FluidStatus {
        let status = self.picker.compute(y);
        let mut min_surface = i32::MAX;
        let above = y + 12;
        let below = y - 12;
        let mut flooded_at_centre = false;
        for (ox, oz) in SURFACE_SAMPLING_OFFSETS {
            let sx = x + (ox << 4);
            let sz = z + (oz << 4);
            let surface = self.preliminary_surface(ev, sx, sz);
            let k1 = surface.wrapping_add(8);
            let centre = ox == 0 && oz == 0;
            if centre && below > k1 {
                return status;
            }
            let over = above > k1;
            if over || centre {
                let s1 = self.picker.compute(k1);
                if !self.picker.is_air(s1.at(k1)) {
                    if centre {
                        flooded_at_centre = true;
                    }
                    if over {
                        return s1;
                    }
                }
            }
            min_surface = min_surface.min(surface);
        }
        let level = self.surface_level(ev, x, y, z, status, min_surface, flooded_at_centre);
        FluidStatus { level, fluid: self.fluid_type(ev, x, y, z, status, level) }
    }

    #[allow(clippy::too_many_arguments)]
    fn surface_level(&self, ev: &Eval, x: i32, y: i32, z: i32, status: FluidStatus, min_surface: i32, flooded: bool) -> i32 {
        let point = Pt { x, y, z, mode: Mode::Point };
        let roots = &ev.prog.roots;
        let (d0, d1);
        // OverworldBiomeBuilder.isDeepDarkRegion, with its float constants
        if eval_root(ev, roots[ROOT_EROSION], point) < (-0.225f32) as f64 && eval_root(ev, roots[ROOT_DEPTH], point) > 0.9f32 as f64 {
            d0 = -1.0;
            d1 = -1.0;
        } else {
            let i = min_surface.wrapping_add(8).wrapping_sub(y);
            let d2 = if flooded { clamped_map(i as f64, 0.0, 64.0, 1.0, 0.0) } else { 0.0 };
            let d3 = clamp(eval_root(ev, roots[ROOT_FLOODEDNESS], point), -1.0, 1.0);
            let d4 = map(d2, 1.0, 0.0, -0.3, 0.8);
            let d5 = map(d2, 1.0, 0.0, -0.8, 0.4);
            d0 = d3 - d5;
            d1 = d3 - d4;
        }
        if d1 > 0.0 {
            status.level
        } else if d0 > 0.0 {
            self.randomized_surface(ev, x, y, z, min_surface)
        } else {
            WAY_BELOW_MIN_Y
        }
    }

    fn randomized_surface(&self, ev: &Eval, x: i32, y: i32, z: i32, min_surface: i32) -> i32 {
        let k = x.div_euclid(16);
        let l = y.div_euclid(40);
        let i1 = z.div_euclid(16);
        let j1 = l * 40 + 20;
        let d0 = eval_root(ev, ev.prog.roots[ROOT_SPREAD], Pt { x: k, y: l, z: i1, mode: Mode::Point }) * 10.0;
        // Mth.quantize
        let l1 = floor(d0 / 3.0).wrapping_mul(3);
        min_surface.min(j1.wrapping_add(l1))
    }

    fn fluid_type(&self, ev: &Eval, x: i32, y: i32, z: i32, status: FluidStatus, level: i32) -> Fluid {
        let is_lava_default = match status.fluid {
            Fluid::Lava => true,
            Fluid::Default => self.picker.df_flags & DF_IS_LAVA_DEFAULT_STATE != 0,
        };
        if level <= -10 && level != WAY_BELOW_MIN_Y && !is_lava_default {
            let p = Pt { x: x.div_euclid(64), y: y.div_euclid(40), z: z.div_euclid(64), mode: Mode::Point };
            if eval_root(ev, ev.prog.roots[ROOT_LAVA], p).abs() > 0.3 {
                return Fluid::Lava;
            }
        }
        status.fluid
    }

    /// NoiseChunk.preliminarySurfaceLevel, cached per quart column like vanilla
    fn preliminary_surface(&mut self, ev: &Eval, x: i32, z: i32) -> i32 {
        let key = ((x >> 2) << 2, (z >> 2) << 2);
        if let Some(&v) = self.prelim.get(&key) {
            return v;
        }
        let root = ev.prog.roots[ROOT_INITIAL_NO_JAGGEDNESS];
        let mut result = i32::MAX;
        let mut y = self.min_y + self.height;
        while y >= self.min_y {
            if eval_root(ev, root, Pt { x: key.0, y, z: key.1, mode: Mode::Point }) > 0.390625 {
                result = y;
                break;
            }
            y -= ev.layout.cell_h;
        }
        self.prelim.insert(key, result);
        result
    }
}

/// Evaluates one router function at one point. Missing functions behave
/// like vanilla's DensityFunctions.zero().
#[inline]
pub fn eval_root(ev: &Eval, root: Option<u32>, p: Pt) -> f64 {
    match root {
        Some(id) => {
            ev.next_point();
            ev.eval(id, &p)
        }
        None => 0.0,
    }
}
