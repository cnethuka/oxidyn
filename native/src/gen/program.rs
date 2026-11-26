//! A density function graph, flattened by the Java side into a stream of
//! 64-bit words. Every reference points backwards (children are emitted
//! before parents), which rules out cycles and lets checks run in one pass.
//!
//! Wire format, one record after another:
//!   node:   kind, then kind-specific words (see `parse`)
//!   spline: SPLINE_CONST bits | SPLINE_MULTI coord n locs.. values.. derivs..
//! and finally ROOTS followed by ROOT_COUNT node ids (u64::MAX = absent).

use crate::noise::{BlendedHeader, NormalHeader, Octave, BLENDED_OCTAVES};

#[derive(Clone, Copy)]
pub struct NormalRef {
    pub first: &'static [Octave],
    pub second: &'static [Octave],
    pub value_factor: f64,
}

#[derive(Clone, Copy)]
pub struct BlendedRef {
    pub header: &'static BlendedHeader,
    pub octaves: &'static [Octave],
}

#[derive(Clone, Copy)]
pub enum MapKind {
    Abs,
    Square,
    Cube,
    HalfNegative,
    QuarterNegative,
    Squeeze,
}

#[derive(Clone, Copy)]
pub enum Node {
    Const(f64),
    Add(u32, u32),
    Mul(u32, u32),
    /// second arg's minValue, used for vanilla's short circuit
    Min(u32, u32, f64),
    Max(u32, u32, f64),
    AddConst(u32, f64),
    MulConst(u32, f64),
    Clamp(u32, f64, f64),
    Mapped(u32, MapKind),
    Range { input: u32, lo: f64, hi: f64, when_in: u32, when_out: u32 },
    YGradient { from_y: i32, to_y: i32, from_v: f64, to_v: f64 },
    Noise { noise: Option<NormalRef>, xz: f64, y: f64 },
    Shifted { sx: u32, sy: u32, sz: u32, xz: f64, y: f64, noise: Option<NormalRef> },
    ShiftA(Option<NormalRef>),
    ShiftB(Option<NormalRef>),
    Shift(Option<NormalRef>),
    Blended(BlendedRef),
    /// type 1 = 3D rarity, 2 = 2D rarity
    Weird { input: u32, noise: Option<NormalRef>, kind: u8 },
    Spline(u32),
    Interpolated { input: u32, slot: u32 },
    FlatCache { input: u32, slot: u32 },
    Cache2D(u32),
    CacheOnce { input: u32, slot: u32 },
    BlendAlpha,
    BlendOffset,
    BlendDensity(u32),
    Beardifier,
}

pub enum Spline {
    Const(f32),
    Multi { coord: u32, locations: Vec<f32>, values: Vec<u32>, derivatives: Vec<f32> },
}

/// Which router function each root slot holds.
pub const ROOT_FINAL: usize = 0;
pub const ROOT_BARRIER: usize = 1;
pub const ROOT_FLOODEDNESS: usize = 2;
pub const ROOT_SPREAD: usize = 3;
pub const ROOT_LAVA: usize = 4;
pub const ROOT_EROSION: usize = 5;
pub const ROOT_DEPTH: usize = 6;
pub const ROOT_INITIAL_NO_JAGGEDNESS: usize = 7;
pub const ROOT_VEIN_TOGGLE: usize = 8;
pub const ROOT_VEIN_RIDGED: usize = 9;
pub const ROOT_VEIN_GAP: usize = 10;
pub const ROOT_COUNT: usize = 11;

const SPLINE_CONST: u64 = 100;
const SPLINE_MULTI: u64 = 101;
const ROOTS: u64 = 200;

pub struct Program {
    pub nodes: Vec<Node>,
    pub splines: Vec<Spline>,
    pub roots: [Option<u32>; ROOT_COUNT],
    pub interp_slots: u32,
    pub flat_slots: u32,
    pub once_slots: u32,
    /// node id of each FlatCache slot, in slot order (children before parents)
    pub flat_nodes: Vec<u32>,
    pub interp_nodes: Vec<u32>,
}

