//! A directed graph in compressed sparse row form, borrowed from the caller's arrays.

use crate::KernelError;

/// The successors of node `v` are `targets[offsets[v]..offsets[v + 1]]`, in the order the Java graph declares
/// them. Node numbers are positions in the graph's declared node order, so "lower number" means "declared
/// earlier". Both slices are borrowed: the graph never copies or keeps the caller's data.
#[derive(Clone, Copy, Debug)]
pub struct Csr<'a> {
    offsets: &'a [i32],
    targets: &'a [i32],
}

impl<'a> Csr<'a> {
    /// Checks the arrays once, so every kernel can index them without further checks failing: `offsets` holds
    /// `n + 1` non-decreasing entries from 0 to `targets.len()`, and every target is a node below `n`.
    pub fn new(offsets: &'a [i32], targets: &'a [i32]) -> Result<Self, KernelError> {
        let Some((&first, _)) = offsets.split_first() else {
            return Err(KernelError::MalformedGraph(c"the offsets need one entry per node plus one"));
        };
        if first != 0 {
            return Err(KernelError::MalformedGraph(c"the first offset must be 0"));
        }
        if offsets.windows(2).any(|pair| pair[0] > pair[1]) {
            return Err(KernelError::MalformedGraph(c"the offsets must not decrease"));
        }
        if offsets[offsets.len() - 1] as usize != targets.len() {
            return Err(KernelError::MalformedGraph(c"the last offset must be the edge count"));
        }
        let nodes = offsets.len() - 1;
        if targets.iter().any(|&target| target < 0 || target as usize >= nodes) {
            return Err(KernelError::MalformedGraph(c"every target must be a node of the graph"));
        }
        Ok(Csr { offsets, targets })
    }

    /// The number of nodes.
    #[inline]
    pub fn nodes(&self) -> usize {
        self.offsets.len() - 1
    }

    /// The successors of `node`, in declared order.
    #[inline]
    pub fn successors(&self, node: usize) -> &'a [i32] {
        &self.targets[self.offsets[node] as usize..self.offsets[node + 1] as usize]
    }

    /// The position in `targets` where the successors of `node` start.
    #[inline]
    pub fn first_edge(&self, node: usize) -> usize {
        self.offsets[node] as usize
    }

    /// The position in `targets` just past the successors of `node`.
    #[inline]
    pub fn end_edge(&self, node: usize) -> usize {
        self.offsets[node + 1] as usize
    }

    /// The node an edge position points at.
    #[inline]
    pub fn target(&self, edge: usize) -> usize {
        self.targets[edge] as usize
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn accepts_a_well_formed_graph() {
        let graph = Csr::new(&[0, 2, 3, 3], &[1, 2, 0]).unwrap();
        assert_eq!(graph.nodes(), 3);
        assert_eq!(graph.successors(0), &[1, 2]);
        assert_eq!(graph.successors(1), &[0]);
        assert_eq!(graph.successors(2), &[] as &[i32]);
    }

    #[test]
    fn accepts_the_empty_graph() {
        assert_eq!(Csr::new(&[0], &[]).unwrap().nodes(), 0);
    }

    #[test]
    fn refuses_missing_offsets() {
        assert!(matches!(Csr::new(&[], &[]), Err(KernelError::MalformedGraph(_))));
    }

    #[test]
    fn refuses_offsets_that_do_not_start_at_zero() {
        assert!(matches!(Csr::new(&[1, 1], &[0]), Err(KernelError::MalformedGraph(_))));
    }

    #[test]
    fn refuses_decreasing_offsets() {
        assert!(matches!(Csr::new(&[0, 2, 1, 2], &[0, 0]), Err(KernelError::MalformedGraph(_))));
    }

    #[test]
    fn refuses_offsets_that_do_not_end_at_the_edge_count() {
        assert!(matches!(Csr::new(&[0, 1], &[0, 0]), Err(KernelError::MalformedGraph(_))));
    }

    #[test]
    fn refuses_a_target_outside_the_nodes() {
        assert!(matches!(Csr::new(&[0, 1], &[1]), Err(KernelError::MalformedGraph(_))));
        assert!(matches!(Csr::new(&[0, 1], &[-1]), Err(KernelError::MalformedGraph(_))));
    }
}
