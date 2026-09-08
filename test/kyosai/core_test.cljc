(ns kyosai.core-test
  "Solidarity / non-adjudicating / no-overdraft invariants, pressed from
   BOTH directions (the loose direction and the strict direction). A test
   suite that only checks the safe side lets the silent failure class
   through: a gate that softens a domain refusal looks green."
  (:require [clojure.test :refer [deftest is testing]]
            [kyosai.core :as core]))

(def schedule
  "2026-09 round: every active member pays 1000 yen. 2026-10: 500."
  {1 100 2 100 3 1000 4 500})

(def member-a (core/member "did:web:member-a.example" "2026-01-01"))
(def withdrawn-b (core/member "did:web:member-b.example" "2026-01-01" :status :withdrawn))


(deftest member-construction
  (is (core/active-member? member-a))
  (is (not (core/active-member? withdrawn-b)) "withdrawn member is not active")
  (is (nil? (core/member "not-a-did" "2026-01-01")) "non-did identity refused"))

(deftest schedule-is-not-a-rate-table
  ;; schedule resolves per round; unscheduled rounds return nil and the
  ;; caller refuses — nobody guesses.
  (is (= 100 (core/schedule-amount schedule 1)))
  (is (nil? (core/schedule-amount schedule 99))))

(deftest solidarity-invariant-both-directions
  (testing "exact schedule amount validates"
    (is (:contribution/valid?
         (core/validate-contribution
          (core/contribution "c" "did:web:m" 1 100) schedule))))
  (testing "UNDER-payment refused"
    (let [v (core/validate-contribution
             (core/contribution "c" "did:web:m" 1 50) schedule)]
      (is (not (:contribution/valid? v)))
      (is (= :not-solidarity-amount (:contribution/error v)))
      (is (= 100 (:contribution/expected v)))))
  (testing "OVER-payment refused equally firmly — a discount is risk pricing"
    (let [v (core/validate-contribution
             (core/contribution "c" "did:web:m" 1 200) schedule)]
      (is (not (:contribution/valid? v)))
      (is (= :not-solidarity-amount (:contribution/error v)))))
  (testing "unscheduled round refused"
    (let [v (core/validate-contribution
             (core/contribution "c" "did:web:m" 99 100) schedule)]
      (is (not (:contribution/valid? v)))
      (is (= :unscheduled-round (:contribution/error v)))))
  (testing "non-positive amount refused"
    (let [v (core/validate-contribution
             (core/contribution "c" "did:web:m" 1 0) schedule)]
      (is (not (:contribution/valid? v)))
      (is (= :non-positive-amount (:contribution/error v))))))

(deftest add-contribution-refusals-do-not-mutate-the-pool
  (let [pool (core/empty-pool)
        bad (core/contribution "c" "did:web:member-a.example" 1 50)]
    (testing "solidarity violation returns a refusal, not a mutation"
      (let [r (core/add-contribution pool bad schedule member-a)]
        (is (:pool/refused? r))
        (is (= :not-solidarity-amount (:pool/reason r)))))
    (testing "withdrawn member refused"
      (let [c-ok (core/contribution "c2" "did:web:member-b.example" 1 100)
            r (core/add-contribution pool c-ok schedule withdrawn-b)]
        (is (:pool/refused? r))
        (is (= :member-not-active (:pool/reason r)))))
    (testing "the original pool is untouched by both refusals"
      (is (= 0 (core/pool-balance pool))))))

(defn- add-c
  "Append one valid contribution (round, amount) from member-a to pool."
  [pool round amount]
  (let [c (core/contribution (str "c-" round) "did:web:member-a.example" round amount)
        r (core/add-contribution pool c schedule member-a)]
    (when-not (:pool/refused? r) r)))

(defn- pool-with
  [round amount] (add-c (core/empty-pool) round amount))

(deftest pool-balance-is-derived-and-inclusive-at-the-boundary
  (let [pool (add-c (pool-with 1 100) 2 100)]
    (is (= 200 (core/pool-balance pool)))
    (testing "amount == balance is payable (inclusive boundary)"
      (is (core/can-pay? pool 200)))
    (testing "one more than balance is refused"
      (is (not (core/can-pay? pool 201))))))

(deftest the-two-helper-spellings-are-one-helper
  (is (= (pool-with 1 100) (add-c (core/empty-pool) 1 100))))

