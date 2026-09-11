(ns kyosai.guest-parity-test
  "Parity between the kotoba guest (kotoba/kyosai/guest/core.kotoba) and the
   .cljc oracle (kyosai.core) for the decision layer.

   The oracle remains the record layer; the guest executes the DECISIONS.
   This test asserts the code mapping documented in the guest header:
   the refusal the oracle returns as a map is the code the guest returns
   as an i64, in BOTH directions of every invariant (solidarity
   under/over, boundary inclusive, member gate, non-positive).

   The guest's own kotoba test suite (amu test, 3 targets) is run by the
   repo gate; this file pins the SAME cases against the oracle so a
   drift between the two implementations is red here."
  (:require [clojure.test :refer [deftest is testing]]
            [kyosai.core :as core]
            #?@(:cljs [["node:fs" :as fs]])))

;; --- code mapping (mirrors the guest header) -------------------------------

(def contribution-codes
  {:valid 0 :not-solidarity-amount 1 :unscheduled-round 2 :non-positive-amount 3})

(def draft-codes
  {:drafted 0 :insufficient-pool 1 :member-not-active 2 :non-positive-amount 3})

(defn- oracle-contribution-code
  "Run the oracle's validate-contribution and map its :contribution/error
   to the guest's code vocabulary. schedule-amount nil = the round is
   absent from the schedule (the guest receives <= 0 for that)."
  [amount schedule-amount]
  (let [c (core/contribution "c" "did:web:m" 1 amount)
        v (core/validate-contribution c (if schedule-amount {1 schedule-amount} {}))]
    (get contribution-codes
         (if (:contribution/valid? v)
           :valid
           (:contribution/error v)))))

(defn- oracle-draft-code
  "Run the oracle's draft-payout and map its refusal to the guest's codes."
  [amount balance member-active?]
  (let [pool {:pool/contributions []
              :pool/ratified-payouts []}
        pool (if (pos? balance)
               (core/add-contribution pool
                 (core/contribution "seed" "did:web:m" 1 balance)
                 {1 balance}
                 (core/member "did:web:m" "2026-01-01"))
               pool)
        member-rec (if member-active?
                     (core/member "did:web:m" "2026-01-01")
                     (core/member "did:web:m" "2026-01-01" :status :withdrawn))
        d (core/draft-payout pool
            {:payout/id "p" :payout/member "did:web:m" :payout/amount amount}
            member-rec)]
    (get draft-codes
         (if (= :drafted (:payout/status d))
           :drafted
           (:payout/reason d)))))

;; --- parity: contribution decisions ----------------------------------------

(deftest contribution-parity-both-directions
  (testing "valid exact schedule amount"
    (is (= 0 (oracle-contribution-code 100 100))))
  (testing "UNDER-payment refused (guest code 1)"
    (is (= 1 (oracle-contribution-code 50 100))))
  (testing "OVER-payment refused equally (guest code 1)"
    (is (= 1 (oracle-contribution-code 200 100))))
  (testing "unscheduled round (guest code 2)"
    (is (= 2 (oracle-contribution-code 100 nil))))
  (testing "non-positive amount (guest code 3)"
    (is (= 3 (oracle-contribution-code 0 100)))))

;; --- parity: draft payout decisions ----------------------------------------

(deftest draft-parity-both-directions
  (testing "amount == balance is drafted (boundary inclusive, code 0)"
    (is (= 0 (oracle-draft-code 100 100 true))))
  (testing "amount > balance refused (code 1)"
    (is (= 1 (oracle-draft-code 101 100 true))))
  (testing "inactive member refused (code 2)"
    (is (= 2 (oracle-draft-code 50 100 false))))
  (testing "non-positive amount refused (code 3)"
    (is (= 3 (oracle-draft-code 0 100 true)))))

(deftest the-guest-header-mapping-is-current
  ;; The mapping table at the top of this file must agree with the guest
  ;; file's own header. If either changes, this goes red and forces a
  ;; simultaneous edit of both — the two implementations are one contract.
  (let [guest #?(:cljs (fs/readFileSync "kotoba/kyosai/guest/core.kotoba" "utf8")
                 :clj (slurp "kotoba/kyosai/guest/core.kotoba"))]
    (is (re-find #"1\s+:not-solidarity-amount" guest))
    (is (re-find #"1\s+:insufficient-pool" guest))
    (is (re-find #"2\s+:member-not-active" guest))
    (is (re-find #"3\s+:non-positive-amount" guest))))
