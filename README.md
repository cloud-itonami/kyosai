# kyosai (共済) — Mutual-Aid Pool Actor: solidarity contributions, council-ratified payouts

**共済 (mutual-aid) actor の domain 層 + deny-by-default boundary。** 共済の掛け金・
支払いの純粋計算 (`src/kyosai/core.cljc`) と、attestation が揃わなければ effect を
1 つも出さない gate (`src/kyosai/murakumo.cljc`)。**共済の引受・査定・支払いの実行は、
ここには無い。**

`did:web:kyosai.itonami.cloud`（名乗り。DNS 解決の配信は別作業）

## この repo は何であるか

| | ここにあるか |
|---|---|
| 共済の domain 計算（掛金 schedule・pool 残高・payout draft・ratified-payout 記録） | **ある**（`kyosai.core`、純粋 `.cljc`） |
| **gate**（attestation が揃わなければ effect を 1 つも出さない判断） | **ある**（`kyosai.murakumo`、5 cell × 7 gate） |
| pool の実保管、支払い rail、 Council の審議・裁定 | **無い** |

**ここには動くサービスは無い。** `cell-plan` が返すのは `{:op :mst/put-record ...}`
という**計画**であって、書き込みそのものではない。実行する者はこの repo に居ない。

## 3 つの構造的不変条件（policy ではなく構造）

1. **SOLIDARITY, NOT RISK PRICING** — 同一 round の active member は全員、
   schedule 金額を**ちょうど**払う。過払いも不足も拒否される
   （値引きも割増もリスクプライシングの別形だから）。
   `validate-contribution` は両方向に拒否する。
2. **NON-ADJUDICATING** — kyosai は payout request を **draft するだけ**。
   裁定（ratify）は member council の権限で、`ratify-payout` は
   **無条件に throw する**。裁定済み記録は `:payout/council-attestation` を
   持つものだけが `apply-ratified-payout` で入る。
3. **NO CUSTODY, NO OVERDRAFT** — pool は口座ではなく、記録された掛金と
   裁定済み支払いから**導出される残高**。残高ちょうどの支払いは可
   （pool を 0 にするのは正常な共済の帰結）、残高超過は
   `:insufficient-pool` で拒否。**pool は構造的に負にならない。**

## 確かめる

```bash
nbb --classpath src:test run_tests.cljs
```

最後の行が `kyosai actor: all green` なら緑（2026-09-08 実測: 22 test /
111 assertion）。

見ているもの:

- **solidarity の両方向** — 不足払いと過払いの**両方**が
  `:not-solidarity-amount` で落ちる（片方向だけの test は値引きの silently-pass を取り逃がす）
- **裁定の構造的排除** — `ratify-payout` がどの引数でも throw すること /
  council attestation 無しの ratified 記録が拒否されること
- **pool の非負** — 残高ちょうどの drain は通り、その後の draft も
  attested 記録も `:insufficient-pool` で落ちること（boundary inclusive の境界値 test 含む）
- **gate が緩む方向 / きつくなる方向の両方** — 無 attest で blocked /
  7 gate の AND（1 本ずつ抜いて確認）/ `false` を attest とみなさない /
  attestation の 4 形を全部受ける / blocked な plan は `:records` を持ち歩かない /
  effect は宣言外の collection に書かない

## ここにあるもの

| ファイル | 役割 |
|---|---|
| `src/kyosai/core.cljc` | **domain 層。** member / schedule / contribution / pool / payout の純粋計算と拒否 |
| `src/kyosai/murakumo.cljc` | **deny-by-default boundary。** 5 cell × 7 gate の plan 判断 |
| `kotoba/kyosai/guest/core.kotoba` | **kotoba guest。** 判断層（solidarity / boundary / member gate / non-positive）を amu で check・test・compile される guest として実行 |
| `test/kyosai/core_test.cljc` | 不変条件を両方向から押す domain test |
| `test/kyosai/murakumo_test.cljc` | gate の緩み/きつみ両方向の boundary test |
| `test/kyosai/guest_parity_test.cljc` | guest と cljc oracle の parity。refusal code の対応表が片側だけ動くと赤 |
| `run_tests.cljs` | runner（nbb + cljs.test）。緑マーカーは全部緑のときだけ出る |
| `manifest.edn` | actor 宣言（機械可読 SSOT） |
| `identity.edn` | repo / DID / canonical document の対応 |
| `repository-contracts.edn` | repo 形状の宣言 |

## kotoba guest

判断層は kotoba guest としても実装されている（`kotoba/kyosai/guest/core.kotoba`）。
cljc oracle が record 層を持ち、guest が**判断**を実行する —— solidarity・
boundary inclusive・member gate の各不変条件が、host の解釈ではなく
コンパイルされた guest の振る舞いとして効く。

```bash
# check（JVM-free）
node orgs/kotoba-lang/amu/bin/amu check kotoba/kyosai/guest/core.kotoba --jvm-free
# guest 自身の test（jvm-kir / js / wasm の 3 target、15 test）
node orgs/kotoba-lang/amu/bin/amu test kotoba/kyosai/guest/core.kotoba
# wasm32-browser compile
node orgs/kotoba-lang/amu/bin/amu compile kotoba/kyosai/guest/core.kotoba \
  --jvm-free --target wasm32-browser --output target/kyosai-guest-core.wasm
```

refusal vocabulary は guest header の対応表で i64 code に写像される
（guest subset は keyword を返さない）。host が code を oracle の refusal map に
戻す。対応表が guest と parity test の**両方**に書かれており、片側だけ直すと赤になる。

## 設計の位置づけ

- **保険ではない。** risk-priced insurance（`kotoba-lang/insurance` が library として
  持つ方）と対になり、同じ slot を solidarity pricing で埋める。掛金は round ごとの
  schedule で決まり、member の属性で変わらない。
- **共済団体の corpus**（`90-docs/community-coverage/community-orgs-mutual-aid.edn`）は
  この actor の coverage 対象。bot 自体は本 repo が初の設計実装。
- **海上保険 actor**（`cloud-itonami/marine-insurance`）と同じ deny-by-default idiom を
  踏襲するが、あちらは descriptor snapshot であるのに対し、こちらは**domain 計算を
  実装して持つ**。実行主体（runtime / pool custody）はどちらにしても無い。

## やらないこと

- **pool の実保管・送金を実装しない**（資金の custody は安全床の対象。boundary の
  plan が data を出すだけ）
- **member の個人属性をスキーマに置かない**（solidarity pricing は属性を要求しない。
  属性を受け付ける field は存在しないことが構造的な保証）
- **裁定を甘くしない** — `ratify-payout` の throw を catch して soft-ratify する
  経路を作らない。council の attestation を持たない記録が pool に入る経路は
  `apply-ratified-payout` の refusal が唯一の入口
