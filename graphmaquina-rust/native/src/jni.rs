//! JNI entry points for `io.github.graphmaquina.rust.RustKernels`, and two plain C entry points for measuring the
//! cost of a Foreign Function and Memory API downcall against a JNI call.
//!
//! Rules every entry point keeps:
//!
//! - Java arrays are borrowed for the length of the call and never retained. Short kernels read them in place
//!   through `GetPrimitiveArrayCritical`; [`Critical`] releases each one when it goes out of scope, on every path,
//!   unwinding included. The cycle enumeration can run for a long time, so it copies its two small inputs out
//!   instead of holding a critical region (which can hold off the garbage collector) for the whole search.
//! - No other JNI function is called while a critical region is open: lengths are read before, exceptions are
//!   thrown after.
//! - A panic never crosses into the JVM: [`entry`] catches it and throws `IllegalStateException` instead.
//! - A null array becomes `NullPointerException`, a kernel refusal `IllegalArgumentException`.

use crate::KernelError;
use crate::csr::Csr;
use crate::{cycles, lcs, scc};
use jni_sys::{JNI_ABORT, JNIEnv, jarray, jbyteArray, jclass, jint, jintArray};
use std::ffi::{CStr, c_void};
use std::mem::MaybeUninit;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr::{self, NonNull};

/// Why an entry point stopped before producing its answer.
enum Failure {
    /// A kernel refused its input.
    Kernel(KernelError),
    /// An array argument was null.
    Null,
    /// A JNI call failed and left its own exception pending, such as an `OutOfMemoryError`.
    Pending,
}

impl From<KernelError> for Failure {
    fn from(error: KernelError) -> Self {
        Failure::Kernel(error)
    }
}

/// The thread's JNI environment for one native call.
#[derive(Clone, Copy)]
struct Env(*mut JNIEnv);

impl Env {
    /// The length of `array`, or [`Failure::Null`] for a null reference.
    fn length(self, array: jarray) -> Result<usize, Failure> {
        if array.is_null() {
            return Err(Failure::Null);
        }
        // SAFETY: `self.0` is the valid environment the JVM passed to this native call on this thread, and `array`
        // is a non-null local reference to a primitive array received as an argument of the same call.
        let length = unsafe { ((**self.0).v1_1.GetArrayLength)(self.0, array) };
        Ok(length as usize)
    }

    /// Borrows the elements of `array`, which holds `length` elements of type `T`, until the guard is dropped.
    /// `writes` says whether changes must reach the Java array on release.
    fn critical<T>(self, array: jarray, length: usize, writes: bool) -> Result<Critical<T>, Failure> {
        let data = if length == 0 {
            NonNull::dangling()
        } else {
            // SAFETY: valid environment and a non-null primitive array reference, as in `length`. No critical region
            // of this call is used for anything but plain memory access until every guard is dropped.
            let raw = unsafe { ((**self.0).v1_2.GetPrimitiveArrayCritical)(self.0, array, ptr::null_mut()) };
            NonNull::new(raw.cast::<T>()).ok_or(Failure::Pending)?
        };
        Ok(Critical {
            env: self,
            array,
            data,
            length,
            mode: if writes { 0 } else { JNI_ABORT },
        })
    }

    /// Copies the `length` ints of `array` into a new vector, holding no critical region.
    fn copy_ints(self, array: jintArray, length: usize) -> Result<Vec<i32>, Failure> {
        let mut copy = Vec::new();
        copy.try_reserve_exact(length).map_err(|_| KernelError::OutOfMemory)?;
        // SAFETY: valid environment and non-null int array of exactly `length` elements; `copy` has room for
        // `length` ints and its length is set only after the JVM has written all of them.
        unsafe {
            ((**self.0).v1_1.GetIntArrayRegion)(self.0, array, 0, length as jint, copy.as_mut_ptr());
            if ((**self.0).v1_2.ExceptionCheck)(self.0) {
                return Err(Failure::Pending);
            }
            copy.set_len(length);
        }
        Ok(copy)
    }

    /// Copies `array`, which holds `length` ints, onto the stack one chunk at a time and hands each chunk to
    /// `consume`: no heap allocation and no critical region, at the price of one copy.
    fn for_each_int_chunk(
        self,
        array: jintArray,
        length: usize,
        mut consume: impl FnMut(&[i32]),
    ) -> Result<(), Failure> {
        const CHUNK: usize = 1024;
        // Left uninitialized: the JVM writes each chunk before it is read, so zeroing 4 KiB per call would be waste.
        let mut buffer = [MaybeUninit::<i32>::uninit(); CHUNK];
        let mut start = 0;
        while start < length {
            let count = CHUNK.min(length - start);
            // SAFETY: valid environment, non-null int array of `length` elements; the region
            // [start, start + count) lies inside it and `buffer` has room for `count` ints.
            unsafe {
                ((**self.0).v1_1.GetIntArrayRegion)(
                    self.0,
                    array,
                    start as jint,
                    count as jint,
                    buffer.as_mut_ptr().cast::<i32>(),
                );
                if ((**self.0).v1_2.ExceptionCheck)(self.0) {
                    return Err(Failure::Pending);
                }
            }
            // SAFETY: with no exception pending, GetIntArrayRegion has initialized the first `count` elements.
            let chunk = unsafe { std::slice::from_raw_parts(buffer.as_ptr().cast::<i32>(), count) };
            consume(chunk);
            start += count;
        }
        Ok(())
    }

