//! Port of vanilla's ImprovedNoise / PerlinNoise / NormalNoise / BlendedNoise.
//!
//! The output has to match the Java code bit for bit, otherwise worlds
//! generated with and without the mod would differ. That means:
//!   - same operation order everywhere (no "simplifying" a*b*c into a*(b*c))
//!   - no fused multiply-add (rustc never contracts on its own, good)
//!   - Mth.floor semantics, which `as i32` gives us since it saturates and
//!     maps NaN to 0 exactly like a Java (int) cast
//!
//! The Java side flattens each noise into the #[repr(C)] structs below once,
//! and then hands us whole batches of coordinates.

/// One ImprovedNoise plus the per-octave factors PerlinNoise would apply.
/// `input_factor`/`value_factor` are precomputed on the Java side with the
/// exact same doubling/halving sequence vanilla uses.
#[repr(C)]
pub struct Octave {
    pub xo: f64,
    pub yo: f64,
    pub zo: f64,
    pub amplitude: f64,
    pub input_factor: f64,
    pub value_factor: f64,
    pub perm: [u8; 256],
}

/// NormalNoise header, followed in memory by `first_count + second_count`
/// octaves. Null octaves are dropped on the Java side since they contribute
/// nothing and the factors are already baked in.
#[repr(C)]
pub struct NormalHeader {
    pub value_factor: f64,
    pub first_count: u32,
    pub second_count: u32,
}

/// BlendedNoise header, followed by 8 main + 16 min + 16 max octaves in that
/// order (already in getOctaveNoise(i) order, i.e. reversed vs. the array).
/// Missing octaves are flagged in `present` - bit i for slot i.
#[repr(C)]
pub struct BlendedHeader {
    pub xz_multiplier: f64,
    pub y_multiplier: f64,
    pub xz_factor: f64,
    pub y_factor: f64,
    pub smear_scale_multiplier: f64,
    pub present: u64,
}

pub const BLENDED_MAIN: usize = 8;
pub const BLENDED_LIMIT: usize = 16;
pub const BLENDED_OCTAVES: usize = BLENDED_MAIN + 2 * BLENDED_LIMIT;

// SimplexNoise.GRADIENT - yes the last four are duplicates, that's vanilla.
const GRADIENT: [[f64; 3]; 16] = [
    [1.0, 1.0, 0.0],
    [-1.0, 1.0, 0.0],
    [1.0, -1.0, 0.0],
    [-1.0, -1.0, 0.0],
    [1.0, 0.0, 1.0],
    [-1.0, 0.0, 1.0],
    [1.0, 0.0, -1.0],
    [-1.0, 0.0, -1.0],
    [0.0, 1.0, 1.0],
    [0.0, -1.0, 1.0],
    [0.0, 1.0, -1.0],
    [0.0, -1.0, -1.0],
    [1.0, 1.0, 0.0],
    [0.0, -1.0, 1.0],
    [-1.0, 1.0, 0.0],
    [0.0, -1.0, -1.0],
];

// ImprovedNoise adds `1.0E-7F` - a float literal promoted to double, which is
// not the same number as 1.0e-7.
const SHIFT_UP_EPSILON: f64 = 1.0e-7f32 as f64;
const ROUND_OFF: f64 = 33554432.0;

#[inline(always)]
fn floor(v: f64) -> i32 {
    let i = v as i32;
    if v < i as f64 { i.wrapping_sub(1) } else { i }
}

#[inline(always)]
fn lfloor(v: f64) -> i64 {
    let i = v as i64;
    if v < i as f64 { i.wrapping_sub(1) } else { i }
}

#[inline(always)]
pub fn wrap(v: f64) -> f64 {
    v - lfloor(v / ROUND_OFF + 0.5) as f64 * ROUND_OFF
}

#[inline(always)]
fn lerp(t: f64, a: f64, b: f64) -> f64 {
    a + t * (b - a)
}

#[inline(always)]
fn lerp2(t1: f64, t2: f64, a: f64, b: f64, c: f64, d: f64) -> f64 {
    lerp(t2, lerp(t1, a, b), lerp(t1, c, d))
}

#[inline(always)]
#[allow(clippy::too_many_arguments)]
fn lerp3(t1: f64, t2: f64, t3: f64, a: f64, b: f64, c: f64, d: f64, e: f64, f: f64, g: f64, h: f64) -> f64 {
    lerp(t3, lerp2(t1, t2, a, b, c, d), lerp2(t1, t2, e, f, g, h))
}

#[inline(always)]
fn smoothstep(t: f64) -> f64 {
    t * t * t * (t * (t * 6.0 - 15.0) + 10.0)
}

#[inline(always)]
fn clamped_lerp(a: f64, b: f64, t: f64) -> f64 {
    if t < 0.0 {
        a
    } else if t > 1.0 {
        b
    } else {
        lerp(t, a, b)
    }
}

