//! Native kernels for three GraphMaquina algorithms whose inputs and outputs are plain integers once the Java side
//! numbers its nodes or interns its lines: strongly connected components, elementary cycle enumeration and the
//! longest-common-subsequence alignment of `LineDiff`.
//!
//! The kernels in [`csr`], [`scc`], [`cycles`] and [`lcs`] are safe Rust over borrowed slices. Every `unsafe` block
//! lives in [`jni`], the boundary with the JVM.
//!
//! Allocation failures: the cycle listing, the only buffer whose size depends on the answer rather than the input,
//! and the copies of Java arrays grow fallibly, so a native heap that cannot hold them becomes a Java
//! `OutOfMemoryError`. Working memory proportional to the input (a few integers per node, or the alignment table
//! bounded at a million 16-bit cells) uses the standard infallible allocation, which aborts the process if the
//! native heap cannot hold it, as it would for any native library.

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
    /// The native heap could not hold the answer.
    OutOfMemory,
    /// The array meant for the answer is also one of the arrays the kernel reads.
    OutputAliasesInput,
}

impl KernelError {
    /// The message the Java exception carries, NUL-terminated for `ThrowNew`.
    pub fn message(self) -> &'static CStr {
        match self {
            KernelError::MalformedGraph(rule) => rule,
            KernelError::TableTooLarge => c"the alignment table exceeds a million cells",
            KernelError::OutputTooSmall => c"the output array is too small",
            KernelError::AnswerTooLarge => c"the answer has more entries than a Java array can hold",
            KernelError::OutOfMemory => c"the native heap cannot hold the answer",
            KernelError::OutputAliasesInput => c"the output array must not be one of the input arrays",
        }
    }

    /// The class of the Java exception: a refusal of the input is an `IllegalArgumentException`, an answer too large
    /// to hold an `OutOfMemoryError`, the error the library's own Java code would hit building that answer.
    pub fn java_class(self) -> &'static CStr {
        match self {
            KernelError::AnswerTooLarge | KernelError::OutOfMemory => c"java/lang/OutOfMemoryError",
            _ => c"java/lang/IllegalArgumentException",
        }
    }
}