    /// A new Java int array holding `values`.
    fn new_ints(self, values: &[i32]) -> Result<jintArray, Failure> {
        let length = jint::try_from(values.len()).map_err(|_| KernelError::AnswerTooLarge)?;
        // SAFETY: valid environment; the new array has `length` elements, exactly the number copied from `values`.
        unsafe {
            let array = ((**self.0).v1_1.NewIntArray)(self.0, length);
            if array.is_null() {
                return Err(Failure::Pending);
            }
            ((**self.0).v1_1.SetIntArrayRegion)(self.0, array, 0, length, values.as_ptr());
            Ok(array)
        }
    }

    /// Whether two references name the same Java object.
    fn same(self, first: jarray, second: jarray) -> bool {
        // SAFETY: valid environment and two local references received by this call; no critical region is open.
        unsafe { ((**self.0).v1_1.IsSameObject)(self.0, first, second) }
    }

    /// Throws a new exception of `class` with `message`, unless a JNI call already left one pending.
    fn throw(self, class: &CStr, message: &CStr) {
        // SAFETY: valid environment, no critical region open, NUL-terminated class name and message.
        unsafe {
            if ((**self.0).v1_2.ExceptionCheck)(self.0) {
                return;
            }
            let class: jclass = ((**self.0).v1_1.FindClass)(self.0, class.as_ptr());
            if !class.is_null() {
                ((**self.0).v1_1.ThrowNew)(self.0, class, message.as_ptr());
            }
        }
    }
}

/// A primitive array held through `GetPrimitiveArrayCritical`, released when dropped. The slices it hands out borrow
/// the guard, so none of them outlives the release.
struct Critical<T> {
    env: Env,
    array: jarray,
    data: NonNull<T>,
    length: usize,
    mode: jint,
}

impl<T> Critical<T> {
    fn as_slice(&self) -> &[T] {
        // SAFETY: `data` points at `length` initialized elements of the Java array (or is dangling for an empty
        // one), which stay valid and in place until `Drop` releases them.
        unsafe { std::slice::from_raw_parts(self.data.as_ptr(), self.length) }
    }

    fn as_mut_slice(&mut self) -> &mut [T] {
        // SAFETY: as in `as_slice`. `&mut self` makes this the only view handed out by this guard, and no entry point
        // holds a second guard on the same array while one writes: `strongComponents` refuses an output array that is
        // also an input (`Env::same`), and `align` writes a `byte[]` while it reads `int[]`s.
        unsafe { std::slice::from_raw_parts_mut(self.data.as_ptr(), self.length) }
    }
}

impl<T> Drop for Critical<T> {
    fn drop(&mut self) {
        if self.length == 0 {
            return;
        }
        // SAFETY: releases exactly the pointer `GetPrimitiveArrayCritical` returned for this array, once.
        unsafe {
            ((**self.env.0).v1_2.ReleasePrimitiveArrayCritical)(
                self.env.0,
                self.array,
                self.data.as_ptr().cast::<c_void>(),
                self.mode,
            );
        }
    }
}

/// Runs `body`, turning a refusal or a panic into the matching Java exception and `fallback` as the return value.
fn entry<R>(env: Env, fallback: R, body: impl FnOnce() -> Result<R, Failure>) -> R {
    match catch_unwind(AssertUnwindSafe(body)) {
        Ok(Ok(value)) => value,
        Ok(Err(Failure::Kernel(error))) => {
            env.throw(error.java_class(), error.message());
            fallback
        }
        Ok(Err(Failure::Null)) => {
            env.throw(c"java/lang/NullPointerException", c"array");
            fallback
        }
        // The JNI call that failed normally left its own exception, which `throw` keeps; if it left none, the
        // caller still gets an error instead of a silently empty answer.
        Ok(Err(Failure::Pending)) => {
            env.throw(
                c"java/lang/OutOfMemoryError",
                c"the JVM could not lend an array to native code",
            );
            fallback
        }
        Err(_) => {
            env.throw(c"java/lang/IllegalStateException", c"the Rust kernel panicked");
            fallback
        }
    }
}

/// `RustKernels.noop()`.
///
/// # Safety
///
/// Called by the JVM only.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_graphmaquina_rust_RustKernels_noop(_env: *mut JNIEnv, _class: jclass) {}

