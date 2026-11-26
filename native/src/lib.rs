//! Native half of Oxidyn. Everything exported here is called from Java via
//! the FFM linker, so keep the signatures boring: plain pointers, integers and
//! doubles, nothing that needs Rust's ABI.
//!
//! Bump ABI_VERSION whenever an export changes shape. The Java side refuses to
//! bind against a library that reports a different number.


pub const ABI_VERSION: u32 = 1;

#[no_mangle]
pub extern "C" fn oxidyn_abi_version() -> u32 {
    ABI_VERSION
}
