//! Back-to-front sorting of translucent quads.
//!
//! Vanilla does this in VertexSorting.byDistance: a float[] of squared
//! distances, then a boxed-comparator merge sort over an int[] of quad
//! indices, then six MemoryUtil.memPut calls per quad to write the index
//! buffer. Every camera move triggers this for each nearby section that has
//! water/glass in it, on the section compile threads.
//!
//! We do the same thing in one go: distances -> stable LSD radix sort ->
//! write indices straight into the ByteBufferBuilder memory.

use std::cell::RefCell;

thread_local! {
    static SCRATCH: RefCell<(Vec<u64>, Vec<u64>)> = const { RefCell::new((Vec::new(), Vec::new())) };
}

/// Maps a float to a u32 whose unsigned order matches Float.compare, which
/// is what vanilla's Floats.compare boils down to (NaN above everything,
/// -0.0 below 0.0).
#[inline(always)]
fn ordered_bits(f: f32) -> u32 {
    let bits = if f.is_nan() { 0x7fc0_0000 } else { f.to_bits() };
    if bits & 0x8000_0000 != 0 { !bits } else { bits | 0x8000_0000 }
}

/// Same expression JOML uses for Vector3f.distanceSquared with
/// joml.useMathFma off (the default): dx*dx + (dy*dy + dz*dz).
#[inline(always)]
fn distance_sq(cx: f32, cy: f32, cz: f32, x: f32, y: f32, z: f32) -> f32 {
    let dx = cx - x;
    let dy = cy - y;
    let dz = cz - z;
    dx * dx + (dy * dy + dz * dz)
}

/// Sorts quads far-to-near and returns their indices in `order`.
/// Ties keep their original order, same as the merge sort in vanilla.
pub fn sort_far_to_near(centroids: &[f32], camera: [f32; 3], order: &mut Vec<u32>) {
    let n = centroids.len() / 3;
    order.clear();

    SCRATCH.with(|cell| {
        let (ref mut a, ref mut b) = *cell.borrow_mut();
        a.clear();
        a.reserve(n);
        for (i, c) in centroids.chunks_exact(3).enumerate() {
            let d = distance_sq(camera[0], camera[1], camera[2], c[0], c[1], c[2]);
            // flip so that an ascending sort gives descending distance;
            // index goes in the low half so we can carry it through the sort
            let key = !ordered_bits(d) as u64;
            a.push(key << 32 | i as u64);
        }

        if n <= 48 {
            insertion_sort(a);
        } else {
            radix_sort(a, b);
        }

        order.extend(a.iter().map(|&v| v as u32));
    });
}

fn insertion_sort(v: &mut [u64]) {
    for i in 1..v.len() {
        let cur = v[i];
        let key = cur >> 32;
        let mut j = i;
        while j > 0 && (v[j - 1] >> 32) > key {
            v[j] = v[j - 1];
            j -= 1;
        }
        v[j] = cur;
    }
}

/// LSD radix on the upper 32 bits, 11 bits per pass. Stable by construction.
/// Passes where every element lands in the same bucket get skipped - with
/// squared distances inside one section the top bits are nearly always equal.
fn radix_sort(a: &mut Vec<u64>, b: &mut Vec<u64>) {
    const BITS: u32 = 11;
    const BUCKETS: usize = 1 << BITS;
    let n = a.len();
    b.clear();
    b.resize(n, 0);

    let mut counts = [[0u32; BUCKETS]; 3];
    for &v in a.iter() {
        let k = (v >> 32) as u32;
        counts[0][(k & 0x7ff) as usize] += 1;
        counts[1][((k >> 11) & 0x7ff) as usize] += 1;
        counts[2][(k >> 22) as usize] += 1;
    }

    for (pass, count) in counts.iter_mut().enumerate() {
        if count.iter().any(|&c| c as usize == n) {
            continue;
        }
        let mut sum = 0u32;
        for c in count.iter_mut() {
            let t = *c;
            *c = sum;
            sum += t;
        }
        let shift = 32 + pass as u32 * BITS;
        for &v in a.iter() {
            let bucket = ((v >> shift) & 0x7ff) as usize;
            b[count[bucket] as usize] = v;
            count[bucket] += 1;
        }
        std::mem::swap(a, b);
    }
}

