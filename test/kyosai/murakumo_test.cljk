(ns kyosai.murakumo-test
  "deny-by-default gate, pressed in BOTH directions (loosen and tighten).
   Sonae/marine-insurance idiom: no-attestation blocks, any single missing
   gate blocks, an explicit false never counts as attested, all four
   attestation shapes are accepted, and blocked plans do not carry
   records."
  (:require [clojure.test :refer [deftest is testing]]
            [kyosai.murakumo :as mk]))

(def all-gates (into #{} mk/common-gates))

(deftest no-attestation-never-produces-an-effect
  (doseq [cell (keys mk/cell-specs)]
    (let [plan (mk/cell-plan cell {:attestations {}})]
      (is (= :blocked (:status plan)) (str cell " blocked without attestations"))
      (is (empty? (:effects plan)) (str cell " produces no effects without attestations"))
      (is (= 7 (count (:missing-gates plan))) (str cell " reports all 7 gates missing")))))

(deftest removing-any-single-required-gate-blocks-the-plan
  ;; 7 gates are AND. If an `every?`/`some` swap made it an OR, a
  ;; no-attestation check still passes — so remove one at a time.
  (doseq [missing mk/common-gates]
    (let [attested (disj all-gates missing)
          plan (mk/cell-plan :health {:attestations attested})]
      (is (= :blocked (:status plan)) (str missing " missing -> blocked"))
      (is (= [missing] (:missing-gates plan)))
      (is (empty? (:effects plan))))))

(deftest all-seven-gates-produce-a-ready-plan
  (let [plan (mk/cell-plan :health {:attestations all-gates :request-id "req-1"})]
    (is (= :ready (:status plan)))
    (is (empty? (:missing-gates plan)))
    (is (= 1 (count (:effects plan))))))

(deftest an-attestation-that-is-explicitly-false-does-not-count-as-attested
  ;; The quietest failure class in this suite: a `contains?`-based gate
  ;; reads {:no-probing-baseline false} as attested.
  (let [attested (assoc (zipmap mk/common-gates (repeat true))
                        :no-probing-baseline false)
        plan (mk/cell-plan :health {:attestations attested})]
    (is (= :blocked (:status plan)))
    (is (= [:no-probing-baseline] (:missing-gates plan)))))

(deftest every-attestation-shape-is-accepted
  (doseq [[label attested] [[:set-of-keywords all-gates]
                            [:keyword-map (zipmap mk/common-gates (repeat true))]
                            [:string-map (zipmap (map name mk/common-gates) (repeat true))]
                            [:set-of-strings (into #{} (map name mk/common-gates))]]]
    (testing (str label)
      (let [plan (mk/cell-plan :health {:attestations attested :request-id "r"})]
        (is (= :ready (:status plan)) (str label " does not reach :ready"))))))

(deftest blocked-plans-do-not-carry-records
  ;; A "plan but do not run" shape would leave computed records reachable
  ;; next to a blocked plan — reconstructing a gated-off write from the
  ;; gate's own output.
  (let [plan (mk/cell-plan :health {:attestations {} :record {:x 1}})]
    (is (= :blocked (:status plan)))
    (is (nil? (:records plan)) "blocked plan carries no :records")))

(deftest effects-are-attributed-to-this-actor
  (let [plan (mk/cell-plan :health {:attestations all-gates :request-id "r"})]
    (doseq [e (:effects plan)]
      (is (= mk/actor-did (:actor e)) "effects attribute to this actor only")
      (is (= :mst/put-record (:op e))))))

(deftest effects-only-target-declared-collections
  (doseq [[cell spec] mk/cell-specs]
    (let [plan (mk/cell-plan cell {:attestations all-gates :request-id "r"})]
      (is (= (set (:collections spec)) (set (map :collection (:effects plan))))
          (str cell " writes only to declared collections")))))

(deftest unknown-cells-throw-rather-than-plan-nothing
  (is (thrown-with-msg? #?(:clj Exception :cljs js/Error) #"unknown cell" (mk/cell-plan :no-such-cell {:attestations all-gates}))))

(deftest all-cell-plans-covers-every-cell-and-blocks-them-all
  (let [plans (mk/all-cell-plans {:attestations {}})]
    (is (= (set (keys mk/cell-specs)) (set (keys plans))))
    (is (every? #(= :blocked (:status %)) (vals plans)))))

(deftest five-cells-and-seven-gates
  ;; Both-direction census: drift either way turns this red.
  (is (= 5 (count mk/cell-specs)))
  (is (= 7 (count mk/common-gates)))
  (is (= (count mk/common-gates) (count (set mk/common-gates))) "no duplicate gates"))

(deftest the-boundary-namespaces-do-not-collide
  (doseq [[_ spec] mk/cell-specs]
    (doseq [c (:collections spec)]
      (is (re-find #"^com\.itonami\.kyosai\." c) (str c " is under the kyosai namespace")))))
