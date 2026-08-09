# ADR-0001: Keep object-container encoding target-neutral

- Status: accepted
- Date: 2026-08-09

## Context

The initial ELF64 writer lived in `kotoba-native` beside instruction selection,
ABI entry shims, aiueos memory maps, fuel policy, and capability setup. Its
low-level ELF records are reusable, while those policies are not.

## Decision

`kotoba-object` owns validated object-container record encoding. The first
contract covers ELF64 little-endian headers, segments, sections, symbols, and
RELA entries.

Backends continue to own section layout, addresses, machine bytes, relocation
selection, entry shims, and runtime policy. This repository never infers those
decisions from a compiler artifact.

## Consequences

- ELF binary structure has one independently tested implementation.
- `kotoba-native` remains responsible for aiueos and target ABI semantics.
- Mach-O and PE can be added as sibling contracts without importing compiler
  policy into this repository.
