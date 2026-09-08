(ns kyosai.murakumo
  "deny-by-default actor boundary for the 共済 (kyosai) actor.

   Mirrors the sonae/marine-insurance idiom: every cell produces a PLAN,
   and the plan produces effects ONLY when all required attestations are
   present. Effects are data ({:op :mst/put-record ...}) — the executor
   does not live in this repo. The plan also refuses to carry draft
   payout records (:records) when blocked, so a gated-off write can never
   be reconstructed from the gate's own output.

   Domain arithmetic lives in kyosai.core; this namespace owns ONLY the
   gate decision. The core's own refusals (:payout/refused?,
   :pool/refused?) are part of the domain layer and pass through here
   unchanged — the boundary does not soften a domain refusal into an
   effect."
  (:require [clojure.string :as str]))

(def actor-did
  "did:web:kyosai.itonami.cloud")

(def common-gates
  [:council-charter-attestation
   :no-platform-held-key-baseline
   :no-probing-baseline
   :murakumo-only-inference-baseline
   :did-primary-baseline
   :append-only-gate-baseline
   :kotoba-only-substrate-baseline])

(defn collection
  [name]
  (str "com.itonami.kyosai." name))

(def cell-specs
  {;; every active member owes the schedule amount for the round
   :collect-contribution
   {:legacy-cell "collect-contribution"
    :phase :event
    :murakumo-node "reuben"
    :collections [(collection "contribution")]
    :required-gates common-gates
    :trigger "manifest cell collect-contribution"
    :ceiling "Solidarity schedule arithmetic in kyosai.core; explicit execution stays in runtime methods"}

   :draft-payout
   {:legacy-cell "draft-payout"
    :phase :event
    :murakumo-node "reuben"
    :collections [(collection "payout-request")]
    :required-gates common-gates
    :trigger "manifest cell draft-payout"
    :ceiling "Drafts only; ratification belongs to the member council"}

   :record-ratified-payout
   {:legacy-cell "record-ratified-payout"
    :phase :event
    :murakumo-node "reuben"
    :collections [(collection "ratified-payout")]
    :required-gates common-gates
    :trigger "manifest cell record-ratified-payout"
    :ceiling "Council attestation required; pool balance invariant enforced"}

   :pool-status
   {:legacy-cell "pool-status"
    :phase :event
    :murakumo-node "reuben"
    :collections [(collection "pool-status")]
    :required-gates common-gates
    :trigger "manifest cell pool-status"
    :ceiling "Read-only derived view of the pool"}

   :health
   {:legacy-cell "health"
    :phase :event
    :murakumo-node "reuben"
    :collections [(collection "health")]
    :required-gates common-gates
    :trigger "manifest cell health"
    :ceiling "Liveness probe"}})

;; ---------------------------------------------------------------------------
;; gate machinery (sonae idiom)
;; ---------------------------------------------------------------------------

(defn safe-rkey
  [s]
  (let [clean (-> (str s)
                  (str/replace #"^did:web:" "")
                  (str/replace #"[^A-Za-z0-9._~-]" "-"))]
    (if (str/blank? clean) "unknown" clean)))

(defn gate-value
  [attestations gate]
  (or (get attestations gate)
      (get attestations (name gate))
      (when (set? attestations) (attestations gate))
      (when (set? attestations) (attestations (name gate)))))

(defn missing-gates
  [spec attestations]
  (->> (:required-gates spec)
       (remove #(boolean (gate-value attestations %)))
       vec))

(defn put-record-effect
  [collection rkey record]
  {:op :mst/put-record
   :actor actor-did
   :collection collection
   :rkey rkey
   :record record})

(defn records-for
  [spec {:keys [records record computed-at request-id]}]
  (let [input-records (cond
                        (map? records) records
                        (some? record) {0 record}
                        :else {})
        base {:actorDid actor-did
              :computedAt computed-at
              :legacyCell (:legacy-cell spec)
              :phase (:phase spec)
              :requestId request-id
              :actorBoundary "kyosai-deny-by-default-boundary"
              :scaffold true
              :constitutionalStatus "attested-plan"}]
    (map-indexed
     (fn [idx coll]
       (let [record* (merge {:$type coll}
                            base
                            (or (get input-records coll)
                                (get input-records idx)
                                {}))
             rkey (safe-rkey (or (:rkey record*)
                                 (get record* "rkey")
                                 (:tid record*)
                                 request-id
                                 (str (:legacy-cell spec) "-" idx)))]
         {:collection coll
          :record record*
          :rkey rkey}))
     (:collections spec))))

(defn cell-plan
  [cell-key {:keys [attestations] :as input}]
  (let [spec (get cell-specs cell-key)]
    (when-not spec
      (throw (ex-info "unknown cell" {:cell cell-key})))
    (let [missing (missing-gates spec attestations)]
      (merge
       {:cell cell-key
        :legacy-cell (:legacy-cell spec)
        :actor actor-did
        :phase (:phase spec)
        :murakumo-node (:murakumo-node spec)
        :trigger (:trigger spec)
        :ceiling (:ceiling spec)
        :required-gates (:required-gates spec)
        :missing-gates missing}
       (if (seq missing)
         {:status :blocked
          :effects []}
         (let [planned-records (records-for spec input)]
           {:status :ready
            :records (vec planned-records)
            :effects (mapv (fn [{:keys [collection record rkey]}]
                             (put-record-effect collection rkey record))
                           planned-records)}))))))

(defn all-cell-plans
  [input]
  (into {}
        (map (fn [cell-key] [cell-key (cell-plan cell-key input)]))
        (keys cell-specs)))
