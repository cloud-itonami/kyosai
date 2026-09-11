(ns kyosai.core
  "共済 (kyosai) — mutual-aid pool core: pure data contracts.

   A cloud-itonami actor for mutual-aid (共済) as the solidarity alternative
   to risk-priced insurance: members pay an EQUAL, schedule-driven
   contribution into a shared pool, and verified losses are paid out of the
   pool. This namespace is the domain layer under the deny-by-default actor
   boundary (kyosai.murakumo); it holds the arithmetic the boundary gates.

   Three invariants are structural here, not policy:

   1. SOLIDARITY, NOT RISK PRICING — every active member of a round pays
      exactly the schedule amount for that round. There is no per-member
      rate, no discount, no loading, no risk factor input anywhere in this
      namespace. `validate-contribution` refuses any amount other than the
      schedule amount, in BOTH directions (over- and under-payment).
   2. NON-ADJUDICATING — kyosai DRAFTS payout requests. It never approves,
      denies or grades a loss; ratification belongs to the member council.
      `ratify-payout` throws unconditionally. Ratifications only ENTER this
      system as already-ratified records carrying a council attestation
      (`apply-ratified-payout` refuses without one).
   3. NO CUSTODY, NO OVERDRAFT — the pool is a derived balance over
      recorded contributions and ratified payouts, not an account. A payout
      request equal to the remaining balance is allowed (the boundary is
      inclusive); one more than the balance is refused
      (`:insufficient-pool`). The pool can never go negative by
      construction.

   Amounts are integers in the smallest currency unit (yen). No network,
   no I/O, no clock. Portable .cljc."

  (:require [kotoba.lang.text :as str]))

;; ---------------------------------------------------------------------------
;; Member — opt-in registry
;; ---------------------------------------------------------------------------

