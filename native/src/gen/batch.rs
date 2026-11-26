//! Array-at-a-time evaluation of a Program.
//!
//! The scalar evaluator walks the whole graph once per point, which is all
//! pointer chasing and branches. Here each node runs over every point of a
//! batch in one go, and noise gets sampled four points at a time.
//!
//! Vanilla's lazy bits are kept with selection vectors: a range_choice branch,
//! the second operand of mul/min/max, or a spline segment is only evaluated
//! on the points that actually need it. Per point, the arithmetic is exactly
//! what the scalar path does, so results don't change.

use super::eval::{Eval, Mode, Pt};
use super::jmath::*;
use super::program::*;
use crate::noise;

/// A set of points sharing one evaluation mode.
pub struct Batch {
    pub x: Vec<i32>,
    pub y: Vec<i32>,
    pub z: Vec<i32>,
    pub kind: BatchKind,
}

pub enum BatchKind {
    Slice,
    /// one column of cells (fixed cx, cz); per point the cell y and fractions
    Cell { cx: u32, cz: u32, cy: Vec<u32>, fx: Vec<f64>, fy: Vec<f64>, fz: Vec<f64> },
}

impl Batch {
    fn mode(&self, i: usize) -> Mode {
        match &self.kind {
            BatchKind::Slice => Mode::Slice,
            BatchKind::Cell { cx, cz, cy, fx, fy, fz } => Mode::Cell { cx: *cx, cy: cy[i], cz: *cz, fx: fx[i], fy: fy[i], fz: fz[i] },
        }
    }

    fn pt(&self, i: usize) -> Pt {
        Pt { x: self.x[i], y: self.y[i], z: self.z[i], mode: self.mode(i) }
    }
}

/// Per-batch CacheOnce results.
pub struct Memo {
    once: Vec<Option<(Vec<f64>, Vec<bool>)>>,
}

impl Memo {
    pub fn new(prog: &Program) -> Self {
        Memo { once: (0..prog.once_slots).map(|_| None).collect() }
    }
}

