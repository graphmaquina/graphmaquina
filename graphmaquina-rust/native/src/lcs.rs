//! The longest-common-subsequence alignment `LineDiff` runs over the middle block of two texts.

use crate::KernelError;

/// A line both blocks keep.
pub const CONTEXT: u8 = 0;
/// A line only the left (expected) block has.
pub const DELETE: u8 = 1;
/// A line only the right (actual) block has.
pub const ADD: u8 = 2;

/// The most cells `LineDiff` lets its table hold. Under it the shorter block has fewer than a thousand lines, so
/// every length in the table fits in a `u16`.
pub const MAX_CELLS: usize = 1_000_000;

/// Aligns two blocks of interned lines (equal numbers mean equal lines) and writes the edit script into `kinds`,
/// one entry per step, returning how many steps there are.
///
/// It is `LineDiff.commonLengths` followed by `LineDiff.align`, unchanged in substance: the table of suffix
/// lengths is filled from the last cell back, then walked from the first cell, keeping common lines and
/// preferring a deletion over an addition on a tie, and whatever is left is deleted and then added. The table is
/// one flat `u16` allocation, half the bytes of Java's `int[][]` and without its per-row arrays.
///
/// # Errors
///
/// [`KernelError::TableTooLarge`] past [`MAX_CELLS`] cells, the bound `LineDiff` aligns under (it reports the
/// block as one deletion and one addition beyond it), and [`KernelError::OutputTooSmall`] when `kinds` cannot hold
/// `left.len() + right.len()` steps, the longest script possible.
pub fn align(left: &[i32], right: &[i32], kinds: &mut [u8]) -> Result<usize, KernelError> {
    let (rows, columns) = (left.len(), right.len());
    let cells = (rows + 1)
        .checked_mul(columns + 1)
        .filter(|&cells| cells <= MAX_CELLS)
        .ok_or(KernelError::TableTooLarge)?;
    if kinds.len() < rows + columns {
        return Err(KernelError::OutputTooSmall);
    }
    let width = columns + 1;
    // table[i * width + j] is the length of the longest common subsequence of left[i..] and right[j..]. The
    // last row and the last column stay 0. Under MAX_CELLS the shorter side has at most 999 lines, so no length
    // reaches u16::MAX and the additions below cannot overflow.
    let mut table = vec![0u16; cells];
    for i in (0..rows).rev() {
        let (upper, lower) = table.split_at_mut((i + 1) * width);
        // Both rows are exactly `width` long and `right` exactly `columns`, so every index below is provably in
        // bounds and the compiler drops the checks.
        let row = &mut upper[i * width..];
        let below = &lower[..width];
        let line = left[i];
        // The cell to the right of the one being filled, carried in a register instead of read back from memory.
        let mut right_cell = 0u16;
        for j in (0..columns).rev() {
            let cell = if line == right[j] {
                below[j + 1] + 1
            } else {
                below[j].max(right_cell)
            };
            row[j] = cell;
            right_cell = cell;
        }
    }
    let (mut i, mut j, mut steps) = (0, 0, 0);
    while i < rows && j < columns {
        kinds[steps] = if left[i] == right[j] {
            i += 1;
            j += 1;
            CONTEXT
        } else if table[(i + 1) * width + j] >= table[i * width + j + 1] {
            i += 1;
            DELETE
        } else {
            j += 1;
            ADD
        };
        steps += 1;
    }
    kinds[steps..steps + (rows - i)].fill(DELETE);
    steps += rows - i;
    kinds[steps..steps + (columns - j)].fill(ADD);
    steps += columns - j;
    Ok(steps)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::scc::tests::SplitMix;

    fn script(left: &[i32], right: &[i32]) -> Vec<u8> {
        let mut kinds = vec![u8::MAX; left.len() + right.len()];
        let steps = align(left, right, &mut kinds).unwrap();
        kinds.truncate(steps);
        kinds
    }

    #[test]
    fn keeps_common_lines_and_deletes_the_rest() {
        assert_eq!(script(&[1, 2, 3], &[1, 3]), vec![CONTEXT, DELETE, CONTEXT]);
    }

    #[test]
    fn a_tie_deletes_before_it_adds() {
        assert_eq!(script(&[1], &[2]), vec![DELETE, ADD]);
    }

    #[test]
    fn empty_blocks_align_to_what_the_other_side_holds() {
        assert_eq!(script(&[], &[]), Vec::<u8>::new());
        assert_eq!(script(&[7, 8], &[]), vec![DELETE, DELETE]);
        assert_eq!(script(&[], &[7, 8]), vec![ADD, ADD]);
    }

    #[test]
    fn refuses_a_table_past_the_cell_bound() {
        let left = vec![0; 999];
        let right = vec![1; 1001];
        let mut kinds = vec![0; left.len() + right.len()];
        assert!(matches!(
            align(&left, &right, &mut kinds),
            Err(KernelError::TableTooLarge)
        ));
    }

    #[test]
    fn accepts_a_table_exactly_at_the_cell_bound() {
        let left = vec![3; 999];
        let right = vec![3; 999];
        let mut kinds = vec![0; left.len() + right.len()];
        assert_eq!(align(&left, &right, &mut kinds), Ok(999));
    }

    #[test]
    fn refuses_an_output_too_small_for_the_longest_script() {
        let mut kinds = vec![0; 2];
        assert!(matches!(
            align(&[1, 2], &[3], &mut kinds),
            Err(KernelError::OutputTooSmall)
        ));
    }

    /// `LineDiff.commonLengths` and `LineDiff.align` as Java writes them, over an `int[][]`-shaped table.
    fn reference(left: &[i32], right: &[i32]) -> Vec<u8> {
        let (rows, columns) = (left.len(), right.len());
        let mut lcs = vec![vec![0i32; columns + 1]; rows + 1];
        for i in (0..rows).rev() {
            for j in (0..columns).rev() {
                lcs[i][j] = if left[i] == right[j] {
                    1 + lcs[i + 1][j + 1]
                } else {
                    lcs[i + 1][j].max(lcs[i][j + 1])
                };
            }
        }
        let mut kinds = Vec::new();
        let (mut i, mut j) = (0, 0);
        while i < rows && j < columns {
            if left[i] == right[j] {
                kinds.push(CONTEXT);
                i += 1;
                j += 1;
            } else if lcs[i + 1][j] >= lcs[i][j + 1] {
                kinds.push(DELETE);
                i += 1;
            } else {
                kinds.push(ADD);
                j += 1;
            }
        }
        kinds.extend(std::iter::repeat_n(DELETE, rows - i));
        kinds.extend(std::iter::repeat_n(ADD, columns - j));
        kinds
    }

    #[test]
    fn random_blocks_match_line_diff() {
        let mut random = SplitMix(0x11FE);
        for _ in 0..3_000 {
            let alphabet = 1 + random.below(6);
            let left: Vec<i32> = (0..random.below(40)).map(|_| random.below(alphabet) as i32).collect();
            let right: Vec<i32> = (0..random.below(40)).map(|_| random.below(alphabet) as i32).collect();
            assert_eq!(script(&left, &right), reference(&left, &right), "{left:?} {right:?}");
        }
    }

    #[test]
    fn a_large_block_matches_line_diff() {
        let mut random = SplitMix(0xB16);
        let left: Vec<i32> = (0..900).map(|_| random.below(30) as i32).collect();
        let right: Vec<i32> = (0..1_000).map(|_| random.below(30) as i32).collect();
        assert_eq!(script(&left, &right), reference(&left, &right));
    }
}
