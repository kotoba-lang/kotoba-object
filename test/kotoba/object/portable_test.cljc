(ns kotoba.object.portable-test
  "The width/encoding contract, written to run on BOTH runtimes.

   The .clj suites next to this one are the JVM oracle. This file exists
   because `kotoba.object.elf64` and `.macho64` carry a `.cljc` extension --
   a claim that they load under ClojureScript. Until 2026-08-20 that claim was
   false: both used `*'`, which cljs cannot resolve, so the extension asserted a
   portability nothing had ever executed. A rename is not evidence; running is."
  (:require [clojure.test :refer [deftest is]]
            [kotoba.object.elf64 :as elf64]
            [kotoba.object.macho64 :as macho64]
            [kotoba.object.pe32plus :as pe32plus]))

(deftest elf-little-endian-encodes-both-signs
  (is (= [0] (elf64/little-endian 0 1)))
  (is (= [255] (elf64/little-endian 255 1)))
  (is (= [255] (elf64/little-endian -1 1)))
  (is (= [128] (elf64/little-endian -128 1)))
  (is (= [0 255] (elf64/little-endian -256 2)))
  (is (= [255 255] (elf64/little-endian -1 2)))
  (is (= [1 0 0 0] (elf64/little-endian 1 4)))
  (is (= [0 0 0 128] (elf64/little-endian 2147483648 4)))
  (is (= [255 255 255 255] (elf64/little-endian -1 4)))
  ;; width 8, the case the old implementation could not do in cljs: it formed
  ;; 2^64 + n, which as a double is spaced 2048 apart and loses these bytes.
  (is (= [255 255 255 255 255 255 255 255] (elf64/little-endian -1 8)))
  (is (= [0 255 255 255 255 255 255 255] (elf64/little-endian -256 8)))
  (is (= [1 0 0 0 0 0 0 0] (elf64/little-endian 1 8)))
  (is (= [0 16 0 0 0 0 0 0] (elf64/little-endian 4096 8))))

(deftest elf-little-endian-rejects-out-of-range
  (is (thrown? #?(:clj Exception :cljs js/Error) (elf64/little-endian 256 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (elf64/little-endian -129 1)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (elf64/little-endian 0 3))))

(deftest macho-little-endian-is-unsigned
  (is (= [0] (macho64/little-endian 0 1)))
  (is (= [255] (macho64/little-endian 255 1)))
  (is (= [0 16 0 0] (macho64/little-endian 4096 4)))
  (is (thrown? #?(:clj Exception :cljs js/Error) (macho64/little-endian -1 1))))

(deftest elf-rela-packs-r-info-as-two-halves
  ;; r_info = (symbol_index << 32) | type
  (is (= [0 0 0 0 0 0 0 0
          1 0 0 0 7 0 0 0
          0 0 0 0 0 0 0 0]
         (elf64/encode-rela {:offset 0 :symbol-index 7 :type 1 :addend 0}))))

(deftest section-names-encode-as-ascii-on-both-runtimes
  ;; This suite previously covered `little-endian` only, so it proved the files
  ;; LOAD under ClojureScript without proving the name paths RUN there.
  ;; `(int c)` yields a code point on the JVM, where iterating a string gives
  ;; Characters, and NaN in cljs, where it gives single-character strings --
  ;; so the validator rejected every name. Found 2026-08-20 by building a real
  ;; PE image from amu, not by reading.
  (is (= [0x74 0x65 0x78 0x74] (vec (take 4 (pe32plus/encode-section-header
                                             {:name "text" :virtual-size 1 :rva 0x1000
                                              :raw-size 512 :raw-offset 0x200
                                              :characteristics 0})))))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (pe32plus/encode-section-header
                {:name "" :virtual-size 1 :rva 0x1000 :raw-size 512
                 :raw-offset 0x200 :characteristics 0})))
  (is (thrown? #?(:clj Exception :cljs js/Error)
               (pe32plus/encode-section-header
                {:name "toolongname" :virtual-size 1 :rva 0x1000 :raw-size 512
                 :raw-offset 0x200 :characteristics 0}))))