/// Reasons a graph gets rejected. Returned to Java as negative codes so the
/// log can say why a dimension stays on the Java path.
#[derive(Debug, Clone, Copy)]
pub enum Reject {
    Malformed = 1,
    UnknownKind = 2,
    BadReference = 3,
    NestedInterpolation = 4,
    YDependentCache2D = 5,
    CacheOnceInCellFill = 6,
    MissingFinalDensity = 7,
}

struct Reader<'a> {
    words: &'a [u64],
    at: usize,
}

impl Reader<'_> {
    fn next(&mut self) -> Result<u64, Reject> {
        let w = *self.words.get(self.at).ok_or(Reject::Malformed)?;
        self.at += 1;
        Ok(w)
    }
    fn f64(&mut self) -> Result<f64, Reject> {
        Ok(f64::from_bits(self.next()?))
    }
    fn i32(&mut self) -> Result<i32, Reject> {
        Ok(self.next()? as i64 as i32)
    }
}

unsafe fn normal_ref(addr: u64) -> Option<NormalRef> {
    if addr == 0 {
        return None;
    }
    let h = &*(addr as *const NormalHeader);
    let start = (addr as *const NormalHeader).add(1) as *const Octave;
    let all = std::slice::from_raw_parts(start, (h.first_count + h.second_count) as usize);
    let (first, second) = all.split_at(h.first_count as usize);
    Some(NormalRef { first, second, value_factor: h.value_factor })
}

unsafe fn blended_ref(addr: u64) -> Result<BlendedRef, Reject> {
    if addr == 0 {
        return Err(Reject::Malformed);
    }
    let header = &*(addr as *const BlendedHeader);
    let start = (addr as *const BlendedHeader).add(1) as *const Octave;
    Ok(BlendedRef { header, octaves: std::slice::from_raw_parts(start, BLENDED_OCTAVES) })
}

