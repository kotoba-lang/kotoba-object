(ns kotoba.object.pe32plus
  "Validated PE32+ record and image encoder.

  This namespace owns container bytes only. Callers retain target policy,
  entry shims, section placement decisions, imports, and relocation choice."
  (:require [clojure.string :as str]))

(def machine-codes {:x86-64 0x8664 :aarch64 0xaa64})
(def subsystem-codes {:efi-application 10})
(def max-section-count 32)
(def max-image-bytes (* 64 1024 1024))

(defn little-endian [n width]
  (when-not (and (integer? n) (integer? width) (<= 1 width 8) (<= 0 n))
    (throw (ex-info "invalid little-endian field" {:value n :width width})))
  (mapv #(bit-and (unsigned-bit-shift-right (long n) (* 8 %)) 0xff)
        (range width)))

(defn align-up [n alignment]
  (when-not (and (integer? n) (<= 0 n)
                 (integer? alignment) (pos? alignment)
                 (zero? (bit-and alignment (dec alignment))))
    (throw (ex-info "invalid PE alignment" {:value n :alignment alignment})))
  (* alignment (quot (+ n (dec alignment)) alignment)))

(defn pad-to [bytes size]
  (let [bytes (vec bytes)]
    (when-not (and (integer? size) (<= 0 size max-image-bytes))
      (throw (ex-info "invalid PE region allocation" {:size size})))
    (when (> (count bytes) size)
      (throw (ex-info "PE32+ region exceeds its allocation"
                      {:size size :actual (count bytes)})))
    (into bytes (repeat (- size (count bytes)) 0))))

(defn- ascii-bytes [value]
  (when-not (and (string? value) (<= 1 (count value) 8)
                 (every? #(<= 0x20 (int %) 0x7e) value))
    (throw (ex-info "PE section name must be 1-8 printable ASCII bytes"
                    {:name value})))
  (mapv int value))

(defn encode-section-header
  [{:keys [name virtual-size rva raw-size raw-offset characteristics]}]
  (doseq [[field value] [[:virtual-size virtual-size] [:rva rva]
                         [:raw-size raw-size] [:raw-offset raw-offset]
                         [:characteristics characteristics]]]
    (when-not (and (integer? value) (<= 0 value 0xffffffff))
      (throw (ex-info "invalid PE section field" {:field field :value value}))))
  (vec (concat (pad-to (ascii-bytes name) 8)
               (little-endian virtual-size 4) (little-endian rva 4)
               (little-endian raw-size 4) (little-endian raw-offset 4)
               (repeat 12 0) (little-endian characteristics 4))))

(defn encode-optional-header
  [{:keys [entry-rva text-rva text-size initialized-size image-size headers-size
           image-base section-alignment file-alignment subsystem
           dll-characteristics stack-reserve stack-commit heap-reserve heap-commit
           data-directories]}]
  (let [subsystem-code (get subsystem-codes subsystem)]
    (when-not subsystem-code
      (throw (ex-info "unsupported PE subsystem" {:subsystem subsystem})))
    (when-not (= 0xf0 (+ 112 (* 16 8)))
      (throw (ex-info "internal PE32+ optional-header size mismatch" {})))
    (let [fixed (vec (concat
                      (little-endian 0x20b 2) [0 0]
                      (little-endian (align-up text-size file-alignment) 4)
                      (little-endian initialized-size 4) (little-endian 0 4)
                      (little-endian entry-rva 4) (little-endian text-rva 4)
                      (little-endian image-base 8)
                      (little-endian section-alignment 4)
                      (little-endian file-alignment 4)
                      (little-endian 2 2) (little-endian 0 2)
                      (little-endian 0 2) (little-endian 0 2)
                      (little-endian 2 2) (little-endian 0 2)
                      (little-endian 0 4) (little-endian image-size 4)
                      (little-endian headers-size 4) (little-endian 0 4)
                      (little-endian subsystem-code 2)
                      (little-endian dll-characteristics 2)
                      (little-endian stack-reserve 8) (little-endian stack-commit 8)
                      (little-endian heap-reserve 8) (little-endian heap-commit 8)
                      (little-endian 0 4) (little-endian 16 4)))
          directories (mapcat (fn [index]
                                (let [{:keys [rva size]} (get data-directories index
                                                                 {:rva 0 :size 0})]
                                  (concat (little-endian rva 4)
                                          (little-endian size 4))))
                              (range 16))]
      (vec (concat fixed directories)))))

(defn encode-image
  "Encode a fully laid-out PE32+ image. Section bytes and placement are caller
  decisions; this function validates their record fields and materializes the
  DOS, COFF, optional, section-table, and padded raw regions."
  [{:keys [machine entry-rva text-rva image-base section-alignment file-alignment
           headers-size image-size subsystem dll-characteristics sections
           data-directories stack-reserve stack-commit heap-reserve heap-commit]
    :or {image-base 0x400000 section-alignment 0x1000 file-alignment 0x200
         headers-size 0x200 subsystem :efi-application
         dll-characteristics 0x160 stack-reserve 0x100000 stack-commit 0x1000
         heap-reserve 0x100000 heap-commit 0x1000 data-directories {}}}]
  (let [machine-code (get machine-codes machine)
        sections (vec sections)]
    (when-not machine-code
      (throw (ex-info "unsupported PE machine" {:machine machine})))
    (when-not (<= 1 (count sections) max-section-count)
      (throw (ex-info "invalid PE section count" {:count (count sections)})))
    (doseq [[left right] (partition 2 1 (sort-by :raw-offset sections))]
      (when (> (+ (:raw-offset left) (:raw-size left)) (:raw-offset right))
        (throw (ex-info "overlapping PE raw sections"
                        {:left (:name left) :right (:name right)}))))
    (doseq [section sections]
      (when-not (= (:raw-size section)
                   (align-up (count (:bytes section)) file-alignment))
        (throw (ex-info "PE section raw size is not canonical"
                        {:section (:name section) :raw-size (:raw-size section)
                         :bytes (count (:bytes section))}))))
    (let [text (first sections)
          initialized-size (reduce + 0 (map :raw-size (rest sections)))
          optional (encode-optional-header
                    {:entry-rva entry-rva :text-rva text-rva
                     :text-size (count (:bytes text))
                     :initialized-size initialized-size :image-size image-size
                     :headers-size headers-size :image-base image-base
                     :section-alignment section-alignment :file-alignment file-alignment
                     :subsystem subsystem :dll-characteristics dll-characteristics
                     :stack-reserve stack-reserve :stack-commit stack-commit
                     :heap-reserve heap-reserve :heap-commit heap-commit
                     :data-directories data-directories})
          coff (vec (concat (little-endian machine-code 2)
                            (little-endian (count sections) 2)
                            (repeat 12 0) (little-endian 0xf0 2)
                            (little-endian 0x22 2)))
          dos (assoc (vec (repeat 0x80 0)) 0 0x4d 1 0x5a 0x3c 0x80)
          headers (pad-to (concat dos [0x50 0x45 0 0] coff optional
                                  (mapcat encode-section-header sections))
                          headers-size)
          bytes (reduce (fn [out {:keys [raw-offset raw-size bytes name]}]
                          (when (> (count out) raw-offset)
                            (throw (ex-info "PE section overlaps headers or prior data"
                                            {:section name :offset raw-offset})))
                          (into (pad-to out raw-offset) (pad-to bytes raw-size)))
                        headers (sort-by :raw-offset sections))]
      (when (> (count bytes) max-image-bytes)
        (throw (ex-info "PE image exceeds byte limit"
                        {:bytes (count bytes) :limit max-image-bytes})))
      bytes)))
