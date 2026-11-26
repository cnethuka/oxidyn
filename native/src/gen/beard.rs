//! Beardifier: the terrain adjustment around structures (villages sit on
//! flattened ground, ancient cities get carved out, ...). Pieces and
//! junctions come from the Java side, in the same order vanilla iterates
//! them, since the contributions are summed in that order.

use super::jmath::*;

pub const NONE: i32 = 0;
pub const BURY: i32 = 1;
pub const BEARD_THIN: i32 = 2;
pub const BEARD_BOX: i32 = 3;
pub const ENCAPSULATE: i32 = 4;

/// 8 ints per piece: min x/y/z, max x/y/z, adjustment, ground level delta
pub const PIECE_INTS: usize = 8;
/// 3 ints per junction: source x, source ground y, source z
pub const JUNCTION_INTS: usize = 3;

pub struct Beard<'a> {
    pub pieces: &'a [i32],
    pub junctions: &'a [i32],
    /// Beardifier.BEARD_KERNEL, copied from Java instead of recomputed so we
    /// don't depend on Math.pow and libm agreeing to the last bit
    pub kernel: &'a [f32],
}

impl Beard<'_> {
    pub fn is_empty(&self) -> bool {
        self.pieces.is_empty() && self.junctions.is_empty()
    }

    pub fn compute(&self, x: i32, y: i32, z: i32) -> f64 {
        let mut d = 0.0;
        for p in self.pieces.chunks_exact(PIECE_INTS) {
            let (min_x, min_y, min_z, max_x, max_y, max_z, adj, ground) = (p[0], p[1], p[2], p[3], p[4], p[5], p[6], p[7]);
            let dx = 0.max((min_x - x).max(x - max_x));
            let dz = 0.max((min_z - z).max(z - max_z));
            let base = min_y + ground;
            let rel = y - base;
            let dy = match adj {
                BURY | BEARD_THIN => rel,
                BEARD_BOX => 0.max((base - y).max(y - max_y)),
                ENCAPSULATE => 0.max((min_y - y).max(y - max_y)),
                _ => 0,
            };
            d += match adj {
                BURY => bury(dx as f64, dy as f64 / 2.0, dz as f64),
                BEARD_THIN | BEARD_BOX => self.beard(dx, dy, dz, rel) * 0.8,
                ENCAPSULATE => bury(dx as f64 / 2.0, dy as f64 / 2.0, dz as f64 / 2.0) * 0.8,
                _ => 0.0,
            };
        }
        for j in self.junctions.chunks_exact(JUNCTION_INTS) {
            let dx = x - j[0];
            let dy = y - j[1];
            let dz = z - j[2];
            d += self.beard(dx, dy, dz, dy) * 0.4;
        }
        d
    }

    #[inline]
    fn beard(&self, dx: i32, dy: i32, dz: i32, y_for_sign: i32) -> f64 {
        let i = dx + 12;
        let j = dy + 12;
        let k = dz + 12;
        let in_range = |v: i32| (0..24).contains(&v);
        if in_range(i) && in_range(j) && in_range(k) {
            let d0 = y_for_sign as f64 + 0.5;
            let d1 = length_sq(dx as f64, d0, dz as f64);
            let d2 = -d0 * fast_inv_sqrt(d1 / 2.0) / 2.0;
            d2 * self.kernel[(k * 24 * 24 + i * 24 + j) as usize] as f64
        } else {
            0.0
        }
    }
}

#[inline]
fn bury(a: f64, b: f64, c: f64) -> f64 {
    clamped_map(length_sq(a, b, c).sqrt(), 0.0, 6.0, 1.0, 0.0)
}