impl Program {
    /// # Safety
    /// Noise addresses in the stream must point at live descriptors that
    /// outlive the program. The Java side keeps them reachable for exactly
    /// as long as the program handle exists.
    pub unsafe fn parse(words: &[u64]) -> Result<Program, Reject> {
        let mut r = Reader { words, at: 0 };
        let mut nodes: Vec<Node> = Vec::new();
        let mut splines: Vec<Spline> = Vec::new();
        let mut roots = [None; ROOT_COUNT];
        let (mut interp_slots, mut flat_slots, mut once_slots) = (0u32, 0u32, 0u32);
        let mut flat_nodes = Vec::new();
        let mut interp_nodes = Vec::new();

        loop {
            let kind = r.next()?;
            let n = nodes.len() as u32;
            // child references must already exist
            let node_ref = |id: u64| -> Result<u32, Reject> {
                if id < n as u64 { Ok(id as u32) } else { Err(Reject::BadReference) }
            };
            let node = match kind {
                0 => Node::Const(r.f64()?),
                1 => Node::Add(node_ref(r.next()?)?, node_ref(r.next()?)?),
                2 => Node::Mul(node_ref(r.next()?)?, node_ref(r.next()?)?),
                3 => Node::Min(node_ref(r.next()?)?, node_ref(r.next()?)?, r.f64()?),
                4 => Node::Max(node_ref(r.next()?)?, node_ref(r.next()?)?, r.f64()?),
                5 => Node::AddConst(node_ref(r.next()?)?, r.f64()?),
                6 => Node::MulConst(node_ref(r.next()?)?, r.f64()?),
                7 => Node::Clamp(node_ref(r.next()?)?, r.f64()?, r.f64()?),
                8 => {
                    let input = node_ref(r.next()?)?;
                    let kind = match r.next()? {
                        0 => MapKind::Abs,
                        1 => MapKind::Square,
                        2 => MapKind::Cube,
                        3 => MapKind::HalfNegative,
                        4 => MapKind::QuarterNegative,
                        5 => MapKind::Squeeze,
                        _ => return Err(Reject::UnknownKind),
                    };
                    Node::Mapped(input, kind)
                }
                9 => Node::Range {
                    input: node_ref(r.next()?)?,
                    lo: r.f64()?,
                    hi: r.f64()?,
                    when_in: node_ref(r.next()?)?,
                    when_out: node_ref(r.next()?)?,
                },
                10 => Node::YGradient { from_y: r.i32()?, to_y: r.i32()?, from_v: r.f64()?, to_v: r.f64()? },
                11 => Node::Noise { noise: normal_ref(r.next()?), xz: r.f64()?, y: r.f64()? },
                12 => Node::Shifted {
                    sx: node_ref(r.next()?)?,
                    sy: node_ref(r.next()?)?,
                    sz: node_ref(r.next()?)?,
                    xz: r.f64()?,
                    y: r.f64()?,
                    noise: normal_ref(r.next()?),
                },
                13 => Node::ShiftA(normal_ref(r.next()?)),
                14 => Node::ShiftB(normal_ref(r.next()?)),
                15 => Node::Shift(normal_ref(r.next()?)),
                16 => Node::Blended(blended_ref(r.next()?)?),
                17 => {
                    let input = node_ref(r.next()?)?;
                    let noise = normal_ref(r.next()?);
                    let kind = r.next()? as u8;
                    if kind != 1 && kind != 2 {
                        return Err(Reject::UnknownKind);
                    }
                    Node::Weird { input, noise, kind }
                }
                18 => {
                    let s = r.next()?;
                    if s >= splines.len() as u64 {
                        return Err(Reject::BadReference);
                    }
                    Node::Spline(s as u32)
                }
                19 => {
                    interp_nodes.push(n);
                    interp_slots += 1;
                    Node::Interpolated { input: node_ref(r.next()?)?, slot: interp_slots - 1 }
                }
                20 => {
                    flat_nodes.push(n);
                    flat_slots += 1;
                    Node::FlatCache { input: node_ref(r.next()?)?, slot: flat_slots - 1 }
                }
                21 => Node::Cache2D(node_ref(r.next()?)?),
                22 => {
                    once_slots += 1;
                    Node::CacheOnce { input: node_ref(r.next()?)?, slot: once_slots - 1 }
                }
                23 => Node::BlendAlpha,
                24 => Node::BlendOffset,
                25 => Node::BlendDensity(node_ref(r.next()?)?),
                26 => Node::Beardifier,
                SPLINE_CONST => {
                    splines.push(Spline::Const(f32::from_bits(r.next()? as u32)));
                    continue;
                }
                SPLINE_MULTI => {
                    let coord = node_ref(r.next()?)?;
                    let len = r.next()? as usize;
                    if len == 0 || len > 4096 {
                        return Err(Reject::Malformed);
                    }
                    let mut locations = Vec::with_capacity(len);
                    for _ in 0..len {
                        locations.push(f32::from_bits(r.next()? as u32));
                    }
                    let mut values = Vec::with_capacity(len);
                    for _ in 0..len {
                        let v = r.next()?;
                        if v >= splines.len() as u64 {
                            return Err(Reject::BadReference);
                        }
                        values.push(v as u32);
                    }
                    let mut derivatives = Vec::with_capacity(len);
                    for _ in 0..len {
                        derivatives.push(f32::from_bits(r.next()? as u32));
                    }
                    splines.push(Spline::Multi { coord, locations, values, derivatives });
                    continue;
                }
                ROOTS => {
                    for root in roots.iter_mut() {
                        let id = r.next()?;
                        *root = if id == u64::MAX { None } else { Some(node_ref(id)?) };
                    }
                    break;
                }
                _ => return Err(Reject::UnknownKind),
            };
            nodes.push(node);
        }
        if r.at != words.len() {
            return Err(Reject::Malformed);
        }
        if roots[ROOT_FINAL].is_none() {
            return Err(Reject::MissingFinalDensity);
        }

        let p = Program { nodes, splines, roots, interp_slots, flat_slots, once_slots, flat_nodes, interp_nodes };
        p.check()?;
        Ok(p)
    }

