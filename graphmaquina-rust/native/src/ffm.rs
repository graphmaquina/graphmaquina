//! Plain C entry points for the Foreign Function and Memory API (JDK 22 and later), used only as a reference: the
//! library compiles for Java 21, where that API is a preview, so only the benchmarks call these. They measure the
//! kernels behind the cheapest boundary the JVM offers, a downcall that may pass a Java array in place.
//!
//! Each function returns its answer as a non-negative number, or one of the negative codes below. A panic is caught
//! and reported as [`PANICKED`]; nothing unwinds into the JVM.

use crate::KernelError;
use crate::csr::Csr;
use crate::{lcs, scc};
use std::panic::{AssertUnwindSafe, catch_unwind};

/// The CSR arrays do not describe a graph.
pub const MALFORMED_GRAPH: i64 = -1;
/// The alignment table would exceed a million cells.
pub const TABLE_TOO_LARGE: i64 = -2;
/// The output cannot hold the longest possible answer.
pub const OUTPUT_TOO_SMALL: i64 = -3;
/// The output overlaps one of the inputs.
pub const OUTPUT_ALIASES_INPUT: i64 = -4;
/// A kernel panicked.
pub const PANICKED: i64 = -5;
/// The native heap could not hold the answer.
pub const OUT_OF_MEMORY: i64 = -6;

fn code(error: KernelError) -> i64 {
    match error {
        KernelError::MalformedGraph(_) => MALFORMED_GRAPH,
        KernelError::TableTooLarge => TABLE_TOO_LARGE,
        KernelError::OutputTooSmall => OUTPUT_TOO_SMALL,
        KernelError::OutputAliasesInput => OUTPUT_ALIASES_INPUT,
        KernelError::AnswerTooLarge | KernelError::OutOfMemory => OUT_OF_MEMORY,
    }
}

/// Runs `body` and turns its refusal or panic into a code.
fn guarded(body: impl FnOnce() -> Result<usize, KernelError>) -> i64 {
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(Ok(answer)) => answer as i64,
        Ok(Err(error)) => code(error),
        Err(_) => PANICKED,
    }
}

/// Whether two non-empty memory ranges share a byte.
fn overlaps<A, B>(first: *const A, first_length: usize, second: *const B, second_length: usize) -> bool {
    let (first_start, second_start) = (first as usize, second as usize);
    let first_end = first_start + first_length * size_of::<A>();
    let second_end = second_start + second_length * size_of::<B>();
    first_length > 0 && second_length > 0 && first_start < second_end && second_start < first_end
}

/// The `length` elements at `data` as a slice; an empty slice for a length of 0, whatever the pointer.
///
/// # Safety
///
/// For a non-zero length, `data` must point at `length` initialized elements that nothing writes while the slice
/// lives.
unsafe fn borrow<'a, T>(data: *const T, length: usize) -> &'a [T] {
    if length == 0 {
        return &[];
    }
    // SAFETY: guaranteed by the caller, as stated above.
    unsafe { std::slice::from_raw_parts(data, length) }
}

/// As [`borrow`], for memory the kernel writes.
///
/// # Safety
///
/// For a non-zero length, `data` must point at `length` initialized elements that nothing else reads or writes while
/// the slice lives.
unsafe fn borrow_mut<'a, T>(data: *mut T, length: usize) -> &'a mut [T] {
    if length == 0 {
        return &mut [];
    }
    // SAFETY: guaranteed by the caller, as stated above.
    unsafe { std::slice::from_raw_parts_mut(data, length) }
}

/// Does nothing: the cost of the downcall alone.
#[unsafe(no_mangle)]
pub extern "C" fn gm_noop() {}

/// The wrapping sum of `length` ints at `values`: a downcall that reads one array.
///
/// # Safety
///
/// `values` must point at `length` readable, initialized `i32`s (or `length` must be 0), valid for the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gm_sum(values: *const i32, length: usize) -> i32 {
    // SAFETY: the caller guarantees `length` readable ints at `values`.
    let values = unsafe { borrow(values, length) };
    values.iter().fold(0i32, |sum, &value| sum.wrapping_add(value))
}