#[inline(always)]
fn grad_dot(hash: i32, x: f64, y: f64, z: f64) -> f64 {
    let g = &GRADIENT[(hash & 15) as usize];
    g[0] * x + g[1] * y + g[2] * z
}

impl Octave {
    #[inline(always)]
    fn p(&self, i: i32) -> i32 {
        self.perm[(i & 0xFF) as usize] as i32
    }

    /// ImprovedNoise.noise(x, y, z, yScale, yMax)
    #[inline(always)]
    pub fn noise(&self, x: f64, y: f64, z: f64, y_scale: f64, y_max: f64) -> f64 {
        let x = x + self.xo;
        let y = y + self.yo;
        let z = z + self.zo;
        let ix = floor(x);
        let iy = floor(y);
        let iz = floor(z);
        let fx = x - ix as f64;
        let fy = y - iy as f64;
        let fz = z - iz as f64;

        let shift = if y_scale != 0.0 {
            let clamp = if y_max >= 0.0 && y_max < fy { y_max } else { fy };
            floor(clamp / y_scale + SHIFT_UP_EPSILON) as f64 * y_scale
        } else {
            0.0
        };

        self.sample_and_lerp(ix, iy, iz, fx, fy - shift, fz, fy)
    }

    #[inline(always)]
    #[allow(clippy::too_many_arguments)]
    fn sample_and_lerp(&self, x: i32, y: i32, z: i32, fx: f64, fy: f64, fz: f64, fy_orig: f64) -> f64 {
        // only the low 8 bits survive p(), so wrapping adds are harmless
        let add = |a: i32, b: i32| a.wrapping_add(b);
        let i = self.p(x);
        let j = self.p(add(x, 1));
        let k = self.p(add(i, y));
        let l = self.p(add(add(i, y), 1));
        let i1 = self.p(add(j, y));
        let j1 = self.p(add(add(j, y), 1));
        let z1 = add(z, 1);

        let d0 = grad_dot(self.p(add(k, z)), fx, fy, fz);
        let d1 = grad_dot(self.p(add(i1, z)), fx - 1.0, fy, fz);
        let d2 = grad_dot(self.p(add(l, z)), fx, fy - 1.0, fz);
        let d3 = grad_dot(self.p(add(j1, z)), fx - 1.0, fy - 1.0, fz);
        let d4 = grad_dot(self.p(add(k, z1)), fx, fy, fz - 1.0);
        let d5 = grad_dot(self.p(add(i1, z1)), fx - 1.0, fy, fz - 1.0);
        let d6 = grad_dot(self.p(add(l, z1)), fx, fy - 1.0, fz - 1.0);
        let d7 = grad_dot(self.p(add(j1, z1)), fx - 1.0, fy - 1.0, fz - 1.0);

        lerp3(
            smoothstep(fx),
            smoothstep(fy_orig),
            smoothstep(fz),
            d0, d1, d2, d3, d4, d5, d6, d7,
        )
    }
}

/// PerlinNoise.getValue(x, y, z) - the plain three argument version, which
/// passes yScale = yMax = 0.
#[inline]
pub fn perlin(octaves: &[Octave], x: f64, y: f64, z: f64) -> f64 {
    let mut sum = 0.0;
    for o in octaves {
        let f = o.input_factor;
        let v = o.noise(wrap(x * f), wrap(y * f), wrap(z * f), 0.0, 0.0);
        sum += o.amplitude * v * o.value_factor;
    }
    sum
}

const NORMAL_INPUT_FACTOR: f64 = 1.0181268882175227;

#[inline]
pub fn normal(first: &[Octave], second: &[Octave], value_factor: f64, x: f64, y: f64, z: f64) -> f64 {
    let a = perlin(first, x, y, z);
    let b = perlin(second, x * NORMAL_INPUT_FACTOR, y * NORMAL_INPUT_FACTOR, z * NORMAL_INPUT_FACTOR);
    (a + b) * value_factor
}