/// `RustKernels.sumCritical(int[])`.
///
/// # Safety
///
/// Called by the JVM only, with its environment for the current thread.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_graphmaquina_rust_RustKernels_sumCritical(
    env: *mut JNIEnv,
    _class: jclass,
    values: jintArray,
) -> jint {
    let env = Env(env);
    entry(env, 0, || {
        let length = env.length(values)?;
        let values = env.critical::<i32>(values, length, false)?;
        Ok(values
            .as_slice()
            .iter()
            .fold(0i32, |sum, &value| sum.wrapping_add(value)))
    })
}

/// `RustKernels.sumRegion(int[])`.
///
/// # Safety
///
/// Called by the JVM only, with its environment for the current thread.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_graphmaquina_rust_RustKernels_sumRegion(
    env: *mut JNIEnv,
    _class: jclass,
    values: jintArray,
) -> jint {
    let env = Env(env);
    entry(env, 0, || {
        let length = env.length(values)?;
        let mut sum = 0i32;
        env.for_each_int_chunk(values, length, |chunk| {
            sum = chunk.iter().fold(sum, |sum, &value| sum.wrapping_add(value));
        })?;
        Ok(sum)
    })
}

/// `RustKernels.strongComponents(int[], int[], int[])`.
///
/// # Safety
///
/// Called by the JVM only, with its environment for the current thread.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_graphmaquina_rust_RustKernels_strongComponents(
    env: *mut JNIEnv,
    _class: jclass,
    offsets: jintArray,
    targets: jintArray,
    component: jintArray,
) -> jint {
    let env = Env(env);
    entry(env, 0, || {
        let (offsets_length, targets_length) = (env.length(offsets)?, env.length(targets)?);
        let component_length = env.length(component)?;
        if env.same(component, offsets) || env.same(component, targets) {
            return Err(KernelError::OutputAliasesInput.into());
        }
        let offsets = env.critical::<i32>(offsets, offsets_length, false)?;
        let targets = env.critical::<i32>(targets, targets_length, false)?;
        let mut component = env.critical::<i32>(component, component_length, true)?;
        let graph = Csr::new(offsets.as_slice(), targets.as_slice())?;
        if component.length < graph.nodes() {
            return Err(KernelError::OutputTooSmall.into());
        }
        Ok(scc::strong_components(&graph, component.as_mut_slice()) as jint)
    })
}

/// `RustKernels.cycles(int[], int[])`.
///
/// # Safety
///
/// Called by the JVM only, with its environment for the current thread.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_graphmaquina_rust_RustKernels_cycles(
    env: *mut JNIEnv,
    _class: jclass,
    offsets: jintArray,
    targets: jintArray,
) -> jintArray {
    let env = Env(env);
    entry(env, ptr::null_mut(), || {
        let (offsets_length, targets_length) = (env.length(offsets)?, env.length(targets)?);
        let offsets = env.copy_ints(offsets, offsets_length)?;
        let targets = env.copy_ints(targets, targets_length)?;
        let graph = Csr::new(&offsets, &targets)?;
        let mut listing = Vec::new();
        cycles::cycles(&graph, &mut listing)?;
        env.new_ints(&listing)
    })
}

/// `RustKernels.align(int[], int[], byte[])`.
///
/// # Safety
///
/// Called by the JVM only, with its environment for the current thread.
#[unsafe(no_mangle)]
pub unsafe extern "system" fn Java_io_github_graphmaquina_rust_RustKernels_align(
    env: *mut JNIEnv,
    _class: jclass,
    left: jintArray,
    right: jintArray,
    kinds: jbyteArray,
) -> jint {
    let env = Env(env);
    entry(env, 0, || {
        let (left_length, right_length) = (env.length(left)?, env.length(right)?);
        let kinds_length = env.length(kinds)?;
        let left = env.critical::<i32>(left, left_length, false)?;
        let right = env.critical::<i32>(right, right_length, false)?;
        let mut kinds = env.critical::<u8>(kinds, kinds_length, true)?;
        Ok(lcs::align(left.as_slice(), right.as_slice(), kinds.as_mut_slice())? as jint)
    })
}

/// Does nothing; the target of a Foreign Function and Memory API downcall, to compare its cost with a JNI call.
#[unsafe(no_mangle)]
pub extern "C" fn gm_noop() {}

/// The wrapping sum of `length` ints at `values`; a downcall that borrows one array.
///
/// # Safety
///
/// `values` must point at `length` readable, initialized `i32`s (or `length` must be 0), valid for the call.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn gm_sum(values: *const i32, length: usize) -> i32 {
    if length == 0 {
        return 0;
    }
    // SAFETY: the caller guarantees `length` readable ints at the non-null `values` for the call.
    let values = unsafe { std::slice::from_raw_parts(values, length) };
    values.iter().fold(0i32, |sum, &value| sum.wrapping_add(value))
}
