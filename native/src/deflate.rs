//! zlib streams through libdeflate.
//!
//! Minecraft uses zlib-wrapped deflate for region files (compression type 2)
//! and for every packet above the network compression threshold. Java's
//! Deflater/Inflater go through the JDK's bundled zlib, which is streaming
//! and comparatively slow. libdeflate is whole-buffer only, but both of these
//! call sites have the whole buffer anyway, and it's quite a bit faster at
//! the same level while producing ordinary zlib output.

use libdeflater::{CompressionLvl, Compressor, DecompressionError, Decompressor};
use std::cell::RefCell;
use std::panic::{catch_unwind, AssertUnwindSafe};

/// Return codes shared with the Java side (see NativeBackend).
pub const ERR_BAD_DATA: i64 = -1;
pub const ERR_NO_SPACE: i64 = -2;
pub const ERR_BAD_LEVEL: i64 = -3;
pub const ERR_PANIC: i64 = -4;

thread_local! {
    // Compressors are a few hundred KB each at the higher levels, so keep one
    // per thread and only rebuild it when someone asks for another level.
    static COMPRESSOR: RefCell<Option<(i32, Compressor)>> = const { RefCell::new(None) };
    static DECOMPRESSOR: RefCell<Option<Decompressor>> = const { RefCell::new(None) };
}

fn with_compressor<R>(level: i32, f: impl FnOnce(&mut Compressor) -> R) -> Option<R> {
    let lvl = CompressionLvl::new(level).ok()?;
    COMPRESSOR.with(|cell| {
        let mut slot = cell.borrow_mut();
        if !matches!(*slot, Some((l, _)) if l == level) {
            *slot = Some((level, Compressor::new(lvl)));
        }
        Some(f(&mut slot.as_mut().unwrap().1))
    })
}

unsafe fn slice<'a>(p: *const u8, len: usize) -> &'a [u8] {
    if len == 0 { &[] } else { std::slice::from_raw_parts(p, len) }
}

unsafe fn slice_mut<'a>(p: *mut u8, len: usize) -> &'a mut [u8] {
    if len == 0 { &mut [] } else { std::slice::from_raw_parts_mut(p, len) }
}

#[no_mangle]
pub extern "C" fn oxidyn_zlib_bound(len: u64) -> u64 {
    // libdeflate's bound doesn't depend on the level in practice, but the
    // API wants a compressor, so just use whatever this thread already has.
    with_compressor(6, |c| c.zlib_compress_bound(len as usize) as u64).unwrap_or(0)
}

/// Compresses `src` into `dst`. Returns the compressed size or a negative
/// ERR_* code.
///
/// # Safety
/// The pointers must be valid for their lengths, and must not overlap.
#[no_mangle]
pub unsafe extern "C" fn oxidyn_zlib_compress(level: i32, src: *const u8, src_len: u64, dst: *mut u8, dst_cap: u64) -> i64 {
    let src = slice(src, src_len as usize);
    let dst = slice_mut(dst, dst_cap as usize);
    let r = catch_unwind(AssertUnwindSafe(|| with_compressor(level, |c| c.zlib_compress(src, dst))));
    match r {
        Ok(Some(Ok(n))) => n as i64,
        Ok(Some(Err(_))) => ERR_NO_SPACE,
        Ok(None) => ERR_BAD_LEVEL,
        Err(_) => ERR_PANIC,
    }
}

/// Decompresses one zlib stream from `src` into `dst`. Returns the number of
/// bytes written, ERR_NO_SPACE if `dst` was too small (caller can grow and
/// retry), or ERR_BAD_DATA.
///
/// # Safety
/// The pointers must be valid for their lengths, and must not overlap.
#[no_mangle]
pub unsafe extern "C" fn oxidyn_zlib_decompress(src: *const u8, src_len: u64, dst: *mut u8, dst_cap: u64) -> i64 {
    let src = slice(src, src_len as usize);
    let dst = slice_mut(dst, dst_cap as usize);
    let r = catch_unwind(AssertUnwindSafe(|| {
        DECOMPRESSOR.with(|cell| {
            let mut slot = cell.borrow_mut();
            slot.get_or_insert_with(Decompressor::new).zlib_decompress(src, dst)
        })
    }));
    match r {
        Ok(Ok(n)) => n as i64,
        Ok(Err(DecompressionError::InsufficientSpace)) => ERR_NO_SPACE,
        Ok(Err(DecompressionError::BadData)) => ERR_BAD_DATA,
        Err(_) => ERR_PANIC,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip() {
        let data: Vec<u8> = (0..100_000u32).map(|i| (i % 251) as u8 ^ (i >> 9) as u8).collect();
        let mut comp = vec![0u8; oxidyn_zlib_bound(data.len() as u64) as usize];
        let n = unsafe { oxidyn_zlib_compress(6, data.as_ptr(), data.len() as u64, comp.as_mut_ptr(), comp.len() as u64) };
        assert!(n > 0);
        // zlib header, deflate + 32K window
        assert_eq!(comp[0], 0x78);

        let mut out = vec![0u8; data.len()];
        let m = unsafe { oxidyn_zlib_decompress(comp.as_ptr(), n as u64, out.as_mut_ptr(), out.len() as u64) };
        assert_eq!(m as usize, data.len());
        assert_eq!(out, data);

        let mut small = vec![0u8; 10];
        let e = unsafe { oxidyn_zlib_decompress(comp.as_ptr(), n as u64, small.as_mut_ptr(), small.len() as u64) };
        assert_eq!(e, ERR_NO_SPACE);
    }

    #[test]
    fn rejects_garbage_and_bad_levels() {
        let junk = [1u8, 2, 3, 4, 5];
        let mut out = [0u8; 64];
        let e = unsafe { oxidyn_zlib_decompress(junk.as_ptr(), 5, out.as_mut_ptr(), 64) };
        assert_eq!(e, ERR_BAD_DATA);
        let e = unsafe { oxidyn_zlib_compress(99, junk.as_ptr(), 5, out.as_mut_ptr(), 64) };
        assert_eq!(e, ERR_BAD_LEVEL);
    }

    #[test]
    fn empty_input() {
        let mut comp = [0u8; 64];
        let n = unsafe { oxidyn_zlib_compress(6, std::ptr::null(), 0, comp.as_mut_ptr(), 64) };
        assert!(n > 0);
        let m = unsafe { oxidyn_zlib_decompress(comp.as_ptr(), n as u64, std::ptr::null_mut(), 0) };
        assert_eq!(m, 0);
    }
}
