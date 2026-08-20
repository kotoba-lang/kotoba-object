(ns kotoba.object.elf64)

(def ^:private object-types
  {:relocatable 1
   :executable 2
   :shared 3
   :core 4})

(def ^:private machines
  {:x86-64 0x3e
   :aarch64 0xb7})

(def ^:private program-types
  {:null 0
   :load 1
   :dynamic 2
   :interpreter 3
   :note 4
   :program-header 6
   :tls 7})

(def ^:private section-types
  {:null 0
   :progbits 1
   :symtab 2
   :strtab 3
   :rela 4
   :nobits 8})

(defn- unsigned-limit [width]
  (reduce *' 1 (repeat width 256)))

(defn little-endian
  "Encode integer `n` in exactly `width` little-endian bytes.

  Negative values use two's-complement representation. Values that cannot be
  represented in the requested width are rejected rather than truncated."
  [n width]
  (when-not (contains? #{1 2 4 8} width)
    (throw (ex-info "ELF integer width must be 1, 2, 4, or 8 bytes"
                    {:width width})))
  (let [limit (unsigned-limit width)
        minimum (- (quot limit 2))]
    (when-not (and (integer? n) (<= minimum n) (< n limit))
      (throw (ex-info "ELF integer does not fit requested width"
                      {:value n :width width})))
    (let [encoded (if (neg? n) (+ limit n) n)]
      (mapv #(mod (quot encoded (unsigned-limit %)) 256)
            (range width)))))

(defn pad-to
  "Return `bytes` padded with zeroes to exactly `size` bytes."
  [bytes size]
  (when-not (and (integer? size) (not (neg? size)))
    (throw (ex-info "ELF allocation size must be a non-negative integer"
                    {:size size})))
  (when (> (count bytes) size)
    (throw (ex-info "ELF region exceeds its allocation"
                    {:size size :actual (count bytes)})))
  (into (vec bytes) (repeat (- size (count bytes)) 0)))

(defn- enum-value [values kind value]
  (or (get values value)
      (when (and (integer? value) (not (neg? value))) value)
      (throw (ex-info (str "Unknown ELF " (name kind))
                      {kind value}))))

(defn encode-header
  "Encode an ELF64 little-endian file header.

  Header and entry sizes are fixed to the ELF64 contract. Program headers may
  be absent by setting their offset and count to zero."
  [{:keys [type machine entry program-header-offset program-header-count
           section-header-offset section-header-count section-name-index]
    :or {entry 0
         program-header-offset 0
         program-header-count 0
         section-header-offset 0
         section-header-count 0
         section-name-index 0}}]
  (let [type-id (enum-value object-types :type type)
        machine-id (enum-value machines :machine machine)]
    (vec
     (concat
      [0x7f 0x45 0x4c 0x46 2 1 1 0] (repeat 8 0)
      (little-endian type-id 2)
      (little-endian machine-id 2)
      (little-endian 1 4)
      (little-endian entry 8)
      (little-endian program-header-offset 8)
      (little-endian section-header-offset 8)
      (little-endian 0 4)
      (little-endian 64 2)
      (little-endian (if (zero? program-header-count) 0 56) 2)
      (little-endian program-header-count 2)
      (little-endian 64 2)
      (little-endian section-header-count 2)
      (little-endian section-name-index 2)))))

(defn encode-program-header
  "Encode one ELF64 program header. `type` defaults to `:load`."
  [{:keys [type flags offset virtual-address physical-address file-size
           memory-size alignment]
    :or {type :load
         flags 0
         offset 0
         virtual-address 0
         file-size 0
         memory-size 0
         alignment 0}}]
  (let [type-id (enum-value program-types :program-type type)
        physical-address (or physical-address virtual-address)]
    (vec (concat (little-endian type-id 4)
                 (little-endian flags 4)
                 (little-endian offset 8)
                 (little-endian virtual-address 8)
                 (little-endian physical-address 8)
                 (little-endian file-size 8)
                 (little-endian memory-size 8)
                 (little-endian alignment 8)))))

(defn encode-section-header
  "Encode one ELF64 section header. Names are offsets into `.shstrtab`."
  [{:keys [name-offset type flags address offset size link info alignment
           entry-size]
    :or {name-offset 0
         type :null
         flags 0
         address 0
         offset 0
         size 0
         link 0
         info 0
         alignment 0
         entry-size 0}}]
  (let [type-id (enum-value section-types :section-type type)]
    (vec (concat (little-endian name-offset 4)
                 (little-endian type-id 4)
                 (little-endian flags 8)
                 (little-endian address 8)
                 (little-endian offset 8)
                 (little-endian size 8)
                 (little-endian link 4)
                 (little-endian info 4)
                 (little-endian alignment 8)
                 (little-endian entry-size 8)))))

(defn encode-rela
  "Encode one ELF64 RELA entry. `type` is the target ABI relocation number."
  [{:keys [offset symbol-index type addend]
    :or {offset 0 symbol-index 0 addend 0}}]
  (when-not (and (integer? type) (<= 0 type 0xffffffff))
    (throw (ex-info "ELF relocation type must be an unsigned 32-bit integer"
                    {:type type})))
  ;; `r_info` is defined by ELF64 as `(symbol_index << 32) | type`, so its eight
  ;; little-endian bytes ARE `type`'s four followed by `symbol_index`'s four.
  ;; Emitting the halves directly says that, and avoids building a value that
  ;; needs arbitrary precision to hold: the previous form multiplied by 2^32
  ;; with `*'`, which promotes past Long on the JVM and has no cljs equivalent.
  ;; It also makes `symbol-index` range-checked -- it never was, so an index of
  ;; 2^32 or more used to be silently promoted and encoded wrong.
  (vec (concat (little-endian offset 8)
               (little-endian type 4)
               (little-endian symbol-index 4)
               (little-endian addend 8))))

(defn encode-symbol
  "Encode one ELF64 symbol-table entry. `info` contains binding and type bits."
  [{:keys [name-offset info other section-index value size]
    :or {name-offset 0 info 0 other 0 section-index 0 value 0 size 0}}]
  (vec (concat (little-endian name-offset 4)
               (little-endian info 1)
               (little-endian other 1)
               (little-endian section-index 2)
               (little-endian value 8)
               (little-endian size 8))))