/// BlendedNoise.compute. `x`/`y`/`z` are the raw block coordinates.
pub fn blended(h: &BlendedHeader, octaves: &[Octave], x: f64, y: f64, z: f64) -> f64 {
    let (main, rest) = octaves.split_at(BLENDED_MAIN);
    let (min_limit, max_limit) = rest.split_at(BLENDED_LIMIT);
    let present = |slot: usize| h.present & (1u64 << slot) != 0;

    let d0 = x * h.xz_multiplier;
    let d1 = y * h.y_multiplier;
    let d2 = z * h.xz_multiplier;
    let d3 = d0 / h.xz_factor;
    let d4 = d1 / h.y_factor;
    let d5 = d2 / h.xz_factor;
    let d6 = h.y_multiplier * h.smear_scale_multiplier;
    let d7 = d6 / h.y_factor;

    let mut main_sum = 0.0;
    let mut scale = 1.0;
    for (i, o) in main.iter().enumerate() {
        if present(i) {
            main_sum += o.noise(wrap(d3 * scale), wrap(d4 * scale), wrap(d5 * scale), d7 * scale, d4 * scale) / scale;
        }
        scale /= 2.0;
    }

    let t = (main_sum / 10.0 + 1.0) / 2.0;
    let skip_min = t >= 1.0;
    let skip_max = t <= 0.0;

    let mut min_sum = 0.0;
    let mut max_sum = 0.0;
    scale = 1.0;
    for j in 0..BLENDED_LIMIT {
        let sx = wrap(d0 * scale);
        let sy = wrap(d1 * scale);
        let sz = wrap(d2 * scale);
        let y_scale = d6 * scale;
        if !skip_min && present(BLENDED_MAIN + j) {
            min_sum += min_limit[j].noise(sx, sy, sz, y_scale, d1 * scale) / scale;
        }
        if !skip_max && present(BLENDED_MAIN + BLENDED_LIMIT + j) {
            max_sum += max_limit[j].noise(sx, sy, sz, y_scale, d1 * scale) / scale;
        }
        scale /= 2.0;
    }

    clamped_lerp(min_sum / 512.0, max_sum / 512.0, t) / 128.0
}

unsafe fn octaves_after<'a, H>(header: *const H, count: usize) -> &'a [Octave] {
    // Octaves start right after the header. Both headers are a multiple of 8
    // bytes, so the octave array stays aligned.
    let start = header.add(1) as *const Octave;
    std::slice::from_raw_parts(start, count)
}

/// Fills `out[i] = NormalNoise.getValue(xs[i], ys[i], zs[i])`.
///
/// # Safety
/// `desc` must point at a NormalHeader laid out as described above, and all
/// four arrays must hold at least `n` doubles.
#[no_mangle]
pub unsafe extern "C" fn oxidyn_noise_normal(
    desc: *const NormalHeader,
    xs: *const f64,
    ys: *const f64,
    zs: *const f64,
    out: *mut f64,
    n: u32,
) {
    let h = &*desc;
    let all = octaves_after(desc, (h.first_count + h.second_count) as usize);
    let (first, second) = all.split_at(h.first_count as usize);
    let n = n as usize;
    let xs = std::slice::from_raw_parts(xs, n);
    let ys = std::slice::from_raw_parts(ys, n);
    let zs = std::slice::from_raw_parts(zs, n);
    let out = std::slice::from_raw_parts_mut(out, n);
    for i in 0..n {
        out[i] = normal(first, second, h.value_factor, xs[i], ys[i], zs[i]);
    }
}

/// Fills `out[i] = BlendedNoise.compute(xs[i], ys[i], zs[i])`.
///
/// # Safety
/// Same deal as `oxidyn_noise_normal`, with a BlendedHeader followed by
/// exactly BLENDED_OCTAVES octaves.
#[no_mangle]
pub unsafe extern "C" fn oxidyn_noise_blended(
    desc: *const BlendedHeader,
    xs: *const f64,
    ys: *const f64,
    zs: *const f64,
    out: *mut f64,
    n: u32,
) {
    let h = &*desc;
    let octaves = octaves_after(desc, BLENDED_OCTAVES);
    let n = n as usize;
    let xs = std::slice::from_raw_parts(xs, n);
    let ys = std::slice::from_raw_parts(ys, n);
    let zs = std::slice::from_raw_parts(zs, n);
    let out = std::slice::from_raw_parts_mut(out, n);
    for i in 0..n {
        out[i] = blended(h, octaves, xs[i], ys[i], zs[i]);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn layout_matches_java_side() {
        // NativeLayouts.java hardcodes these, keep them in sync.
        assert_eq!(std::mem::size_of::<Octave>(), 304);
        assert_eq!(std::mem::size_of::<NormalHeader>(), 16);
        assert_eq!(std::mem::size_of::<BlendedHeader>(), 48);
    }

    #[test]
    fn floor_behaves_like_java() {
        assert_eq!(floor(-0.5), -1);
        assert_eq!(floor(-1.0), -1);
        assert_eq!(floor(2.9), 2);
        assert_eq!(floor(f64::NAN), 0);
        assert_eq!(floor(1e300), i32::MAX);
        // (int) saturates to MIN, then vanilla's `i - 1` wraps around
        assert_eq!(floor(-1e300), i32::MAX);
    }

    #[test]
    fn epsilon_is_the_float_one() {
        assert_eq!(SHIFT_UP_EPSILON.to_bits(), 1.0000000116860974e-7f64.to_bits());
    }

    #[test]
    fn wrap_keeps_small_values() {
        assert_eq!(wrap(123.25), 123.25);
        assert_eq!(wrap(-5.5), -5.5);
        assert_eq!(wrap(ROUND_OFF), 0.0);
    }
}
