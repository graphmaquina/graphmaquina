//! Each kernel reserves its working memory up front: the number of heap allocations per call is a small constant
//! that does not grow with the input. A counting global allocator, per thread so parallel tests do not interfere,
//! measures it.

use graphmaquina_rust::csr::Csr;
use graphmaquina_rust::{cycles, lcs, scc};
use std::alloc::{GlobalAlloc, Layout, System};
use std::cell::Cell;

struct Counting;

thread_local! {
    static ALLOCATIONS: Cell<usize> = const { Cell::new(0) };
}

// SAFETY: every call is forwarded unchanged to the system allocator; the counter is a const-initialized
// thread-local `Cell` without a destructor, so touching it never allocates or re-enters the allocator.
unsafe impl GlobalAlloc for Counting {
    unsafe fn alloc(&self, layout: Layout) -> *mut u8 {
        ALLOCATIONS.with(|count| count.set(count.get() + 1));
        // SAFETY: forwarded with the caller's layout, which satisfies `GlobalAlloc::alloc`'s contract.
        unsafe { System.alloc(layout) }
    }

    unsafe fn dealloc(&self, pointer: *mut u8, layout: Layout) {
        // SAFETY: `pointer` came from `System.alloc` with this `layout`, through `alloc` above.
        unsafe { System.dealloc(pointer, layout) }
    }

    unsafe fn realloc(&self, pointer: *mut u8, layout: Layout, size: usize) -> *mut u8 {
        ALLOCATIONS.with(|count| count.set(count.get() + 1));
        // SAFETY: forwarded unchanged; `pointer` and `layout` came from this allocator.
        unsafe { System.realloc(pointer, layout, size) }
    }
}

#[global_allocator]
static GLOBAL: Counting = Counting;

fn allocations(work: impl FnOnce()) -> usize {
    let before = ALLOCATIONS.with(Cell::get);
    work();
    ALLOCATIONS.with(Cell::get) - before
}

/// A ring with a chord every seventh node, as CSR arrays.
fn ring(nodes: usize) -> (Vec<i32>, Vec<i32>) {
    let mut offsets = vec![0];
    let mut targets = Vec::new();
    for node in 0..nodes {
        targets.push(((node + 1) % nodes) as i32);
        if node % 7 == 0 {
            targets.push(((node + 3) % nodes) as i32);
        }
        offsets.push(targets.len() as i32);
    }
    (offsets, targets)
}

/// Disjoint triangles, one elementary cycle each, plus an edge from each triangle into the next one.
fn triangles(nodes: usize) -> (Vec<i32>, Vec<i32>) {
    let mut offsets = vec![0];
    let mut targets = Vec::new();
    for node in 0..nodes {
        let base = node - node % 3;
        targets.push((base + (node + 1) % 3) as i32);
        if node % 3 == 2 && node + 1 < nodes {
            targets.push((node + 1) as i32);
        }
        offsets.push(targets.len() as i32);
    }
    (offsets, targets)
}

#[test]
fn strong_components_allocates_the_same_four_buffers_at_any_size() {
    let mut counts = Vec::new();
    for nodes in [10, 1_000, 100_000] {
        let (offsets, targets) = ring(nodes);
        let graph = Csr::new(&offsets, &targets).unwrap();
        let mut component = vec![0; nodes];
        counts.push(allocations(|| {
            scc::strong_components(&graph, &mut component);
        }));
    }
    assert_eq!(counts, vec![4, 4, 4]);
}

#[test]
fn cycle_enumeration_allocates_a_fixed_set_of_buffers_besides_its_answer() {
    let mut counts = Vec::new();
    for nodes in [9, 999, 99_999] {
        let (offsets, targets) = triangles(nodes);
        let graph = Csr::new(&offsets, &targets).unwrap();
        // Room for the whole answer beforehand, so only the working memory is counted.
        let mut out = Vec::with_capacity(4 * nodes);
        let mut found = 0;
        counts.push(allocations(|| {
            found = cycles::cycles(&graph, &mut out);
        }));
        assert_eq!(found, nodes / 3);
    }
    assert_eq!(counts, vec![9, 9, 9]);
}

#[test]
fn alignment_allocates_only_its_table() {
    let mut counts = Vec::new();
    for lines in [10, 300, 990] {
        let left: Vec<i32> = (0..lines).map(|line| (line % 13) as i32).collect();
        let right: Vec<i32> = (0..lines).map(|line| (line % 11) as i32).collect();
        let mut kinds = vec![0u8; 2 * lines];
        counts.push(allocations(|| {
            lcs::align(&left, &right, &mut kinds).unwrap();
        }));
    }
    assert_eq!(counts, vec![1, 1, 1]);
}