thread_local! {
    static ORDER: RefCell<Vec<u32>> = const { RefCell::new(Vec::new()) };
}

/// Sorts `quads` centroids (xyz interleaved) and writes the resulting
/// triangle index buffer to `out` - 6 indices per quad, 2 or 4 bytes each.
/// Returns 0 on success, -1 on a bad index size.
///
/// # Safety
/// `centroids` must hold `quads * 3` floats and `out` must have room for
/// `quads * 6 * index_bytes` bytes. `out` does not need to be aligned.
#[no_mangle]
pub unsafe extern "C" fn oxidyn_sort_quads(
    centroids: *const f32,
    quads: u32,
    cx: f32,
    cy: f32,
    cz: f32,
    out: *mut u8,
    index_bytes: u32,
) -> i32 {
    if index_bytes != 2 && index_bytes != 4 {
        return -1;
    }
    let centroids = std::slice::from_raw_parts(centroids, quads as usize * 3);

    ORDER.with(|cell| {
        let order = &mut *cell.borrow_mut();
        sort_far_to_near(centroids, [cx, cy, cz], order);

        if index_bytes == 2 {
            let mut p = out as *mut u16;
            for &q in order.iter() {
                // (short) cast in vanilla, so plain truncation
                let b = (q * 4) as u16;
                for idx in [b, b + 1, b + 2, b + 2, b + 3, b] {
                    p.write_unaligned(idx);
                    p = p.add(1);
                }
            }
        } else {
            let mut p = out as *mut u32;
            for &q in order.iter() {
                let b = q * 4;
                for idx in [b, b + 1, b + 2, b + 2, b + 3, b] {
                    p.write_unaligned(idx);
                    p = p.add(1);
                }
            }
        }
    });
    0
}

#[cfg(test)]
mod tests {
    use super::*;

    // straight port of vanilla's approach, used as the reference
    fn reference(centroids: &[f32], cam: [f32; 3]) -> Vec<u32> {
        let d: Vec<f32> = centroids
            .chunks_exact(3)
            .map(|c| distance_sq(cam[0], cam[1], cam[2], c[0], c[1], c[2]))
            .collect();
        let mut idx: Vec<u32> = (0..d.len() as u32).collect();
        // slice::sort_by is stable, like fastutil's mergeSort
        idx.sort_by(|&a, &b| java_float_compare(d[b as usize], d[a as usize]));
        idx
    }

    fn java_float_compare(a: f32, b: f32) -> std::cmp::Ordering {
        ordered_bits(a).cmp(&ordered_bits(b))
    }

    fn lcg(seed: &mut u64) -> f32 {
        *seed = seed.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        ((*seed >> 40) as f32 / (1u64 << 24) as f32) * 16.0
    }

    #[test]
    fn matches_reference_on_random_input() {
        let mut seed = 42;
        for &n in &[0usize, 1, 7, 48, 49, 500, 4096] {
            let mut c = Vec::with_capacity(n * 3);
            for _ in 0..n * 3 {
                // snap to a grid so we actually get ties
                c.push((lcg(&mut seed) * 4.0).round() / 4.0);
            }
            let cam = [lcg(&mut seed), lcg(&mut seed), lcg(&mut seed)];
            let mut got = Vec::new();
            sort_far_to_near(&c, cam, &mut got);
            assert_eq!(got, reference(&c, cam), "n = {n}");
        }
    }

    #[test]
    fn float_order_matches_java() {
        assert!(ordered_bits(-0.0) < ordered_bits(0.0));
        assert!(ordered_bits(-1.0) < ordered_bits(-0.5));
        assert!(ordered_bits(f32::INFINITY) < ordered_bits(f32::NAN));
        assert!(ordered_bits(1.0) < ordered_bits(2.0));
    }

    #[test]
    fn writes_short_indices() {
        let c = [0.0f32, 0.0, 0.0, 10.0, 0.0, 0.0];
        let mut buf = [0u16; 12];
        let rc = unsafe { oxidyn_sort_quads(c.as_ptr(), 2, 0.0, 0.0, 0.0, buf.as_mut_ptr() as *mut u8, 2) };
        assert_eq!(rc, 0);
        // quad 1 is further away so it goes first
        assert_eq!(buf, [4, 5, 6, 6, 7, 4, 0, 1, 2, 2, 3, 0]);
    }
}
