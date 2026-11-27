//! Elementary cycles, listed in exactly the order `Graph.cycles()` lists them.

use crate::csr::Csr;
use crate::scc::strong_components;

/// Appends every elementary cycle of `graph` to `out` and returns how many there are. Each cycle is written as
/// its length followed by its nodes; it starts at its lowest node and does not repeat the closing node.
///
/// This is `Graph.cycles()` step for step: roots in node order, skipping roots whose strongly connected
/// component closes no cycle (more than one node, or one node pointing at itself); from each root a depth-first
/// search over successors in declared order that follows only nodes of the root's component numbered at least as
/// high as the root, and emits the path each time an edge returns to the root. The order of the answer is part of
/// the Java contract, so the search order is too.
///
/// The working memory (components, flags, path and frame stack) is reserved once from the node count; the only
/// buffer that grows is `out`, geometrically, because the size of the answer is not known before it is found.
pub fn cycles(graph: &Csr<'_>, out: &mut Vec<i32>) -> usize {
    let nodes = graph.nodes();
    let mut component = vec![0i32; nodes];
    let components = strong_components(graph, &mut component);
    // Member count per component, then raised to 2 for a lone node with a self-loop: a component closes a cycle
    // exactly when its entry ends above 1.
    let mut closes = vec![0u32; components];
    for &owner in &component {
        closes[owner as usize] += 1;
    }
    for (node, &owner) in component.iter().enumerate() {
        let owner = owner as usize;
        if closes[owner] == 1 && graph.successors(node).contains(&(node as i32)) {
            closes[owner] = 2;
        }
    }
    let mut on_path = vec![false; nodes];
    let mut path: Vec<i32> = Vec::with_capacity(nodes);
    // The frame on top of the search lives in locals (`node`, `edge`, `end`), where the compiler can keep it in
    // registers; `suspended` holds the frames below it. A path holds each node once, so neither vector reallocates.
    let mut suspended: Vec<(u32, u32)> = Vec::with_capacity(nodes);
    let mut found = 0;
    for root in 0..nodes {
        let root_component = component[root];
        if closes[root_component as usize] <= 1 {
            continue;
        }
        path.push(root as i32);
        on_path[root] = true;
        let (mut node, mut edge, mut end) = (root, graph.first_edge(root), graph.end_edge(root));
        loop {
            if edge == end {
                on_path[node] = false;
                path.pop();
                let Some((parent, parent_edge)) = suspended.pop() else {
                    break;
                };
                (node, edge) = (parent as usize, parent_edge as usize);
                end = graph.end_edge(node);
                continue;
            }
            let next = graph.target(edge);
            edge += 1;
            if next < root || component[next] != root_component {
                continue;
            }
            if next == root {
                out.push(path.len() as i32);
                out.extend_from_slice(&path);
                found += 1;
            } else if !on_path[next] {
                on_path[next] = true;
                path.push(next as i32);
                suspended.push((node as u32, edge as u32));
                (node, edge, end) = (next, graph.first_edge(next), graph.end_edge(next));
            }
        }
    }
    found
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::scc::tests::{SplitMix, csr};

    fn listing(adjacency: &[&[i32]]) -> Vec<Vec<i32>> {
        let (offsets, targets) = csr(adjacency);
        let graph = Csr::new(&offsets, &targets).unwrap();
        let mut out = Vec::new();
        let count = cycles(&graph, &mut out);
        let mut result = Vec::with_capacity(count);
        let mut at = 0;
        while at < out.len() {
            let length = out[at] as usize;
            result.push(out[at + 1..at + 1 + length].to_vec());
            at += 1 + length;
        }
        assert_eq!(result.len(), count);
        result
    }

    #[test]
    fn a_two_node_cycle_and_a_self_loop_come_out_in_search_order() {
        assert_eq!(listing(&[&[1, 0], &[0]]), vec![vec![0, 1], vec![0]]);
    }

    #[test]
    fn an_acyclic_graph_has_no_cycle() {
        assert_eq!(listing(&[&[1, 2], &[2], &[]]), Vec::<Vec<i32>>::new());
        assert_eq!(listing(&[]), Vec::<Vec<i32>>::new());
    }

    fn dense(size: usize) -> Vec<Vec<i32>> {
        (0..size)
            .map(|from| (1..size).map(|offset| ((from + offset) % size) as i32).collect())
            .collect()
    }

    #[test]
    fn dense_fixtures_have_the_counts_cycle_bench_asserts() {
        for (size, expected) in [(6, 409), (8, 16_064)] {
            let adjacency = dense(size);
            let lists: Vec<&[i32]> = adjacency.iter().map(Vec::as_slice).collect();
            assert_eq!(listing(&lists).len(), expected);
        }
    }

    /// The recursive enumeration the library shipped before pruning by components, kept as Java's
    /// CycleEnumerationDifferentialTest keeps it: roots in order, successors in order, nodes below the root and
    /// nodes on the path skipped.
    fn reference(adjacency: &[Vec<i32>]) -> Vec<Vec<i32>> {
        fn search(
            adjacency: &[Vec<i32>],
            start: usize,
            current: usize,
            path: &mut Vec<i32>,
            on_path: &mut [bool],
            found: &mut Vec<Vec<i32>>,
        ) {
            for &next in &adjacency[current] {
                let next = next as usize;
                if next == start {
                    found.push(path.clone());
                    continue;
                }
                if next < start || on_path[next] {
                    continue;
                }
                path.push(next as i32);
                on_path[next] = true;
                search(adjacency, start, next, path, on_path, found);
                path.pop();
                on_path[next] = false;
            }
        }
        let mut found = Vec::new();
        for start in 0..adjacency.len() {
            let mut on_path = vec![false; adjacency.len()];
            on_path[start] = true;
            search(
                adjacency,
                start,
                start,
                &mut vec![start as i32],
                &mut on_path,
                &mut found,
            );
        }
        found
    }

    #[test]
    fn random_graphs_match_the_recursive_reference_in_order() {
        let mut random = SplitMix(0xC1C1E5);
        for _ in 0..3_000 {
            let n = 1 + random.below(7);
            let adjacency: Vec<Vec<i32>> = (0..n)
                .map(|_| {
                    let mut successors: Vec<i32> = (0..n as i32).filter(|_| random.below(3) == 0).collect();
                    for i in (1..successors.len()).rev() {
                        successors.swap(i, random.below(i + 1));
                    }
                    successors
                })
                .collect();
            let lists: Vec<&[i32]> = adjacency.iter().map(Vec::as_slice).collect();
            assert_eq!(listing(&lists), reference(&adjacency), "{adjacency:?}");
        }
    }

    #[test]
    fn every_three_node_graph_matches_the_reference() {
        for mask in 0u32..512 {
            let adjacency: Vec<Vec<i32>> = (0..3)
                .map(|from| (0..3).filter(|to| mask & (1 << (from * 3 + to)) != 0).collect())
                .collect();
            let lists: Vec<&[i32]> = adjacency.iter().map(Vec::as_slice).collect();
            assert_eq!(listing(&lists), reference(&adjacency), "{adjacency:?}");
        }
    }
}