(deftest draft-payout-refuses-overdraft-and-inactive-member
  (let [pool (pool-with 1 100)]
    (testing "amount == balance drafts (inclusive)"
      (let [d (core/draft-payout pool {:payout/id "p1"
                                       :payout/member "did:web:member-a.example"
                                       :payout/amount 100
                                       :payout/round 1} member-a)]
        (is (= :drafted (:payout/status d)))))
    (testing "amount > balance refused with both sides named"
      (let [d (core/draft-payout pool {:payout/id "p2"
                                       :payout/member "did:web:member-a.example"
                                       :payout/amount 101
                                       :payout/round 1} member-a)]
        (is (:payout/refused? d))
        (is (= :insufficient-pool (:payout/reason d)))
        (is (= 100 (:payout/balance d)))
        (is (= 101 (:payout/amount d)))))
    (testing "inactive member refused even with funds"
      (let [d (core/draft-payout pool {:payout/id "p3"
                                       :payout/member "did:web:member-b.example"
                                       :payout/amount 50
                                       :payout/round 1} withdrawn-b)]
        (is (:payout/refused? d))
        (is (= :member-not-active (:payout/reason d)))))
    (testing "non-positive amount refused"
      (let [d (core/draft-payout pool {:payout/id "p4"
                                       :payout/member "did:web:member-a.example"
                                       :payout/amount 0
                                       :payout/round 1} member-a)]
        (is (:payout/refused? d))
        (is (= :non-positive-amount (:payout/reason d)))))))

(deftest ratification-is-structurally-not-kyosai-s
  ;; No flag, role, or attestation makes kyosai itself a ratifier.
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"non-adjudicating" (core/ratify-payout {})))
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"non-adjudicating" (core/ratify-payout {} :by :council))))

(deftest apply-ratified-payout-requires-council-attestation
  (let [pool (pool-with 1 100)]
    (testing "ratified record without council attestation refused"
      (let [r (core/apply-ratified-payout pool
                 {:payout/id "p1" :payout/amount 100 :payout/status :ratified})]
        (is (:pool/refused? r))
        (is (= :council-attestation-required (:pool/reason r)))))
    (testing "attested payout within balance records and drains the pool"
      (let [r (core/apply-ratified-payout pool
                 {:payout/id "p1" :payout/amount 100
                  :payout/council-attestation "resolution-2026-09-01"
                  :payout/status :ratified})]
        (is (not (:pool/refused? r)))
        (is (= 0 (core/pool-balance r)))))
    (testing "attested payout OVER balance refused — balance outranks attestation"
      (let [r (core/apply-ratified-payout pool
                 {:payout/id "p2" :payout/amount 500
                  :payout/council-attestation "resolution-2026-09-02"
                  :payout/status :ratified})]
        (is (:pool/refused? r))
        (is (= :insufficient-pool (:pool/reason r)))))))

(deftest pool-never-goes-negative-by-construction
  ;; End-to-end: two contributions, one drain to zero, then any further
  ;; payout — draft AND attested recording — both refuse.
  (let [p0 (core/empty-pool)
        p1 (core/add-contribution p0
             (core/contribution "c1" "did:web:member-a.example" 1 100)
             schedule member-a)
        p2 (core/add-contribution p1
             (core/contribution "c2" "did:web:member-b.example" 1 100)
             schedule member-a)
        p3 (core/apply-ratified-payout p2
             {:payout/id "p1" :payout/amount 200
              :payout/council-attestation "res-1" :payout/status :ratified})
        drained p3]
    (is (= 0 (core/pool-balance drained)))
    (testing "draft against a drained pool refuses"
      (let [d (core/draft-payout drained {:payout/id "p2"
                                          :payout/member "did:web:member-a.example"
                                          :payout/amount 1
                                          :payout/round 2} member-a)]
        (is (:payout/refused? d))
        (is (= :insufficient-pool (:payout/reason d)))))
    (testing "attested recording against a drained pool refuses"
      (let [r (core/apply-ratified-payout drained
                 {:payout/id "p2" :payout/amount 1
                  :payout/council-attestation "res-2" :payout/status :ratified})]
        (is (:pool/refused? r))
        (is (= :insufficient-pool (:pool/reason r)))))))
