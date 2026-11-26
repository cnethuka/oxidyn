//! Native NoiseBasedChunkGenerator.doFill.
//!
//! Java compiles a dimension's noise router into a Program once, then calls
//! oxidyn_gen_fill per chunk and gets one byte per block back.

pub mod aquifer;
pub mod batch;
pub mod beard;
pub mod eval;
pub mod fill;
pub mod jmath;
pub mod program;
pub mod rng;

use program::Program;
use std::panic::{catch_unwind, AssertUnwindSafe};

/// Parses a program. On failure returns null and writes a positive reason
/// code (program::Reject) to `error`.
///
/// # Safety
/// `words` must hold `len` u64s, and any noise addresses in them must stay
/// valid until oxidyn_gen_free is called on the result.
#[no_mangle]
pub unsafe extern "C" fn oxidyn_gen_create(words: *const u64, len: u64, error: *mut i32) -> *mut Program {
    let words = std::slice::from_raw_parts(words, len as usize);
    match catch_unwind(|| Program::parse(words)) {
        Ok(Ok(p)) => Box::into_raw(Box::new(p)),
        Ok(Err(reason)) => {
            *error = reason as i32;
            std::ptr::null_mut()
        }
        Err(_) => {
            *error = 99;
            std::ptr::null_mut()
        }
    }
}

/// # Safety
/// `program` must come from oxidyn_gen_create and not be used afterwards.
#[no_mangle]
pub unsafe extern "C" fn oxidyn_gen_free(program: *mut Program) {
    if !program.is_null() {
        drop(Box::from_raw(program));
    }
}

/// Fills one chunk. Returns 0 on success, -1 on bad arguments, -2 if
/// something panicked (Java then falls back to vanilla for this chunk).
///
/// # Safety
/// All pointers must be valid for the sizes described by `params`; `kernel`
/// holds 24^3 floats and `out` holds `out_len` bytes.
#[no_mangle]
pub unsafe extern "C" fn oxidyn_gen_fill(
    program: *const Program,
    params: *const fill::FillParams,
    pieces: *const i32,
    junctions: *const i32,
    kernel: *const f32,
    out: *mut u8,
    out_len: u64,
) -> i32 {
    let (prog, p) = (&*program, &*params);
    if p.cell_w <= 0 || p.cell_h <= 0 || 16 % p.cell_w != 0 || p.height <= 0 {
        return -1;
    }
    let Some((n_pieces, n_junctions)) = fill::check_beard_lengths(p) else {
        return -1;
    };
    if out_len as usize != fill::output_len(p) {
        return -1;
    }
    let slice = |ptr: *const i32, n: usize| if n == 0 { &[][..] } else { std::slice::from_raw_parts(ptr, n) };
    let pieces = slice(pieces, n_pieces);
    let junctions = slice(junctions, n_junctions);
    let kernel = std::slice::from_raw_parts(kernel, 24 * 24 * 24);
    let out = std::slice::from_raw_parts_mut(out, out_len as usize);
    match catch_unwind(AssertUnwindSafe(|| fill::fill(prog, p, pieces, junctions, kernel, out))) {
        Ok(()) => 0,
        Err(_) => -2,
    }
}
