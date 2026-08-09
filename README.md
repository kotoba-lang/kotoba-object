# kotoba-object

Target-neutral object-file construction contracts for Kotoba.

`kotoba-object` owns the byte-level records used to construct object and
executable containers. It deliberately does not own compiler lowering, target
ABI policy, entry shims, memory maps, or linker policy.

The initial contract is `kotoba.object.elf64`, a small, validated ELF64
little-endian record encoder. Backends supply target decisions as data and use
the returned byte vectors when assembling a complete image.

```clojure
(require '[kotoba.object.elf64 :as elf64])

(elf64/encode-header
 {:type :executable
  :machine :x86-64
  :entry 0x401000
  :program-header-offset 64
  :program-header-count 2
  :section-header-offset 0x3000
  :section-header-count 4
  :section-name-index 3})
```

## Boundary

- owned here: ELF identification, headers, program/section records,
  symbols, RELA records, little-endian integer encoding, bounded padding
- owned by code generators/backends: instruction bytes, relocations to request,
  ABI shims, virtual addresses, section layout, capability/runtime policy
- owned by orchestration: selecting ELF versus Mach-O/PE/Wasm and writing files

## Development

```sh
clojure -M:test
```