/// `scc::strong_components` over caller memory: returns the component count or a negative code.
///
/// # Safety
///
/// Each pointer must point at its length of initialized elements (or the length must be 0), valid for the call, and
/// no other thread may write them during it.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gm_strong_components(
    offsets: *const i32,
    offsets_length: usize,
    targets: *const i32,
    targets_length: usize,
    component: *mut i32,
    component_length: usize,
) -> i64 {
    if overlaps(component, component_length, offsets, offsets_length)
        || overlaps(component, component_length, targets, targets_length)
    {
        return OUTPUT_ALIASES_INPUT;
    }
    // SAFETY: the caller guarantees each pointer and length; the output overlaps neither input, checked above.
    let (offsets, targets, component) = unsafe {
        (
            borrow(offsets, offsets_length),
            borrow(targets, targets_length),
            borrow_mut(component, component_length),
        )
    };
    guarded(|| {
        let graph = Csr::new(offsets, targets)?;
        if component.len() < graph.nodes() {
            return Err(KernelError::OutputTooSmall);
        }
        Ok(scc::strong_components(&graph, component))
    })
}

/// `lcs::align` over caller memory: returns the step count or a negative code.
///
/// # Safety
///
/// As for [`gm_strong_components`].
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gm_align(
    left: *const i32,
    left_length: usize,
    right: *const i32,
    right_length: usize,
    kinds: *mut u8,
    kinds_length: usize,
) -> i64 {
    if overlaps(kinds, kinds_length, left, left_length) || overlaps(kinds, kinds_length, right, right_length) {
        return OUTPUT_ALIASES_INPUT;
    }
    // SAFETY: the caller guarantees each pointer and length; the output overlaps neither input, checked above.
    let (left, right, kinds) = unsafe {
        (
            borrow(left, left_length),
            borrow(right, right_length),
            borrow_mut(kinds, kinds_length),
        )
    };
    guarded(|| lcs::align(left, right, kinds))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn components(offsets: &[i32], targets: &[i32], component: &mut [i32]) -> i64 {
        // SAFETY: three live, distinct slices with their exact lengths.
        unsafe {
            gm_strong_components(
                offsets.as_ptr(),
                offsets.len(),
                targets.as_ptr(),
                targets.len(),
                component.as_mut_ptr(),
                component.len(),
            )
        }
    }

    #[test]
    fn components_come_back_numbered_by_their_first_node() {
        let mut component = [9; 4];
        assert_eq!(components(&[0, 1, 3, 4, 4], &[1, 0, 2, 3], &mut component), 3);
        assert_eq!(component, [0, 0, 1, 2]);
    }

    #[test]
    fn a_refused_graph_returns_its_code() {
        assert_eq!(components(&[0, 1], &[1], &mut [0; 1]), MALFORMED_GRAPH);
        assert_eq!(components(&[0, 0, 0], &[], &mut [0; 1]), OUTPUT_TOO_SMALL);
    }

    #[test]
    fn an_output_overlapping_an_input_is_refused() {
        let mut memory = [0, 1, 2, 1, 0];
        let base = memory.as_mut_ptr();
        // SAFETY: every pointer stays inside `memory`; the call must refuse before writing anything.
        let code = unsafe { gm_strong_components(base, 3, base.add(3), 2, base.add(3), 2) };
        assert_eq!(code, OUTPUT_ALIASES_INPUT);
        assert_eq!(memory, [0, 1, 2, 1, 0]);
    }

    #[test]
    fn the_sum_reads_the_array() {
        let values = [1, 2, 3];
        // SAFETY: a live slice and its length, then an empty one.
        unsafe {
            assert_eq!(gm_sum(values.as_ptr(), values.len()), 6);
            assert_eq!(gm_sum(std::ptr::null(), 0), 0);
        }
    }

    fn align(left: &[i32], right: &[i32], kinds: &mut [u8]) -> i64 {
        // SAFETY: three live, distinct slices with their exact lengths.
        unsafe {
            gm_align(
                left.as_ptr(),
                left.len(),
                right.as_ptr(),
                right.len(),
                kinds.as_mut_ptr(),
                kinds.len(),
            )
        }
    }

    #[test]
    fn an_alignment_writes_its_steps() {
        let mut kinds = [9u8; 5];
        assert_eq!(align(&[1, 2, 3], &[1, 3], &mut kinds), 3);
        assert_eq!(kinds[..3], [lcs::CONTEXT, lcs::DELETE, lcs::CONTEXT]);
    }

    #[test]
    fn a_refused_alignment_returns_its_code() {
        assert_eq!(align(&[0; 999], &[1; 1001], &mut [0; 2000]), TABLE_TOO_LARGE);
        assert_eq!(align(&[1, 2], &[3], &mut [0; 2]), OUTPUT_TOO_SMALL);
    }
}