impl Eval<'_> {
    /// out[k] = value of `id` at point sel[k]
    pub fn eval_batch(&self, id: u32, b: &Batch, sel: &[u32], out: &mut [f64], memo: &mut Memo) {
        debug_assert_eq!(sel.len(), out.len());
        if sel.is_empty() {
            return;
        }
        match self.prog.nodes[id as usize] {
            Node::Const(v) => out.fill(v),
            Node::Add(a, c) => {
                self.eval_batch(a, b, sel, out, memo);
                let mut t = vec![0.0; sel.len()];
                self.eval_batch(c, b, sel, &mut t, memo);
                for (o, v) in out.iter_mut().zip(&t) {
                    *o += v;
                }
            }
            Node::Mul(a, c) => {
                self.eval_batch(a, b, sel, out, memo);
                // second operand only where the first isn't zero
                self.lazy_second(c, b, sel, out, memo, |d| d != 0.0, |d, v| d * v, |_| 0.0);
            }
            Node::Min(a, c, c_min) => {
                self.eval_batch(a, b, sel, out, memo);
                self.lazy_second(c, b, sel, out, memo, |d| !(d < c_min), java_min, |d| d);
            }
            Node::Max(a, c, c_max) => {
                self.eval_batch(a, b, sel, out, memo);
                self.lazy_second(c, b, sel, out, memo, |d| !(d > c_max), java_max, |d| d);
            }
            Node::AddConst(a, c) => {
                self.eval_batch(a, b, sel, out, memo);
                out.iter_mut().for_each(|o| *o += c);
            }
            Node::MulConst(a, c) => {
                self.eval_batch(a, b, sel, out, memo);
                out.iter_mut().for_each(|o| *o *= c);
            }
            Node::Clamp(a, lo, hi) => {
                self.eval_batch(a, b, sel, out, memo);
                out.iter_mut().for_each(|o| *o = clamp(*o, lo, hi));
            }
            Node::Mapped(..) | Node::YGradient { .. } | Node::Beardifier | Node::BlendAlpha | Node::BlendOffset => {
                // cheap per-point things: reuse the scalar code
                if let Node::Mapped(a, _) = self.prog.nodes[id as usize] {
                    self.eval_batch(a, b, sel, out, memo);
                    let Node::Mapped(_, kind) = self.prog.nodes[id as usize] else { unreachable!() };
                    out.iter_mut().for_each(|o| *o = super::eval::mapped_pub(kind, *o));
                } else {
                    for (k, &i) in sel.iter().enumerate() {
                        out[k] = self.eval(id, &b.pt(i as usize));
                    }
                }
            }
            Node::Range { input, lo, hi, when_in, when_out } => {
                let mut d = vec![0.0; sel.len()];
                self.eval_batch(input, b, sel, &mut d, memo);
                let (mut sel_in, mut pos_in, mut sel_out, mut pos_out) = (Vec::new(), Vec::new(), Vec::new(), Vec::new());
                for (k, &v) in d.iter().enumerate() {
                    if v >= lo && v < hi {
                        sel_in.push(sel[k]);
                        pos_in.push(k);
                    } else {
                        sel_out.push(sel[k]);
                        pos_out.push(k);
                    }
                }
                self.scatter(when_in, b, &sel_in, &pos_in, out, memo);
                self.scatter(when_out, b, &sel_out, &pos_out, out, memo);
            }
            Node::Noise { noise, xz, y } => {
                let xs: Vec<f64> = sel.iter().map(|&i| b.x[i as usize] as f64 * xz).collect();
                let ys: Vec<f64> = sel.iter().map(|&i| b.y[i as usize] as f64 * y).collect();
                let zs: Vec<f64> = sel.iter().map(|&i| b.z[i as usize] as f64 * xz).collect();
                normal_batch(noise, &xs, &ys, &zs, out);
            }
            Node::Shifted { sx, sy, sz, xz, y, noise } => {
                let mut xs = vec![0.0; sel.len()];
                let mut ys = vec![0.0; sel.len()];
                let mut zs = vec![0.0; sel.len()];
                self.eval_batch(sx, b, sel, &mut xs, memo);
                self.eval_batch(sy, b, sel, &mut ys, memo);
                self.eval_batch(sz, b, sel, &mut zs, memo);
                for (k, &i) in sel.iter().enumerate() {
                    let i = i as usize;
                    xs[k] = b.x[i] as f64 * xz + xs[k];
                    ys[k] = b.y[i] as f64 * y + ys[k];
                    zs[k] = b.z[i] as f64 * xz + zs[k];
                }
                normal_batch(noise, &xs, &ys, &zs, out);
            }
            Node::ShiftA(n) => {
                let xs: Vec<f64> = sel.iter().map(|&i| b.x[i as usize] as f64 * 0.25).collect();
                let ys = vec![0.0 * 0.25; sel.len()];
                let zs: Vec<f64> = sel.iter().map(|&i| b.z[i as usize] as f64 * 0.25).collect();
                normal_batch(n, &xs, &ys, &zs, out);
                out.iter_mut().for_each(|o| *o *= 4.0);
            }
            Node::ShiftB(n) => {
                let xs: Vec<f64> = sel.iter().map(|&i| b.z[i as usize] as f64 * 0.25).collect();
                let ys: Vec<f64> = sel.iter().map(|&i| b.x[i as usize] as f64 * 0.25).collect();
                let zs = vec![0.0 * 0.25; sel.len()];
                normal_batch(n, &xs, &ys, &zs, out);
                out.iter_mut().for_each(|o| *o *= 4.0);
            }
            Node::Shift(n) => {
                let xs: Vec<f64> = sel.iter().map(|&i| b.x[i as usize] as f64 * 0.25).collect();
                let ys: Vec<f64> = sel.iter().map(|&i| b.y[i as usize] as f64 * 0.25).collect();
                let zs: Vec<f64> = sel.iter().map(|&i| b.z[i as usize] as f64 * 0.25).collect();
                normal_batch(n, &xs, &ys, &zs, out);
                out.iter_mut().for_each(|o| *o *= 4.0);
            }
            Node::Blended(bl) => {
                let n = sel.len();
                let full = n - n % noise::LANES;
                for k in (0..full).step_by(noise::LANES) {
                    let g = |c: &Vec<i32>| -> [f64; noise::LANES] { std::array::from_fn(|l| c[sel[k + l] as usize] as f64) };
                    let v = noise::blended_x4(bl.header, bl.octaves, g(&b.x), g(&b.y), g(&b.z));
                    out[k..k + noise::LANES].copy_from_slice(&v);
                }
                for k in full..n {
                    let i = sel[k] as usize;
                    out[k] = noise::blended(bl.header, bl.octaves, b.x[i] as f64, b.y[i] as f64, b.z[i] as f64);
                }
            }
            Node::Weird { input, noise, kind } => {
                let mut r = vec![0.0; sel.len()];
                self.eval_batch(input, b, sel, &mut r, memo);
                for v in r.iter_mut() {
                    *v = super::eval::rarity_pub(kind, *v);
                }
                let xs: Vec<f64> = sel.iter().zip(&r).map(|(&i, &d)| b.x[i as usize] as f64 / d).collect();
                let ys: Vec<f64> = sel.iter().zip(&r).map(|(&i, &d)| b.y[i as usize] as f64 / d).collect();
                let zs: Vec<f64> = sel.iter().zip(&r).map(|(&i, &d)| b.z[i as usize] as f64 / d).collect();
                normal_batch(noise, &xs, &ys, &zs, out);
                for (o, d) in out.iter_mut().zip(&r) {
                    *o = d * o.abs();
                }
            }
            Node::Spline(s) => {
                let mut f = vec![0.0f32; sel.len()];
                self.spline_batch(s, b, sel, &mut f, memo);
                for (o, v) in out.iter_mut().zip(&f) {
                    *o = *v as f64;
                }
            }
            Node::Interpolated { input, slot } => match &b.kind {
                // can't nest (checked at parse time), so this is plain evaluation
                BatchKind::Slice => self.eval_batch(input, b, sel, out, memo),
                BatchKind::Cell { cx, cz, cy, fx, fy, fz } => {
                    let c = &self.interp[slot as usize];
                    let l = &self.layout;
                    for (k, &i) in sel.iter().enumerate() {
                        let i = i as usize;
                        let y = cy[i];
                        let n000 = c[l.corner(*cx, *cz, y)];
                        let n001 = c[l.corner(*cx, cz + 1, y)];
                        let n100 = c[l.corner(cx + 1, *cz, y)];
                        let n101 = c[l.corner(cx + 1, cz + 1, y)];
                        let n010 = c[l.corner(*cx, *cz, y + 1)];
                        let n011 = c[l.corner(*cx, cz + 1, y + 1)];
                        let n110 = c[l.corner(cx + 1, *cz, y + 1)];
                        let n111 = c[l.corner(cx + 1, cz + 1, y + 1)];
                        let (x, yy, z) = (fx[i], fy[i], fz[i]);
                        let a = lerp(yy, lerp(x, n000, n100), lerp(x, n010, n110));
                        let bb = lerp(yy, lerp(x, n001, n101), lerp(x, n011, n111));
                        out[k] = lerp(z, a, bb);
                    }
                }
            },
            Node::FlatCache { input, slot } => {
                let l = &self.layout;
                let size = l.noise_size + 1;
                let grid = &self.flat[slot as usize];
                let (mut miss, mut pos) = (Vec::new(), Vec::new());
                for (k, &i) in sel.iter().enumerate() {
                    let qx = (b.x[i as usize] >> 2) - l.first_noise_x;
                    let qz = (b.z[i as usize] >> 2) - l.first_noise_z;
                    if qx >= 0 && qz >= 0 && qx < size && qz < size {
                        out[k] = grid[(qx * size + qz) as usize];
                    } else {
                        miss.push(i);
                        pos.push(k);
                    }
                }
                self.scatter(input, b, &miss, &pos, out, memo);
            }
            Node::Cache2D { input, .. } => {
                // y-independent: evaluate once per run of equal (x, z)
                let (mut heads, mut pos) = (Vec::new(), Vec::new());
                let mut last = None;
                for (k, &i) in sel.iter().enumerate() {
                    let key = (b.x[i as usize], b.z[i as usize]);
                    if last != Some(key) {
                        heads.push(i);
                        pos.push(k);
                        last = Some(key);
                    }
                }
                let mut v = vec![0.0; heads.len()];
                self.eval_batch(input, b, &heads, &mut v, memo);
                let mut h = 0;
                for k in 0..sel.len() {
                    if h + 1 < pos.len() && pos[h + 1] == k {
                        h += 1;
                    }
                    out[k] = v[h];
                }
            }
            Node::CacheOnce { input, slot } => {
                let s = slot as usize;
                let len = b.x.len();
                let (mut need, mut pos) = (Vec::new(), Vec::new());
                {
                    let (_, done) = memo.once[s].get_or_insert_with(|| (vec![0.0; len], vec![false; len]));
                    for (k, &i) in sel.iter().enumerate() {
                        if !done[i as usize] {
                            need.push(i);
                            pos.push(k);
                        }
                    }
                }
                if !need.is_empty() {
                    let mut v = vec![0.0; need.len()];
                    self.eval_batch(input, b, &need, &mut v, memo);
                    let (vals, done) = memo.once[s].as_mut().unwrap();
                    for (j, &i) in need.iter().enumerate() {
                        vals[i as usize] = v[j];
                        done[i as usize] = true;
                    }
                }
                let (vals, _) = memo.once[s].as_ref().unwrap();
                for (k, &i) in sel.iter().enumerate() {
                    out[k] = vals[i as usize];
                }
            }
            Node::BlendDensity(input) => self.eval_batch(input, b, sel, out, memo),
        }
    }

    /// Evaluates `id` on a subset and writes the results to the given positions.
    fn scatter(&self, id: u32, b: &Batch, sub: &[u32], pos: &[usize], out: &mut [f64], memo: &mut Memo) {
        if sub.is_empty() {
            return;
        }
        let mut v = vec![0.0; sub.len()];
        self.eval_batch(id, b, sub, &mut v, memo);
        for (j, &k) in pos.iter().enumerate() {
            out[k] = v[j];
        }
    }

    /// The mul/min/max pattern: out holds the first operand; where `need`
    /// says so, evaluate the second and `combine`, elsewhere `skip`.
    #[allow(clippy::too_many_arguments)]
    fn lazy_second(
        &self,
        second: u32,
        b: &Batch,
        sel: &[u32],
        out: &mut [f64],
        memo: &mut Memo,
        need: impl Fn(f64) -> bool,
        combine: impl Fn(f64, f64) -> f64,
        skip: impl Fn(f64) -> f64,
    ) {
        let (mut sub, mut pos) = (Vec::new(), Vec::new());
        for (k, &d) in out.iter().enumerate() {
            if need(d) {
                sub.push(sel[k]);
                pos.push(k);
            }
        }
        let mut v = vec![0.0; sub.len()];
        self.eval_batch(second, b, &sub, &mut v, memo);
        let mut j = 0;
        for k in 0..out.len() {
            if j < pos.len() && pos[j] == k {
                out[k] = combine(out[k], v[j]);
                j += 1;
            } else {
                out[k] = skip(out[k]);
            }
        }
    }

    fn spline_batch(&self, s: u32, b: &Batch, sel: &[u32], out: &mut [f32], memo: &mut Memo) {
        if sel.is_empty() {
            return;
        }
        match &self.prog.splines[s as usize] {
            Spline::Const(v) => out.fill(*v),
            Spline::Multi { coord, locations, values, derivatives } => {
                let mut c = vec![0.0; sel.len()];
                self.eval_batch(*coord, b, sel, &mut c, memo);
                let f: Vec<f32> = c.iter().map(|v| *v as f32).collect();
                let last = locations.len() - 1;
                let interval: Vec<i32> = f.iter().map(|&v| super::eval::find_interval_start_pub(locations, v)).collect();

                // which child splines each point needs: one at the ends, two in between
                let mut need_sel = vec![Vec::new(); values.len()];
                let mut need_pos = vec![Vec::new(); values.len()];
                for (k, &i) in interval.iter().enumerate() {
                    let (lo, hi) = if i < 0 { (0, None) } else if i as usize == last { (last, None) } else { (i as usize, Some(i as usize + 1)) };
                    need_sel[lo].push(sel[k]);
                    need_pos[lo].push(k);
                    if let Some(hi) = hi {
                        need_sel[hi].push(sel[k]);
                        need_pos[hi].push(k);
                    }
                }
                // child results, looked up by (child, position in sel)
                let mut child_vals: Vec<Vec<f32>> = Vec::with_capacity(values.len());
                for (ci, &child) in values.iter().enumerate() {
                    let mut v = vec![0.0f32; need_sel[ci].len()];
                    self.spline_batch(child, b, &need_sel[ci], &mut v, memo);
                    child_vals.push(v);
                }
                let mut cursor = vec![0usize; values.len()];
                let mut take = |ci: usize, k: usize| -> f32 {
                    let j = cursor[ci];
                    debug_assert_eq!(need_pos[ci][j], k);
                    cursor[ci] += 1;
                    child_vals[ci][j]
                };
                for k in 0..sel.len() {
                    let i = interval[k];
                    let fv = f[k];
                    out[k] = if i < 0 {
                        let v = take(0, k);
                        super::eval::linear_extend_pub(fv, locations, v, derivatives, 0)
                    } else if i as usize == last {
                        let v = take(last, k);
                        super::eval::linear_extend_pub(fv, locations, v, derivatives, last)
                    } else {
                        let i = i as usize;
                        let f6 = take(i, k);
                        let f7 = take(i + 1, k);
                        let f1 = locations[i];
                        let f2 = locations[i + 1];
                        let f3 = (fv - f1) / (f2 - f1);
                        let f8 = derivatives[i] * (f2 - f1) - (f7 - f6);
                        let f9 = -derivatives[i + 1] * (f2 - f1) + (f7 - f6);
                        lerp_f(f3, f6, f7) + f3 * (1.0 - f3) * lerp_f(f3, f8, f9)
                    };
                }
            }
        }
    }
}

/// NormalNoise over arrays, four points at a time.
fn normal_batch(n: Option<NormalRef>, xs: &[f64], ys: &[f64], zs: &[f64], out: &mut [f64]) {
    let Some(n) = n else {
        out.fill(0.0);
        return;
    };
    let len = out.len();
    let full = len - len % noise::LANES;
    for k in (0..full).step_by(noise::LANES) {
        let g = |a: &[f64]| -> [f64; noise::LANES] { std::array::from_fn(|l| a[k + l]) };
        let v = noise::normal_x4(n.first, n.second, n.value_factor, g(xs), g(ys), g(zs));
        out[k..k + noise::LANES].copy_from_slice(&v);
    }
    for k in full..len {
        out[k] = noise::normal(n.first, n.second, n.value_factor, xs[k], ys[k], zs[k]);
    }
}
