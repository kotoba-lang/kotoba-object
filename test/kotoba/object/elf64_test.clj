(ns kotoba.object.elf64-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.object.elf64 :as elf64]))

(deftest integer-and-padding-contract
  (is (= [0x78 0x56 0x34 0x12]
         (elf64/little-endian 0x12345678 4)))
  (is (= [0xff 0xff 0xff 0xff 0xff 0xff 0xff 0xff]
         (elf64/little-endian -1 8)))
  (is (= [1 2 0 0] (elf64/pad-to [1 2] 4)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"does not fit"
                        (elf64/little-endian 256 1)))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"exceeds"
                        (elf64/pad-to [1 2 3] 2))))

(deftest executable-header-contract
  (let [header (elf64/encode-header
                {:type :executable
                 :machine :x86-64
                 :entry 0x101000
                 :program-header-offset 64
                 :program-header-count 2
                 :section-header-offset 0x2040
                 :section-header-count 4
                 :section-name-index 3})]
    (is (= 64 (count header)))
    (is (= [0x7f 0x45 0x4c 0x46 2 1 1] (subvec header 0 7)))
    (is (= [2 0] (subvec header 16 18)))
    (is (= [0x3e 0] (subvec header 18 20)))
    (is (= [2 0] (subvec header 56 58)))
    (is (= [4 0 3 0] (subvec header 60 64)))))

(deftest record-widths-and-defaults
  (testing "load segment"
    (let [record (elf64/encode-program-header
                  {:flags 5 :offset 0x1000 :virtual-address 0x101000
                   :file-size 32 :memory-size 32 :alignment 0x1000})]
      (is (= 56 (count record)))
      (is (= [1 0 0 0 5 0 0 0] (subvec record 0 8)))))
  (testing "section header"
    (is (= 64 (count (elf64/encode-section-header
                      {:name-offset 1 :type :progbits :flags 6
                       :alignment 16})))))
  (testing "relocation and symbol records"
    (is (= 24 (count (elf64/encode-rela
                      {:offset 3 :symbol-index 2 :type 2 :addend -4}))))
    (is (= 24 (count (elf64/encode-symbol
                      {:name-offset 1 :info 0x12 :section-index 1 :size 12}))))))

(deftest unsupported-identifiers-are-rejected
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown ELF machine"
                        (elf64/encode-header {:type :executable
                                              :machine :mips})))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"relocation type"
                        (elf64/encode-rela {:type -1}))))
