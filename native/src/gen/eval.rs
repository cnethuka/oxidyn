//! Evaluates a Program the way vanilla's NoiseChunk would.
//!
//! The same density function gives different answers depending on how
//! NoiseChunk calls it, so every evaluation carries a Mode:
//!   Point - SinglePointContext (aquifer grid, preliminary surface, flat caches)
//!   Slice - filling interpolator cell corners
//!   Cell  - filling the per-cell cache of final density, interpolators use lerp3
//!   Block - the per-block loop (aquifer barrier, ore veins), interpolators use
//!           the incremental y -> x -> z lerps, which round differently

use super::beard::Beard;
use super::jmath::*;
use super::program::*;
use crate::noise;
use std::cell::Cell;

#[derive(Clone, Copy)]
pub enum Mode {
    Point,
    Slice,
    Cell { cx: u32, cy: u32, cz: u32, fx: f64, fy: f64, fz: f64 },
    Block { cx: u32, cy: u32, cz: u32, dx: f64, dy: f64, dz: f64 },
}

#[derive(Clone, Copy)]
pub struct Pt {
    pub x: i32,
    pub y: i32,
    pub z: i32,
    pub mode: Mode,
}

pub struct Layout {
    pub cell_w: i32,
    pub cell_h: i32,
    pub cells_xz: u32,
    pub cells_y: u32,
    pub first_cell_x: i32,
    pub first_cell_z: i32,
    pub cell_min_y: i32,
    pub first_noise_x: i32,
    pub first_noise_z: i32,
    /// FlatCache grids are (noise_size + 1)^2
    pub noise_size: i32,
}

impl Layout {
    /// Corner arrays are indexed [x][z][y], each axis one longer than the cell count.
    #[inline(always)]
    pub fn corner(&self, cx: u32, cz: u32, cy: u32) -> usize {
        let n = self.cells_xz as usize + 1;
        let ny = self.cells_y as usize + 1;
        (cx as usize * n + cz as usize) * ny + cy as usize
    }

    pub fn corner_count(&self) -> usize {
        let n = self.cells_xz as usize + 1;
        n * n * (self.cells_y as usize + 1)
    }
}

pub struct Eval<'a> {
    pub prog: &'a Program,
    pub layout: Layout,
    pub beard: Beard<'a>,
    pub flat: Vec<Vec<f64>>,
    pub interp: Vec<Vec<f64>>,
    once_stamp: Vec<Cell<u64>>,
    once_value: Vec<Cell<f64>>,
    stamp: Cell<u64>,
}

impl<'a> Eval<'a> {
    pub fn new(prog: &'a Program, layout: Layout, beard: Beard<'a>) -> Self {
        let once = prog.once_slots as usize;
        Eval {
            prog,
            layout,
            beard,
            flat: vec![Vec::new(); prog.flat_slots as usize],
            interp: vec![Vec::new(); prog.interp_slots as usize],
            once_stamp: (0..once).map(|_| Cell::new(0)).collect(),
            once_value: (0..once).map(|_| Cell::new(0.0)).collect(),
            stamp: Cell::new(0),
        }
    }

    /// Marks the start of a new sample point for CacheOnce. Everything
    /// evaluated until the next call shares cached values.
    #[inline(always)]
    pub fn next_point(&self) {
        self.stamp.set(self.stamp.get() + 1);
    }

    /// Fills every FlatCache grid, then every interpolator's corners. Grids
    /// come first since corner evaluation reads them.
    pub fn prepare(&mut self) {
        let l = &self.layout;
        let size = l.noise_size as usize + 1;
        for slot in 0..self.prog.flat_slots as usize {
            let Node::FlatCache { input, .. } = self.prog.nodes[self.prog.flat_nodes[slot] as usize] else {
                unreachable!()
            };
            let mut grid = Vec::with_capacity(size * size);
            for i in 0..size {
                let x = (self.layout.first_noise_x + i as i32) << 2;
                for k in 0..size {
                    let z = (self.layout.first_noise_z + k as i32) << 2;
                    self.next_point();
                    grid.push(self.eval(input, &Pt { x, y: 0, z, mode: Mode::Point }));
                }
            }
            self.flat[slot] = grid;
        }

        let count = self.layout.corner_count();
        let (n, ny) = (self.layout.cells_xz + 1, self.layout.cells_y + 1);
        for slot in 0..self.prog.interp_slots as usize {
            let Node::Interpolated { input, .. } = self.prog.nodes[self.prog.interp_nodes[slot] as usize] else {
                unreachable!()
            };
            let mut corners = vec![0.0; count];
            for cx in 0..n {
                let x = (self.layout.first_cell_x + cx as i32) * self.layout.cell_w;
                for cz in 0..n {
                    let z = (self.layout.first_cell_z + cz as i32) * self.layout.cell_w;
                    for cy in 0..ny {
                        let y = (cy as i32 + self.layout.cell_min_y) * self.layout.cell_h;
                        self.next_point();
                        corners[self.layout.corner(cx, cz, cy)] = self.eval(input, &Pt { x, y, z, mode: Mode::Slice });
                    }
                }
            }
            self.interp[slot] = corners;
        }
    }