(def member-statuses #{:active :withdrawn})

(defn member
  "Construct a member-registry record. status defaults to :active."
  [did joined-on & {:keys [status]}]
  (when (and (string? did) (str/starts-with? did "did:"))
    {:member/did      did
     :member/joined-on joined-on
     :member/status    (or status :active)}))

(defn active-member?
  "True when the member record exists and is :active."
  [m]
  (and (some? m) (= :active (:member/status m))))

;; ---------------------------------------------------------------------------
;; Solidarity contribution schedule — NOT a rate table
;; ---------------------------------------------------------------------------

(defn schedule-amount
  "The contribution amount every active member owes in `round`, resolved
  from the operator-supplied schedule. schedule is a map of round ->
  amount. Returns nil for an unscheduled round — callers refuse, they do
  not guess."
  [schedule round]
  (get schedule round))

(defn contribution
  "Construct a contribution record."
  [id member-did round amount & {:keys [currency recorded-at]}]
  {:contribution/id          id
   :contribution/member      member-did
   :contribution/round       round
   :contribution/amount      amount
   :contribution/currency    (or currency "JPY")
   :contribution/recorded-at recorded-at})

(defn validate-contribution
  "Validate a contribution against the schedule. The solidarity invariant
  is exact: the amount must EQUAL the schedule amount for the round —
  over-payment is refused as firmly as under-payment, because both are
  risk pricing by another name (a discount prices the member as safer; a
  surcharge prices them as riskier)."
  [c schedule]
  (cond
    (not (map? c))                        {:contribution/valid? false :contribution/error :not-a-map}
    (not (:contribution/id c))            {:contribution/valid? false :contribution/error :missing-id}
    (not (:contribution/member c))        {:contribution/valid? false :contribution/error :missing-member}
    (not (pos? (or (:contribution/amount c) 0)))
    {:contribution/valid? false :contribution/error :non-positive-amount}
    (nil? (schedule-amount schedule (:contribution/round c)))
    {:contribution/valid? false :contribution/error :unscheduled-round}
    (not= (:contribution/amount c)
          (schedule-amount schedule (:contribution/round c)))
    {:contribution/valid? false
     :contribution/error  :not-solidarity-amount
     :contribution/expected (schedule-amount schedule (:contribution/round c))}
    :else                                 {:contribution/valid? true}))

;; ---------------------------------------------------------------------------
;; Pool — derived balance, never an account
;; ---------------------------------------------------------------------------

(defn empty-pool
  "An empty pool: no contributions, no ratified payouts."
  []
  {:pool/contributions      []
   :pool/ratified-payouts   []})

(defn add-contribution
  "Record a contribution into the pool. Refuses (throws) on a malformed
   record and returns a refusal map on a solidarity/schedule violation —
   the pool is never mutated by an invalid contribution."
  [pool c schedule member-rec]
  (let [v (validate-contribution c schedule)]
    (if-not (:contribution/valid? v)
      {:pool/refused? true :pool/reason (:contribution/error v)}
      (if-not (active-member? member-rec)
        {:pool/refused? true :pool/reason :member-not-active}
        {:pool/contributions    (conj (:pool/contributions pool) c)
         :pool/ratified-payouts (:pool/ratified-payouts pool)}))))

(defn- sum-by
  [k xs]
  (reduce + 0 (map k xs)))

(defn pool-balance
  "The derived pool balance: contributions in minus ratified payouts out.
   Non-negative by construction (both mutation paths refuse anything that
   would overdraw)."
  [pool]
  (- (sum-by :contribution/amount (:pool/contributions pool))
     (sum-by :payout/amount (:pool/ratified-payouts pool))))

(defn can-pay?
  "True when `amount` can be paid out of the pool WITHOUT overdrawing it.
   The boundary is INCLUSIVE: an amount exactly equal to the balance is
   payable — draining the pool to zero is a normal mutual-aid outcome, not
   a violation. Only strictly-more-than-the-balance is refused."
  [pool amount]
  (and (pos? (or amount 0))
       (<= amount (pool-balance pool))))

;; ---------------------------------------------------------------------------
;; Payout request — DRAFT only (non-adjudicating)
;; ---------------------------------------------------------------------------

;; kyosai can only ever PRODUCE this one status. :ratified/:paid arrive
;; from outside (the council / the payment rail) and enter only via
;; apply-ratified-payout.
(def payout-request-statuses #{:drafted})

(defn draft-payout
  "Draft a payout request against the pool. Fail-closed refusals:

     :insufficient-pool  — the requested amount exceeds the balance
     :member-not-active  — the claimant is not an active registry member
     :non-positive-amount — the amount is zero or negative

   An amount exactly equal to the balance is DRAFTED (boundary inclusive).
   The returned record is a request for the member council to ratify; it
   is never a payment."
  [pool {:keys [payout/id payout/member payout/amount payout/round
                payout/requested-on payout/reason]} member-rec]
  (cond
    (not (pos? (or amount 0)))
    {:payout/refused? true :payout/reason :non-positive-amount}

    (not (active-member? member-rec))
    {:payout/refused? true :payout/reason :member-not-active}

    (not (can-pay? pool amount))
    {:payout/refused? true
     :payout/reason   :insufficient-pool
     :payout/balance  (pool-balance pool)
     :payout/amount   amount}

    :else
    {:payout/id          id
     :payout/member      member
     :payout/round       round
     :payout/amount      amount
     :payout/requested-on requested-on
     :payout/reason      reason
     :payout/status      :drafted}))

(defn ratify-payout
  "Ratification is the member council's authority, structurally not
   kyosai's. This function exists so the boundary is EXPLICIT: it refuses
   unconditionally. There is no flag, no role, no attestation that makes
   kyosai itself a ratifier."
  [_ & _]
  (throw (ex-info "kyosai is non-adjudicating: ratification belongs to the member council" {})))

;; ---------------------------------------------------------------------------
;; Recording an ALREADY-RATIFIED payout (council evidence required)
;; ---------------------------------------------------------------------------

(defn apply-ratified-payout
  "Record a payout that the member council has ALREADY ratified. The
   record must carry a truthy :payout/council-attestation (the council's
   own evidence — a resolution id, a signed minute, a capability); a
   ratified record without one is refused. Defense in depth: even a
   council-attested payout is refused if it would overdraw the pool —
   the balance invariant outranks any attestation."
  [pool ratified]
  (cond
    (not (map? ratified))
    {:pool/refused? true :pool/reason :not-a-map}

    (not (:payout/id ratified))
    {:pool/refused? true :pool/reason :missing-id}

    (not (:payout/council-attestation ratified))
    {:pool/refused? true :pool/reason :council-attestation-required}

    (not (pos? (or (:payout/amount ratified) 0)))
    {:pool/refused? true :pool/reason :non-positive-amount}

    (> (:payout/amount ratified) (pool-balance pool))
    {:pool/refused? true
     :pool/reason   :insufficient-pool
     :payout/balance (pool-balance pool)
     :payout/amount  (:payout/amount ratified)}

    :else
    {:pool/contributions      (:pool/contributions pool)
     :pool/ratified-payouts   (conj (:pool/ratified-payouts pool) ratified)}))
