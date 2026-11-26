//! Xoroshiro128++ and the positional factory, as used for aquifers and ore veins.

pub struct Xoroshiro {
    lo: i64,
    hi: i64,
}

impl Xoroshiro {
    pub fn new(lo: i64, hi: i64) -> Self {
        if (lo | hi) == 0 {
            Xoroshiro { lo: -7046029254386353131, hi: 7640891576956012809 }
        } else {
            Xoroshiro { lo, hi }
        }
    }

    #[inline(always)]
    pub fn next_long(&mut self) -> i64 {
        let i = self.lo;
        let mut j = self.hi;
        let k = i.wrapping_add(j).rotate_left(17).wrapping_add(i);
        j ^= i;
        self.lo = i.rotate_left(49) ^ j ^ (j << 21);
        self.hi = j.rotate_left(28);
        k
    }

    #[inline(always)]
    fn next_int(&mut self) -> i32 {
        self.next_long() as i32
    }

    /// XoroshiroRandomSource.nextInt(bound), Lemire style with rejection
    pub fn next_int_bounded(&mut self, bound: i32) -> i32 {
        let bound64 = bound as i64;
        let mut i = self.next_int() as u32 as i64;
        let mut j = i * bound64;
        let mut k = j & 0xFFFF_FFFF;
        if k < bound64 {
            let l = ((!bound).wrapping_add(1) as u32 % bound as u32) as i64;
            while k < l {
                i = self.next_int() as u32 as i64;
                j = i * bound64;
                k = j & 0xFFFF_FFFF;
            }
        }
        (j >> 32) as i32
    }

    #[inline(always)]
    pub fn next_float(&mut self) -> f32 {
        ((self.next_long() as u64) >> 40) as f32 * 5.9604645E-8f32
    }
}

/// Mth.getSeed - note the first multiply is a 32-bit int multiply in Java
#[inline(always)]
pub fn get_seed(x: i32, y: i32, z: i32) -> i64 {
    let mut i = (x.wrapping_mul(3129871) as i64) ^ (z as i64).wrapping_mul(116129781) ^ (y as i64);
    i = i.wrapping_mul(i).wrapping_mul(42317861).wrapping_add(i.wrapping_mul(11));
    i >> 16
}

#[derive(Clone, Copy)]
pub struct Positional {
    pub lo: i64,
    pub hi: i64,
}

impl Positional {
    #[inline(always)]
    pub fn at(&self, x: i32, y: i32, z: i32) -> Xoroshiro {
        Xoroshiro::new(get_seed(x, y, z) ^ self.lo, self.hi)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bounded_ints_stay_in_range() {
        let mut r = Xoroshiro::new(12345, 678);
        for _ in 0..10000 {
            let v = r.next_int_bounded(10);
            assert!((0..10).contains(&v));
        }
        let f = r.next_float();
        assert!((0.0..1.0).contains(&f));
    }
}
