//! Little helpers that behave exactly like their Java/Mth counterparts.
//! Mostly about NaN and signed zero, where Rust's std versions differ.

#[inline(always)]
pub fn java_min(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        return a;
    }
    if a == 0.0 && b == 0.0 && b.to_bits() == (-0.0f64).to_bits() {
        return b;
    }
    if a <= b { a } else { b }
}

#[inline(always)]
pub fn java_max(a: f64, b: f64) -> f64 {
    if a.is_nan() {
        return a;
    }
    if a == 0.0 && b == 0.0 && a.to_bits() == (-0.0f64).to_bits() {
        return b;
    }
    if a >= b { a } else { b }
}

/// Mth.clamp(double, double, double)
#[inline(always)]
pub fn clamp(v: f64, lo: f64, hi: f64) -> f64 {
    if v < lo { lo } else { java_min(v, hi) }
}

#[inline(always)]
pub fn lerp(t: f64, a: f64, b: f64) -> f64 {
    a + t * (b - a)
}

#[inline(always)]
pub fn lerp_f(t: f32, a: f32, b: f32) -> f32 {
    a + t * (b - a)
}

#[inline(always)]
pub fn inverse_lerp(v: f64, a: f64, b: f64) -> f64 {
    (v - a) / (b - a)
}

#[inline(always)]
pub fn clamped_lerp(a: f64, b: f64, t: f64) -> f64 {
    if t < 0.0 {
        a
    } else if t > 1.0 {
        b
    } else {
        lerp(t, a, b)
    }
}

/// Mth.clampedMap
#[inline(always)]
pub fn clamped_map(v: f64, a: f64, b: f64, c: f64, d: f64) -> f64 {
    clamped_lerp(c, d, inverse_lerp(v, a, b))
}

/// Mth.map
#[inline(always)]
pub fn map(v: f64, a: f64, b: f64, c: f64, d: f64) -> f64 {
    lerp(inverse_lerp(v, a, b), c, d)
}

/// Mth.floor(double)
#[inline(always)]
pub fn floor(v: f64) -> i32 {
    let i = v as i32;
    if v < i as f64 { i.wrapping_sub(1) } else { i }
}

#[inline(always)]
pub fn floor_div(a: i32, b: i32) -> i32 {
    a.div_euclid(b) - if b < 0 && a.rem_euclid(b) != 0 { 1 } else { 0 }
}

#[inline(always)]
pub fn length_sq(a: f64, b: f64, c: f64) -> f64 {
    a * a + b * b + c * c
}

/// Mth.fastInvSqrt(double) - the bit hack, with vanilla's magic constant
#[inline(always)]
pub fn fast_inv_sqrt(v: f64) -> f64 {
    let half = 0.5 * v;
    let i = 6910469410427058090i64.wrapping_sub((v.to_bits() as i64) >> 1);
    let y = f64::from_bits(i as u64);
    y * (1.5 - half * y * y)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn floor_div_matches_java() {
        for a in -40..40 {
            for b in [1, 3, 12, 16, -5] {
                let want = (a as f64 / b as f64).floor() as i32;
                assert_eq!(floor_div(a, b), want, "{a} / {b}");
            }
        }
    }

    #[test]
    fn min_max_signed_zero() {
        assert_eq!(java_min(0.0, -0.0).to_bits(), (-0.0f64).to_bits());
        assert_eq!(java_min(-0.0, 0.0).to_bits(), (-0.0f64).to_bits());
        assert_eq!(java_max(-0.0, 0.0).to_bits(), 0.0f64.to_bits());
        assert!(java_min(f64::NAN, 1.0).is_nan());
        assert!(java_min(1.0, f64::NAN).is_nan());
    }
}
