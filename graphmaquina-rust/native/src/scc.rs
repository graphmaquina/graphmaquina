//! Strongly connected components, numbered the way `StrongComponents.find` orders them.

use crate::csr::Csr;

/// Writes into `component[v]` the number of the strongly connected component of node `v` and returns how many
/// components there are. Components are numbered by their lowest node, which is the order `StrongComponents.find`
/// lists them in (the declaration index of their first node), so the caller rebuilds that exact list by walking
/// the nodes in order.
///
/// The search is an iterative Tarjan: it never recurses, so a graph as deep as it is large cannot overflow the
/// stack. Its working memory is reserved once, up front, from the node count; nothing grows while it runs.
///
/// # Panics
///
/// If `component` is shorter than the node count.
pub fn strong_components(graph: &Csr<'_>, component: &mut [i32]) -> usize {
    const UNVISITED: u32 = u32::MAX;
    let nodes = graph.nodes();
    let component = &mut component[..nodes];
    // A node is on Tarjan's stack exactly when it has been visited and has no component yet, so `component`
    // doubles as the on-stack flag: -1 until the node's component closes.
    component.fill(-1);
    let mut index = vec![UNVISITED; nodes];
    let mut low = vec![0u32; nodes];
    let mut stack: Vec<u32> = Vec::with_capacity(nodes);
    // One frame per node on the search path: the node and the position of its next edge to examine. A path holds
    // each node at most once, so `nodes` frames is the most it ever needs and the vector never reallocates.
    let mut frames: Vec<(u32, u32)> = Vec::with_capacity(nodes);
    let mut next_index = 0u32;
    let mut closed = 0i32;
    for root in 0..nodes {
        if index[root] != UNVISITED {
            continue;
        }
        index[root] = next_index;
        low[root] = next_index;
        next_index += 1;
        stack.push(root as u32);
        frames.push((root as u32, graph.first_edge(root) as u32));
        while let Some(frame) = frames.last_mut() {
            let (node, edge) = (frame.0 as usize, frame.1 as usize);
            if edge < graph.end_edge(node) {
                frame.1 += 1;
                let next = graph.target(edge);
                if index[next] == UNVISITED {
                    index[next] = next_index;
                    low[next] = next_index;
                    next_index += 1;
                    stack.push(next as u32);
                    frames.push((next as u32, graph.first_edge(next) as u32));
                } else if component[next] < 0 {
                    low[node] = low[node].min(index[next]);
                }
                continue;
            }
            frames.pop();
            if let Some(&(parent, _)) = frames.last() {
                let parent = parent as usize;
                low[parent] = low[parent].min(low[node]);
            }
            if low[node] == index[node] {
                while let Some(member) = stack.pop() {
                    component[member as usize] = closed;
                    if member as usize == node {
                        break;
                    }
                }
                closed += 1;
            }
        }
    }
    // Tarjan closes components in finishing order; renumber them by their lowest node. `index` is free again
    // and has room for one entry per component, so it holds the renumbering instead of a new allocation.
    let renumbered = &mut index[..closed as usize];
    renumbered.fill(UNVISITED);
    let mut count = 0u32;
    for slot in component.iter_mut() {
        let finished = *slot as usize;
        if renumbered[finished] == UNVISITED {
            renumbered[finished] = count;
            count += 1;
        }
        *slot = renumbered[finished] as i32;
    }
    count as usize
}

#[cfg(test)]
pub(crate) mod tests {
    use super::*;

    /// Builds the CSR arrays of a graph from its adjacency lists.
    pub(crate) fn csr(adjacency: &[&[i32]]) -> (Vec<i32>, Vec<i32>) {
        let mut offsets = vec![0];
        let mut targets = Vec::new();
        for successors in adjacency {
            targets.extend_from_slice(successors);
            offsets.push(targets.len() as i32);
        }
        (offsets, targets)
    }

    fn components(adjacency: &[&[i32]]) -> (usize, Vec<i32>) {
        let (offsets, targets) = csr(adjacency);
        let graph = Csr::new(&offsets, &targets).unwrap();
        let mut component = vec![-1; graph.nodes()];
        let count = strong_components(&graph, &mut component);
        (count, component)
    }

