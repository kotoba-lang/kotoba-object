(ns kotoba.object.pe32plus-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.object.pe32plus :as pe]))

(def image
  {:machine :x86-64 :entry-rva 0x1000 :text-rva 0x1000 :image-size 0x3000
   :sections [{:name ".text" :virtual-size 1 :rva 0x1000
               :raw-size 0x200 :raw-offset 0x200
               :characteristics 0x60000020 :bytes [0xc3]}]})

(deftest encodes-a-bounded-pe32+-image
  (let [bytes (pe/encode-image image)]
    (is (= 0x400 (count bytes)))
    (is (= [0x4d 0x5a] (subvec bytes 0 2)))
    (is (= [0x50 0x45 0 0] (subvec bytes 0x80 0x84)))
    (is (= 0xc3 (nth bytes 0x200)))))

(deftest records-fail-closed
  (testing "unknown machine"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pe/encode-image (assoc image :machine :unknown)))))
  (testing "noncanonical raw size"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pe/encode-image (assoc-in image [:sections 0 :raw-size] 1)))))
  (testing "oversize section name"
    (is (thrown? clojure.lang.ExceptionInfo
                 (pe/encode-image (assoc-in image [:sections 0 :name] ".too-long"))))))
