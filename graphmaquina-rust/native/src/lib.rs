//! Native kernels for three GraphMaquina algorithms whose inputs and outputs are plain integers once the Java side
//! numbers its nodes or interns its lines: strongly connected components, elementary cycle enumeration and the
//! longest-common-subsequence alignment of `LineDiff`.
//!
//! The kernels in [`csr`], [`scc`], [`cycles`] and [`lcs`] are safe Rust over borrowed slices. Every `unsafe` block
//! lives in [`jni`], the boundary with the JVM.

pub mod csr;
pub mod cycles;
pub mod jni;
pub mod lcs;
pub mod scc;

use std::ffi::CStr;

/// Why a kernel refused its input. The JNI layer turns each one into a Java exception.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum KernelError {
    /// The CSR arrays do not describe a graph; the text says which rule they break.
    MalformedGraph(&'static CStr),
    /// The alignment table would exceed [`lcs::MAX_CELLS`].
    TableTooLarge,
    /// The output array cannot hold the longest possible result.
    OutputTooSmall,
    /// The answer has more entries than a Java array can hold.
    AnswerTooLarge,
}

impl KernelError {
    /// The message the Java exception carries, NUL-terminated for `ThrowNew`.
    pub fn message(self) -> &'static CStr {
        match self {
            KernelError::MalformedGraph(rule) => rule,
            KernelError::TableTooLarge => c"the alignment table exceeds a million cells",
            KernelError::OutputTooSmall => c"the output array is too small",
            KernelError::AnswerTooLarge => c"the answer has more entries than a Java array can hold",
        }
    }
}