    pub fn eval(&self, id: u32, p: &Pt) -> f64 {
        match self.prog.nodes[id as usize] {
            Node::Const(v) => v,
            Node::Add(a, b) => self.eval(a, p) + self.eval(b, p),
            Node::Mul(a, b) => {
                let d = self.eval(a, p);
                if d == 0.0 { 0.0 } else { d * self.eval(b, p) }
            }
            Node::Min(a, b, b_min) => {
                let d = self.eval(a, p);
                if d < b_min { d } else { java_min(d, self.eval(b, p)) }
            }
            Node::Max(a, b, b_max) => {
                let d = self.eval(a, p);
                if d > b_max { d } else { java_max(d, self.eval(b, p)) }
            }
            Node::AddConst(a, c) => self.eval(a, p) + c,
            Node::MulConst(a, c) => self.eval(a, p) * c,
            Node::Clamp(a, lo, hi) => clamp(self.eval(a, p), lo, hi),
            Node::Mapped(a, kind) => mapped(kind, self.eval(a, p)),
            Node::Range { input, lo, hi, when_in, when_out } => {
                let d = self.eval(input, p);
                if d >= lo && d < hi { self.eval(when_in, p) } else { self.eval(when_out, p) }
            }
            Node::YGradient { from_y, to_y, from_v, to_v } => {
                clamped_map(p.y as f64, from_y as f64, to_y as f64, from_v, to_v)
            }
            Node::Noise { noise, xz, y } => sample(noise, p.x as f64 * xz, p.y as f64 * y, p.z as f64 * xz),
            Node::Shifted { sx, sy, sz, xz, y, noise } => {
                let x = p.x as f64 * xz + self.eval(sx, p);
                let yy = p.y as f64 * y + self.eval(sy, p);
                let z = p.z as f64 * xz + self.eval(sz, p);
                sample(noise, x, yy, z)
            }
            Node::ShiftA(n) => shift(n, p.x as f64, 0.0, p.z as f64),
            Node::ShiftB(n) => shift(n, p.z as f64, p.x as f64, 0.0),
            Node::Shift(n) => shift(n, p.x as f64, p.y as f64, p.z as f64),
            Node::Blended(b) => noise::blended(b.header, b.octaves, p.x as f64, p.y as f64, p.z as f64),
            Node::Weird { input, noise, kind } => {
                let d = self.eval(input, p);
                let rarity = if kind == 1 { rarity_3d(d) } else { rarity_2d(d) };
                rarity * sample(noise, p.x as f64 / rarity, p.y as f64 / rarity, p.z as f64 / rarity).abs()
            }
            Node::Spline(s) => self.spline(s, p) as f64,
            Node::Interpolated { input, slot } => self.interpolated(input, slot, p),
            Node::FlatCache { input, slot } => {
                let l = &self.layout;
                let qx = (p.x >> 2) - l.first_noise_x;
                let qz = (p.z >> 2) - l.first_noise_z;
                let size = l.noise_size + 1;
                if qx >= 0 && qz >= 0 && qx < size && qz < size {
                    self.flat[slot as usize][(qx * size + qz) as usize]
                } else {
                    self.eval(input, p)
                }
            }
            // y-independent (checked at parse time), so caching by x/z is a no-op
            Node::Cache2D(input) => self.eval(input, p),
            Node::CacheOnce { input, slot } => {
                let s = slot as usize;
                let now = self.stamp.get();
                if self.once_stamp[s].get() == now {
                    self.once_value[s].get()
                } else {
                    let v = self.eval(input, p);
                    self.once_stamp[s].set(now);
                    self.once_value[s].set(v);
                    v
                }
            }
            // only reached with the empty blender; anything else stays on Java
            Node::BlendAlpha => 1.0,
            Node::BlendOffset => 0.0,
            Node::BlendDensity(input) => self.eval(input, p),
            Node::Beardifier => self.beard.compute(p.x, p.y, p.z),
        }
    }