    #[test]
    fn components_follow_declaration_order() {
        // StrongComponentsTest.componentsFollowDeclarationOrder: A→B, B→A, B→C, C→D.
        assert_eq!(components(&[&[1], &[0, 2], &[3], &[]]), (3, vec![0, 0, 1, 2]));
    }

    #[test]
    fn numbering_ignores_the_order_the_search_finishes_components_in() {
        // 0→3, 3→1, 1→3, 2→2, 4→0: the search finishes {1, 3} before {0}.
        assert_eq!(components(&[&[3], &[3], &[2], &[1], &[0]]), (4, vec![0, 1, 2, 1, 3]));
    }

    #[test]
    fn the_empty_graph_has_no_component() {
        assert_eq!(components(&[]), (0, vec![]));
    }

    #[test]
    fn a_deep_ring_is_one_component_without_overflowing_the_stack() {
        let n: usize = 200_000;
        let adjacency: Vec<Vec<i32>> = (0..n).map(|v| vec![((v + 1) % n) as i32]).collect();
        let lists: Vec<&[i32]> = adjacency.iter().map(Vec::as_slice).collect();
        let (count, component) = components(&lists);
        assert_eq!(count, 1);
        assert!(component.iter().all(|&c| c == 0));
    }

    #[test]
    fn a_deep_chain_is_one_component_per_node() {
        let n = 200_000;
        let adjacency: Vec<Vec<i32>> =
            (0..n).map(|v| if v + 1 < n { vec![(v + 1) as i32] } else { vec![] }).collect();
        let lists: Vec<&[i32]> = adjacency.iter().map(Vec::as_slice).collect();
        let (count, component) = components(&lists);
        assert_eq!(count, n);
        assert!(component.iter().enumerate().all(|(v, &c)| c == v as i32));
    }

    /// A tiny deterministic generator, so the random corpus is the same on every run and needs no dependency.
    pub(crate) struct SplitMix(pub(crate) u64);

    impl SplitMix {
        pub(crate) fn next(&mut self) -> u64 {
            self.0 = self.0.wrapping_add(0x9E37_79B9_7F4A_7C15);
            let mut z = self.0;
            z = (z ^ (z >> 30)).wrapping_mul(0xBF58_476D_1CE4_E5B9);
            z = (z ^ (z >> 27)).wrapping_mul(0x94D0_49BB_1331_11EB);
            z ^ (z >> 31)
        }

        pub(crate) fn below(&mut self, bound: usize) -> usize {
            (self.next() % bound as u64) as usize
        }
    }

    /// Mutual reachability by brute force, numbered by lowest member: the definition, not an algorithm.
    fn reference(adjacency: &[Vec<i32>]) -> (usize, Vec<i32>) {
        let n = adjacency.len();
        let mut reach = vec![vec![false; n]; n];
        for (start, row) in reach.iter_mut().enumerate() {
            let mut pending = vec![start];
            row[start] = true;
            while let Some(v) = pending.pop() {
                for &w in &adjacency[v] {
                    if !row[w as usize] {
                        row[w as usize] = true;
                        pending.push(w as usize);
                    }
                }
            }
        }
        let mut component = vec![-1; n];
        let mut count = 0;
        for v in 0..n {
            if component[v] >= 0 {
                continue;
            }
            for w in v..n {
                if reach[v][w] && reach[w][v] {
                    component[w] = count as i32;
                }
            }
            count += 1;
        }
        (count, component)
    }

    #[test]
    fn random_graphs_match_mutual_reachability() {
        let mut random = SplitMix(0x5EED);
        for _ in 0..2_000 {
            let n = 1 + random.below(12);
            let density = 1 + random.below(4);
            let adjacency: Vec<Vec<i32>> = (0..n)
                .map(|_| {
                    let mut successors = Vec::new();
                    for w in 0..n {
                        if random.below(n.max(2)) < density && !successors.contains(&(w as i32)) {
                            successors.push(w as i32);
                        }
                    }
                    // Successor order is part of the input: shuffle it.
                    for i in (1..successors.len()).rev() {
                        successors.swap(i, random.below(i + 1));
                    }
                    successors
                })
                .collect();
            let lists: Vec<&[i32]> = adjacency.iter().map(Vec::as_slice).collect();
            assert_eq!(components(&lists), reference(&adjacency), "{adjacency:?}");
        }
    }
}