    fn children(&self, id: u32, out: &mut Vec<u32>) {
        match self.nodes[id as usize] {
            Node::Add(a, b) | Node::Mul(a, b) | Node::Min(a, b, _) | Node::Max(a, b, _) => out.extend([a, b]),
            Node::AddConst(a, _) | Node::MulConst(a, _) | Node::Clamp(a, _, _) | Node::Mapped(a, _) => out.push(a),
            Node::Range { input, when_in, when_out, .. } => out.extend([input, when_in, when_out]),
            Node::Shifted { sx, sy, sz, .. } => out.extend([sx, sy, sz]),
            Node::Weird { input, .. } => out.push(input),
            Node::Spline(s) => self.spline_coords(s, out),
            Node::Interpolated { input, .. }
            | Node::FlatCache { input, .. }
            | Node::CacheOnce { input, .. }
            | Node::Cache2D(input)
            | Node::BlendDensity(input) => out.push(input),
            _ => {}
        }
    }

    fn spline_coords(&self, s: u32, out: &mut Vec<u32>) {
        if let Spline::Multi { coord, values, .. } = &self.splines[s as usize] {
            out.push(*coord);
            for &v in values {
                self.spline_coords(v, out);
            }
        }
    }

    /// Rejects graph shapes where vanilla's NoiseChunk caches would not be
    /// value-transparent, i.e. where the result depends on evaluation order.
    /// Those dimensions just stay on the Java path.
    fn check(&self) -> Result<(), Reject> {
        let n = self.nodes.len();
        // per node: subtree contains an Interpolated / reads y / has a CacheOnce
        // that isn't hidden behind an Interpolated
        let mut has_interp = vec![false; n];
        let mut reads_y = vec![false; n];
        let mut bare_once = vec![false; n];
        let mut kids = Vec::new();
        for id in 0..n {
            kids.clear();
            self.children(id as u32, &mut kids);
            let any = |v: &Vec<bool>| kids.iter().any(|&k| v[k as usize]);
            let (ci, cy, co) = (any(&has_interp), any(&reads_y), any(&bare_once));
            match self.nodes[id] {
                Node::Interpolated { .. } => {
                    // NoiseInterpolator.compute inside another interpolator's
                    // slice fill returns a stale value in vanilla
                    if ci {
                        return Err(Reject::NestedInterpolation);
                    }
                    has_interp[id] = true;
                    reads_y[id] = true;
                    bare_once[id] = false;
                }
                Node::Cache2D(_) => {
                    if cy {
                        return Err(Reject::YDependentCache2D);
                    }
                    has_interp[id] = ci;
                    bare_once[id] = co;
                }
                Node::CacheOnce { .. } => {
                    has_interp[id] = ci;
                    reads_y[id] = cy;
                    bare_once[id] = true;
                }
                Node::Noise { y, .. } => reads_y[id] = y != 0.0,
                Node::Shifted { y, .. } => {
                    reads_y[id] = y != 0.0 || cy;
                    has_interp[id] = ci;
                    bare_once[id] = co;
                }
                Node::YGradient { .. } | Node::Shift(_) | Node::Blended(_) | Node::Beardifier => reads_y[id] = true,
                Node::Weird { .. } => {
                    reads_y[id] = true;
                    has_interp[id] = ci;
                    bare_once[id] = co;
                }
                _ => {
                    has_interp[id] = ci;
                    reads_y[id] = cy;
                    bare_once[id] = co;
                }
            }
        }
        // the cell fill runs the final density without bumping the counter
        // CacheOnce keys on, so a CacheOnce reachable there could go stale
        if let Some(f) = self.roots[ROOT_FINAL] {
            if bare_once[f as usize] {
                return Err(Reject::CacheOnceInCellFill);
            }
        }
        Ok(())
    }
}