    #[inline]
    fn interpolated(&self, input: u32, slot: u32, p: &Pt) -> f64 {
        let (cx, cy, cz) = match p.mode {
            // outside the chunk loop vanilla just evaluates the wrapped function;
            // Slice can't nest (rejected at parse time)
            Mode::Point | Mode::Slice => return self.eval(input, p),
            Mode::Cell { cx, cy, cz, .. } | Mode::Block { cx, cy, cz, .. } => (cx, cy, cz),
        };
        let c = &self.interp[slot as usize];
        let l = &self.layout;
        // NoiseInterpolator naming: noiseXYZ, slice0 = this x cell, slice1 = next
        let n000 = c[l.corner(cx, cz, cy)];
        let n001 = c[l.corner(cx, cz + 1, cy)];
        let n100 = c[l.corner(cx + 1, cz, cy)];
        let n101 = c[l.corner(cx + 1, cz + 1, cy)];
        let n010 = c[l.corner(cx, cz, cy + 1)];
        let n011 = c[l.corner(cx, cz + 1, cy + 1)];
        let n110 = c[l.corner(cx + 1, cz, cy + 1)];
        let n111 = c[l.corner(cx + 1, cz + 1, cy + 1)];
        match p.mode {
            Mode::Cell { fx, fy, fz, .. } => {
                // Mth.lerp3(x, y, z, n000, n100, n010, n110, n001, n101, n011, n111)
                let a = lerp(fy, lerp(fx, n000, n100), lerp(fx, n010, n110));
                let b = lerp(fy, lerp(fx, n001, n101), lerp(fx, n011, n111));
                lerp(fz, a, b)
            }
            Mode::Block { dx, dy, dz, .. } => {
                // updateForY, updateForX, updateForZ
                let xz00 = lerp(dy, n000, n010);
                let xz10 = lerp(dy, n100, n110);
                let xz01 = lerp(dy, n001, n011);
                let xz11 = lerp(dy, n101, n111);
                let z0 = lerp(dx, xz00, xz10);
                let z1 = lerp(dx, xz01, xz11);
                lerp(dz, z0, z1)
            }
            _ => unreachable!(),
        }
    }

    fn spline(&self, s: u32, p: &Pt) -> f32 {
        match &self.prog.splines[s as usize] {
            Spline::Const(v) => *v,
            Spline::Multi { coord, locations, values, derivatives } => {
                let f = self.eval(*coord, p) as f32;
                let i = find_interval_start(locations, f);
                let last = locations.len() as i32 - 1;
                if i < 0 {
                    linear_extend(f, locations, self.spline(values[0], p), derivatives, 0)
                } else if i == last {
                    let j = last as usize;
                    linear_extend(f, locations, self.spline(values[j], p), derivatives, j)
                } else {
                    let i = i as usize;
                    let f1 = locations[i];
                    let f2 = locations[i + 1];
                    let f3 = (f - f1) / (f2 - f1);
                    let f4 = derivatives[i];
                    let f5 = derivatives[i + 1];
                    let f6 = self.spline(values[i], p);
                    let f7 = self.spline(values[i + 1], p);
                    let f8 = f4 * (f2 - f1) - (f7 - f6);
                    let f9 = -f5 * (f2 - f1) + (f7 - f6);
                    lerp_f(f3, f6, f7) + f3 * (1.0 - f3) * lerp_f(f3, f8, f9)
                }
            }
        }
    }
}

/// Mth.binarySearch(0, len, i -> f < locations[i]) - 1
fn find_interval_start(locations: &[f32], f: f32) -> i32 {
    let mut lo = 0i32;
    let mut len = locations.len() as i32;
    while len > 0 {
        let half = len / 2;
        let mid = lo + half;
        if f < locations[mid as usize] {
            len = half;
        } else {
            lo = mid + 1;
            len -= half + 1;
        }
    }
    lo - 1
}

#[inline]
fn linear_extend(f: f32, locations: &[f32], value: f32, derivatives: &[f32], i: usize) -> f32 {
    let d = derivatives[i];
    if d == 0.0 { value } else { value + d * (f - locations[i]) }
}

#[inline(always)]
pub fn sample(n: Option<NormalRef>, x: f64, y: f64, z: f64) -> f64 {
    match n {
        Some(n) => noise::normal(n.first, n.second, n.value_factor, x, y, z),
        None => 0.0,
    }
}

#[inline(always)]
fn shift(n: Option<NormalRef>, x: f64, y: f64, z: f64) -> f64 {
    sample(n, x * 0.25, y * 0.25, z * 0.25) * 4.0
}

#[inline(always)]
fn mapped(kind: MapKind, v: f64) -> f64 {
    match kind {
        MapKind::Abs => v.abs(),
        MapKind::Square => v * v,
        MapKind::Cube => v * v * v,
        MapKind::HalfNegative => if v > 0.0 { v } else { v * 0.5 },
        MapKind::QuarterNegative => if v > 0.0 { v } else { v * 0.25 },
        MapKind::Squeeze => {
            let c = clamp(v, -1.0, 1.0);
            c / 2.0 - c * c * c / 24.0
        }
    }
}

fn rarity_3d(v: f64) -> f64 {
    if v < -0.5 {
        0.75
    } else if v < 0.0 {
        1.0
    } else if v < 0.5 {
        1.5
    } else {
        2.0
    }
}

fn rarity_2d(v: f64) -> f64 {
    if v < -0.75 {
        0.5
    } else if v < -0.5 {
        0.75
    } else if v < 0.5 {
        1.0
    } else if v < 0.75 {
        2.0
    } else {
        3.0
    }
}
