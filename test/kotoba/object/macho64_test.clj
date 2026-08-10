(ns kotoba.object.macho64-test
  (:require [clojure.test :refer [deftest is testing]]
            [kotoba.object.macho64 :as macho]))

(def object-record
  {:machine :aarch64
   :platform :ios
   :minimum-os [12 0 0]
   :sections [{:segment "__TEXT" :name "__text" :align 2
               :flags 0x80000000 :bytes [0xc0 0x03 0x5f 0xd6]}
              {:segment "__TEXT" :name "__const" :align 0
               :flags 0 :bytes (vec (concat (map int "aarch64-ios-kotoba-v1") [0]))}]
   :symbols [{:name "_kotoba_ios_code_start" :section 1 :value 0 :external? true}
             {:name "_kotoba_ios_code_end" :section 1 :value 4 :external? true}
             {:name "_kotoba_ios_entry" :section 1 :value 0 :external? true}
             {:name "_kotoba_ios_target_profile" :section 2 :value 0 :external? true}]})

(deftest encodes-a-bounded-arm64-ios-object
  (let [bytes (macho/encode-object object-record)]
    (is (= [0xcf 0xfa 0xed 0xfe] (subvec bytes 0 4)))
    (is (= [0x0c 0x00 0x00 0x01] (subvec bytes 4 8)))
    (is (= 3 (nth bytes 16)))
    (is (= [0x19 0 0 0] (subvec bytes 32 36)))
    (is (= [0x32 0 0 0] (subvec bytes 264 268)))
    (is (= [0x02 0 0 0] (subvec bytes 288 292)))
    (is (< (count bytes) 4096))))

(deftest object-encoding-is-deterministic
  (is (= (macho/encode-object object-record)
         (macho/encode-object object-record))))

(deftest malformed-records-fail-closed
  (testing "unknown machine"
    (is (thrown? clojure.lang.ExceptionInfo
                 (macho/encode-object (assoc object-record :machine :x86-64)))))
  (testing "oversize section name"
    (is (thrown? clojure.lang.ExceptionInfo
                 (macho/encode-object
                  (assoc-in object-record [:sections 0 :name]
                            "__section_name_too_long")))))
  (testing "invalid symbol section"
    (is (thrown? clojure.lang.ExceptionInfo
                 (macho/encode-object
                  (assoc-in object-record [:symbols 0 :section] 3)))))
  (testing "relocations remain an explicit gap"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not supported yet"
                          (macho/encode-object
                           (assoc-in object-record [:sections 0 :relocations]
                                     [{:offset 0}]))))))
