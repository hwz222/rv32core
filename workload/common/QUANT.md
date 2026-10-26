# FP32 到 INT8：量化規格與實作

> 本文件是量化方法的工程規格。各模型的量化結果與準確率見
> `workload/mobilenet/quantization_report.md` 與
> `workload/resnet18/quantization_report.md`。

本文件依照下列順序說明：**模型有哪些運算 → 浮點公式 → 量化的數學原理 →
FP32 如何轉成 INT8 → 哪些運算被摺疊、怎麼摺 → 每個運算量化後的整數公式 →
實作位置 → 實測結果 → 最終模型只剩下哪些操作**。

讀完之後，應該能回答：「一張圖片進到 CPU 之後，到底做了哪些整數運算。」

---

## 目錄

1. [問題與整體流程](#1-問題與整體流程)
2. [模型有哪些運算（FP32）](#2-模型有哪些運算fp32)
3. [量化的數學原理](#3-量化的數學原理)
4. [BatchNorm 摺疊](#4-batchnorm-摺疊)
5. [FP32 轉 INT8 的完整步驟](#5-fp32-轉-int8-的完整步驟)
6. [requantize：INT32 轉回 INT8](#6-requantizeint32-轉回-int8)
7. [每個運算的整數公式](#7-每個運算的整數公式)
8. [實作位置與工具](#8-實作位置與工具)
9. [實測結果](#9-實測結果)
10. [量化後的模型剩下哪些操作](#10-量化後的模型剩下哪些操作)
11. [必要檢查與數值邊界](#11-必要檢查與數值邊界)

---

## 1. 問題與整體流程

RV32IMA 只有整數指令（`add`、`mul`、`mulh`、位移、比較），**沒有浮點單元**。
但訓練出來的模型全部是 FP32。因此必須把模型轉成只用整數就能執行的形式。

```
                    離線（Python，只做一次）              執行期（C，在 CPU 上）
┌────────────┐   ┌──────────────────────────────┐   ┌─────────────────────────┐
│ FP32 模型   │   │ ① 摺疊 BatchNorm              │   │ INT8 乘加 → INT32 累加   │
│ .npz 權重   │──▶│ ② 校正：量測每層數值範圍        │──▶│ requantize → INT8        │
│ + 網路結構  │   │ ③ 權重、bias、乘數轉成整數      │   │ 全程整數，無浮點          │
└────────────┘   └──────────────────────────────┘   └─────────────────────────┘
                  qref.py                              nn_int8.c
                          │                                      │
                          ▼                                      ▼
                  model_int8/（model.json、weights.bin）    logits（INT32）
```

三個程式必須依照本規格實作，且結果**逐位元相同**：

| 程式 | 角色 |
|---|---|
| `workload/common/tools/qref.py`（Python，離線） | 參考實作；量化報告的 INT8 準確率由它量測 |
| C 的 host 版（`*_host`） | host golden：RISC-V 版的比對基準 |
| C 的 RISC-V 版（`*.elf`） | 在 ISA simulator 與 RTL 上執行 |

---

## 2. 模型有哪些運算（FP32）

兩個模型都是 CIFAR-10 分類器：輸入 `3×32×32`，輸出 10 個 logit。

### 2.1 運算種類統計（取自 `model_int8/model.json`）

| 模型 | 卷積 | 殘差加法 | 全域平均池化 | 全連接 | 合計層數 |
|---|---|---|---|---|---|
| ResNet18 寬度 16 | 20 | 8 | 1 | 1 | 30 |
| MobileNetV1 α=0.5 | 27 | 0 | 1 | 1 | 29 |

卷積的細分：

| 模型 | 標準 3×3 | 1×1 | depthwise 3×3 |
|---|---|---|---|
| ResNet18 | 17（stride 1×14、stride 2×3） | 3（shortcut 投影，stride 2） | 0 |
| MobileNetV1 | 1（stem） | 13（pointwise） | 13（stride 1×9、stride 2×4） |

所以整個模型只用到 **4 種運算**：卷積、加法、平均池化、全連接。這也是
`nn_int8.c` 只有 4 個 kernel 的原因。

### 2.2 各運算的 FP32 公式

**卷積**（輸出通道 `c`、位置 `(y, x)`；`S` 為 stride、`P` 為 padding、`K` 為 kernel 大小）

```
y[c][y][x] = Σ(ci, ky, kx)  x[ci][y·S + ky − P][x·S + kx − P] × w[c][ci][ky][kx]
```

- 標準卷積：`ci` 跑遍所有輸入通道
- depthwise 卷積（`groups = C`）：`ci` 只取 `c`，每個通道各自獨立
- pointwise（1×1）卷積：`K = 1`，只在通道方向混合
- 卷積**不帶 bias**（訓練時設定 `bias=False`），bias 由後面的 BN 提供

**BatchNorm**（推論時；`gamma`、`beta`、`mean`、`var` 為訓練後的固定值，`eps = 1e-5`）

```
bn(y)[c] = gamma[c] × (y[c] − mean[c]) / sqrt(var[c] + eps) + beta[c]
```

**ReLU**

```
relu(v) = max(v, 0)
```

**殘差加法**（ResNet18 的 BasicBlock）

```
out = relu(a + b)      a：區塊第二個卷積的輸出
                       b：shortcut（區塊輸入，或 1×1 投影卷積的輸出）
```

**全域平均池化**（輸入 `C×H×W`，輸出 `C`）

```
out[c] = (1 / (H·W)) × Σ(y, x) x[c][y][x]
```

**全連接層**

```
logit[j] = bias[j] + Σi x[i] × w[j][i]
預測類別 = argmax(logit)
```

不做 softmax：softmax 是單調遞增函式，不影響 argmax，而且需要指數運算。

### 2.3 完整的層序列

**ResNet18 寬度 16**

```
輸入 3×32×32
  stem:        conv3×3(3→16, s1) → BN → ReLU                    輸出 16×32×32
  layer1（2 個 BasicBlock，通道 16，s1）
  layer2（2 個 BasicBlock，通道 32，第一個 s2）                    輸出 32×16×16
  layer3（2 個 BasicBlock，通道 64，第一個 s2）                    輸出 64×8×8
  layer4（2 個 BasicBlock，通道 128，第一個 s2）                   輸出 128×4×4
  pool:        全域平均池化                                       輸出 128
  fc:          全連接 128→10                                     輸出 10

BasicBlock(cin, cout, s):
  a = ReLU(BN(conv3×3(x, cin→cout, s)))
  a = BN(conv3×3(a, cout→cout, s1))
  b = x                              （cin == cout 且 s == 1）
      或 BN(conv1×1(x, cin→cout, s)) （形狀改變時的投影）
  out = ReLU(a + b)
```

**MobileNetV1 α=0.5**

```
輸入 3×32×32
  stem:        conv3×3(3→16, s1) → BN → ReLU
  13 個 block：dw3×3 → BN → ReLU → pw1×1 → BN → ReLU
               通道 16→32→64→64→128→128→256×6→512→512
               stride 1,2,1,2,1,2,1,1,1,1,1,2,1                  空間 32→16→8→4→2
  pool:        全域平均池化                                       輸出 512
  fc:          全連接 512→10                                     輸出 10
```

---

## 3. 量化的數學原理

### 3.1 基本對應關係

量化就是把一段實數範圍用整數等距表示：

```
實數 r ≈ s × q          s：scale（正的浮點數，離線決定）
                        q：整數（INT8，範圍 −127 ～ 127）
```

本專案採**對稱量化**，zero point 固定為 0。因此實數 0.0 對應整數 0，
zero padding 直接補 0，不需要額外處理。

給定一段數值範圍，scale 的取法是：

```
s = max(|r|) / 127
q = clip(round(r / s), −127, 127)
```

權重範圍取 −127 而不是 −128，是為了讓正負範圍對稱。

### 3.2 為什麼乘加可以在整數域完成

設輸入 `x = s_x · q_x`、權重 `w = s_w · q_w`，則卷積的實數結果為：

```
y = Σ x·w = Σ (s_x·q_x)(s_w·q_w) = (s_x·s_w) × Σ q_x·q_w
                                    └─ 常數 ─┘  └─ 純整數累加 ─┘
```

**關鍵**：兩個 scale 可以提到總和之外。所以 CPU 只需要做 `Σ q_x·q_w` 這個
整數累加（結果存在 INT32），scale 的部分留到最後再處理。

### 3.3 scale 的粒度

| 對象 | 粒度 | 理由 |
|---|---|---|
| activation | 每個張量一個 | 同一層各通道的數值範圍相近；每通道一個會讓下一層的乘數變成陣列，C 端負擔增加 |
| 卷積權重 | **每個輸出通道一個** | depthwise 卷積各通道的權重範圍差異可達數十倍，用單一 scale 會讓小通道的精度嚴重損失 |
| 全連接層權重 | 整個矩陣共用一個 | 10 個 logit 必須有相同的 scale，才能直接比較整數大小取 argmax |

### 3.4 數值格式總表

| 張量 | 型別 | 範圍 | scale |
|---|---|---|---|
| ReLU 之後的 activation | `int8_t` | 0 ～ 127 | 每張量一個 |
| 沒有 ReLU 的 activation、輸入影像 | `int8_t` | −128 ～ 127（影像 −127 ～ 127） | 每張量一個 |
| 卷積權重 | `int8_t` | −127 ～ 127 | 每輸出通道一個 |
| 全連接層權重 | `int8_t` | −127 ～ 127 | 整個矩陣一個 |
| bias | `int32_t` | int32 | `s_in × s_w[c]` |
| 累加器 | `int32_t` | int32 | `s_in × s_w[c]` |
| requantize 參數 | `int32_t M0`、`int32_t shift` | 見第 6 節 | 每輸出通道一組 |
| logit | `int32_t` | int32 | 全連接層的 `s_in × s_w` |

記憶體排列（row-major，與 PyTorch 相同）：activation `[C][H][W]`、
卷積權重 `[C_out][C_in/groups][K][K]`、全連接權重 `[out][in]`。

---

## 4. BatchNorm 摺疊

### 4.1 為什麼要摺

BatchNorm 在推論時是固定的線性轉換：對每個通道做「乘一個數、加一個數」。
如果保留成獨立的一層，CPU 每個輸出元素都要多做一次乘法與加法。

但卷積本身也是線性運算，所以 BN 可以**併進前一層卷積的權重與 bias**，
執行期完全不需要 BN 這個運算。

### 4.2 推導

卷積（無 bias）接 BN：

```
bn(conv(x))[c] = gamma[c] × (Σ x·w[c] − mean[c]) / sqrt(var[c] + eps) + beta[c]
```

令

```
a[c] = gamma[c] / sqrt(var[c] + eps)
```

展開後：

```
= a[c] × Σ x·w[c] − a[c]×mean[c] + beta[c]
= Σ x · (a[c]×w[c])  +  (beta[c] − mean[c]×a[c])
  └── 新權重 w_f ──┘     └──── 新 bias b_f ────┘
```

### 4.3 摺疊公式

```
a[c]   = gamma[c] / sqrt(var[c] + eps)          eps = 1e-5
w_f[c] = w[c] × a[c]                            （該通道的所有權重同乘 a[c]）
b_f[c] = beta[c] − mean[c] × a[c]
```

摺疊後，每個卷積變成 `y = conv(x, w_f) + b_f`，後面若有 ReLU 就接 ReLU。
**BN 這個運算從模型中完全消失。**

摺疊在 float64 之下進行（`qref.py` 的 `fold_bn()`），所以沒有精度損失。
實測：ResNet18 摺疊後的 FP32 準確率為 92.71%，與訓練時完全相同。

---

## 5. FP32 轉 INT8 的完整步驟

輸入是訓練產出的 `model_fp32.npz`（權重）與 `model_graph.json`（網路結構）。
以下五個步驟全部在 `qref.py` 中離線完成。

### 步驟 1：摺疊 BatchNorm

依第 4 節，得到每層的 `w_f`（float64）與 `b_f`（float64）。

### 步驟 2：校正 activation 的 scale

scale 必須知道「這一層的輸出會落在什麼範圍」，而這要用真實資料量測：

- 校正資料：**CIFAR-10 訓練集的前 1000 張**（編號 0～999），使用測試時的
  前處理（不做資料增強）
- 以摺疊後的 FP32 模型跑過這 1000 張，對每個張量（每個 conv、add、gap 的
  輸出）記錄整批的最大絕對值 `max_abs`
- `s = max_abs / 127`

這是 min-max 校正。若改用其他方法（例如 percentile），必須在量化報告中
說明並附理由。

### 步驟 3：量化權重

```
s_w[c] = max( max(|w_f[c]|) / 127 ,  |b_f[c]| / (s_in × 2^30) )    （兩項皆 0 時取 1.0）
w_q    = clip(rint(w_f / s_w[c]), −127, 127)
```

第一項涵蓋權重的數值範圍。**第二項是針對失效通道的保護**：weight decay 有
可能把某個通道的所有權重壓到約 10⁻⁷，但 BN 仍給它一個不小的 bias。只有
第一項的話，極小的 scale 會讓 `b_q = b_f / (s_in × s_w)` 超出 int32。

> 實例：MobileNet 的 stem 通道 1，`max|w_f| = 4.66e−07`、`b_f = −1.34`。
> 只用第一項時 `s_w = 3.67e−09`，`b_q ≈ −2.18e10`，遠超過 int32 上限 2.15e9。
> 兩項並用後 `s_w = 7.45e−08`，`b_q = −2^30 = −1073741824`，放得進 int32。
>
> 此時該通道的權重只剩極少有效位元：量化後 `q_w` 落在 `[−3, 6]`，絕對值
> 總和為 68，故累加項的上界為 `127 × 68 = 8636`。因此
> `acc = Σ q_x·q_w + b_q ≤ 8636 − 1073741824 < 0` 恆為負，經 `clamp8(..., lo=0)`
> 後輸出必為 0。這與浮點模型一致：浮點模型中 `Σ x·w ≈ 0`（權重量級 10⁻⁷），
> 加上 `b_f = −1.34` 後經 ReLU 也是 0。
>
> 兩個模型中共有 5 個這樣的通道。

全連接層整個矩陣共用一個 `s_w`，同樣取兩項的最大值（bias 取最大絕對值）。

### 步驟 4：量化 bias

```
b_q[c] = rint(b_f[c] / (s_in × s_w[c]))
```

`s_in` 是該層**輸入張量**的 scale。分母 `s_in × s_w[c]` 正是累加器的 scale
（見 3.2 節），所以 bias 可以直接加進累加器。

`rint` 採 round-half-to-even（NumPy 的 `np.rint`）。這些取整只在離線做一次，
所以只有 Python 端需要知道這個規則。

### 步驟 5：計算 requantize 的乘數

累加器的 scale 是 `s_in × s_w[c]`，但輸出要用 `s_out`，所以要乘上

```
M = s_in × s_w[c] / s_out
```

`M` 是浮點數，CPU 算不了，必須轉成整數形式。作法見第 6 節。

### 輸入影像的量化

輸入的 scale 不用校正，而是由公式推導，保證涵蓋所有可能的像素值：

```
x      = (pixel / 255 − mean[ch]) / std[ch]
s_in   = 取各通道 max(mean[ch]/std[ch], (1 − mean[ch])/std[ch]) 的最大值 / 127
x_q    = clip(rint(x / s_in), −127, 127)
```

以 `mean = (0.4914, 0.4822, 0.4465)`、`std = (0.2470, 0.2435, 0.2616)` 代入，
得 `s_in = 2.1265 / 127 = 0.01674401`（兩個模型相同）。標準化與量化都由
build 階段的產生器離線完成，C 程式拿到的是已量化好的常數陣列。

---

## 6. requantize：INT32 轉回 INT8

這是整個量化流程中最關鍵的一步。

### 6.1 目標

累加器是 INT32，scale 為 `s_in × s_w[c]`；輸出要是 INT8，scale 為 `s_out`。
換算關係：

```
q_out = round( q_acc × M )        M = s_in × s_w[c] / s_out
```

`M` 通常是一個小於 1 的浮點數（例如 5.67e−04），但 CPU 沒有浮點運算。

### 6.2 定點表示法

把 `M` 拆成「整數乘數 × 2 的負次方」：

```
M = M0 × 2^−(31 + shift)        其中 2^30 ≤ M0 < 2^31，1 ≤ 31 + shift ≤ 62
```

`M0` 限制在 `[2^30, 2^31)`，保證它至少有 31 位有效位元，精度足夠。

離線推導（`frexp` 把 `M` 拆成 `m × 2^e`，`0.5 ≤ m < 1`）：

```
m, e  = frexp(M)
M0    = round(m × 2^31)
if M0 == 2^31:  M0 = 2^30; e = e + 1     （進位造成溢位時修正）
shift = −e
```

實例（ResNet18 的 stem，通道 0）：

```
s_in  = 0.01674401          輸入影像的 scale
s_w[0] = 3.952945e−03       該通道的權重 scale
s_out = 1.166356e−01        該層輸出的 scale（校正得到）
M     = s_in × s_w[0] / s_out = 5.674781e−04
      → M0 = 1247897547, shift = 10
      驗算：1247897547 × 2^−41 = 5.674781e−04 ✓
```

### 6.3 執行期的整數運算

C 與 Python 完全相同：

```c
/* 回傳 round(x × M)，只捨入一次，逢半進位（往 +infinity）。 */
static inline int64_t requant(int32_t x, int32_t m0, int32_t shift) {
    int64_t p = (int64_t)x * (int64_t)m0;        /* 精確，|p| < 2^62 */
    int k = 31 + shift;                          /* 1 ～ 62 */
    return (p + ((int64_t)1 << (k - 1))) >> k;   /* 算術右移 */
}

static inline int8_t clamp8(int64_t v, int32_t lo) {  /* lo 為 0 或 −128 */
    return (int8_t)(v < lo ? lo : (v > 127 ? 127 : v));
}
```

規則：

- **只捨入一次**，對象是精確的 64-bit 乘積。加上 `2^(k−1)` 再右移，等同於
  逢半進位的四捨五入
- 先在 64-bit 之下 clamp，再縮成 `int8_t`。**不可以先縮再 clamp**（先縮會
  造成數值繞回）
- 該層有 ReLU 時 `lo = 0`——**ReLU 就是由這個 clamp 完成的**，不需要獨立的
  ReLU 運算；沒有 ReLU 時 `lo = −128`
- Python 端要用整數型別（`int` 或 `np.int64`）；負數的 `>>` 在 Python、
  以及 C 在 RV32 與 x86 上都是算術右移，行為一致

### 6.4 RV32IMA 上的指令展開

`-nostdlib` 連結時，若 compiler 把 64-bit 運算轉成 libgcc 函式呼叫
（`__muldi3`、`__ashrdi3`）就會連結失敗。實測 gcc 12 的展開結果：

```
mul  a5,a0,a1      ← 64-bit 乘積的低 32 位
mulh a0,a0,a1      ← 高 32 位
lui / add / sltu / srli / slli / or    ← 64-bit 加法與位移，全部展開
```

`nm -u` 顯示沒有任何未定義符號，確認不需要 libgcc。

實際的 `31 + shift` 範圍：ResNet18 為 30～46，MobileNet 為 31～61，都在
`requant()` 定義的 1～62 之內。

---

## 7. 每個運算的整數公式

以下是 CPU 執行期實際做的事，全部是整數運算。

### 7.1 卷積（標準／depthwise／pointwise／shortcut 投影）

```
acc = b_q[c] + Σ(ci, ky, kx)  x_q[ci][y·S + ky − P][x·S + kx − P] × w_q[c][ci][ky][kx]
out = clamp8( requant(acc, M0[c], shift[c]), lo )

acc：int32        M = s_in × s_w[c] / s_out
lo = 0（有 ReLU）或 −128（無 ReLU）
```

- 超出輸入範圍的位置讀 0（zero padding），因為 zero point 為 0
- depthwise（`groups = C`）：通道 `c` 只讀輸入的第 `c` 個通道
- 1×1 卷積：`K = 1`、`P = 0`，是上式的特例
- `S`、`P`、`K`、`groups` 都來自 `model_graph.json`

### 7.2 殘差加法（ResNet18）

兩個輸入的 scale 不同（`s_a` 與 `s_b`），必須各自換算到輸出的 scale：

```
out = clamp8( requant(a, Ma0, a_shift) + requant(b, Mb0, b_shift), 0 )

Ma = s_a / s_out,  Mb = s_b / s_out
lo = 0（ReLU 在此完成）
```

每個輸入各捨入一次，相加在 64-bit 之下完成，最後才 clamp。

實例（ResNet18 `layer1.0.add`）：`M0 = [1120697645, 1531838469]`、
`shift = [−1, 0]`。

### 7.3 全域平均池化

```
sum[c] = Σ(y, x) x_q[c][y][x]                       （int32）
out[c] = clamp8( requant(sum[c], M0, shift), 0 )     M = s_in / (H·W × s_out)
```

**除以 `H×W` 已經併進 `M`，執行期不做除法。**

實例（ResNet18，輸入 128×4×4）：`H×W = 16`，`M = 0.1278`，其中已包含
`1/16 = 0.0625` 這個因子。

### 7.4 全連接層

```
logit[j] = b_q[j] + Σi  x_q[i] × w_q[j][i]      （int32，不做 requantize）
預測類別 = argmax(logit)
```

logit 保持 INT32，不轉回 INT8，理由有二：

1. 不損失精度（轉 INT8 會把 10 個數字壓到 8 位元）
2. 整個權重矩陣共用一個 scale，所以 10 個 logit 的 scale 相同
   （ResNet18 為 5.47e−04、MobileNet 為 9.51e−05），比較整數大小就等同於
   比較實數大小

### 7.5 輸出格式（host 與 RISC-V 版逐行比對的對象）

與 GEMM 相同：**每行一個 32-bit word，8 位小寫 16 進位**（`%08x`），row-major。

| 程式 | 輸出內容 |
|---|---|
| operator bench（`bench_dw`、`bench_pw`、`bench_conv3x3`、`bench_resadd`） | 輸出張量的每個元素，`int8_t` 擴展成 `int32_t`（−3 印成 `fffffffd`） |
| 完整模型（`mobilenet_infer`、`resnet18_infer`） | 10 個 `int32_t` logit |

host 版與 RISC-V 版必須輸出完全相同的字串。

---

## 8. 實作位置與工具

### 8.1 檔案對照

| 檔案 | 階段 | 內容 |
|---|---|---|
| `workload/common/QUANT.md` | —— | 本文件 |
| `workload/common/tools/qref.py` | 離線 | 第 4、5 節的摺疊與量化；第 6、7 節的整數參考實作；量測 INT8 準確率 |
| `workload/<model>/model_int8/` | 離線產出 | `model.json`（層參數、scale、bias、乘數）、`weights.bin`（INT8 權重）、`accuracy.json` |
| `workload/common/tools/gen_model_c.py` | build 時 | 把 `model_int8/` 轉成 C 陣列與層表，並規劃 activation 的記憶體配置。**只用 Python 標準函式庫**（build 不需要 numpy／torch） |
| `workload/common/qops.h` | 執行期 | 第 6 節的 `requant()` 與 `clamp8()` |
| `workload/common/nn_int8.{h,c}` | 執行期 | 第 7 節的 conv、add、gap、fc 四個 kernel 與層執行器 |
| `verification/int8_models/` | 驗證 | 確認 `qref.py`、host C、RV32IMA C 三者的 logit 完全相同 |

### 8.2 執行指令

```bash
# 量化 + 量測 INT8 準確率（離線，需要 numpy 與 torch）
python3 workload/common/tools/qref.py \
    --run ../ml_workspace/runs/resnet18_w16 \
    --out workload/resnet18/model_int8 \
    --data ../ml_workspace/datasets \
    --eval-out ../ml_workspace/int8_eval/resnet18_w16

# 三方 bit-exact 驗證
python3 verification/int8_models/check_bitexact.py --model all
```

### 8.3 `model.json` 的欄位

每一層都記錄了 C 端需要的所有整數參數：

| 欄位 | 內容 |
|---|---|
| `op` | `conv`／`add`／`gap`／`fc` |
| `cin`、`cout`、`kernel`、`stride`、`padding`、`groups` | 卷積的形狀參數 |
| `in_shape`、`out_shape` | 輸入與輸出張量形狀 |
| `relu` | 是否有 ReLU（決定 `clamp8` 的 `lo`） |
| `weight_offset`、`weight_count` | 權重在 `weights.bin` 中的位置 |
| `bias` | INT32 bias 陣列（每輸出通道一個） |
| `m0`、`shift` | requantize 參數（每輸出通道一組；`add` 為兩組） |
| `weight_scale` | 浮點 scale，僅供記錄與除錯，C 端不使用 |

---

## 9. 實測結果

### 9.1 準確率（CIFAR-10 測試集，10,000 張）

| 模型 | FP32（訓練時） | FP32（摺疊 BN 後） | **INT8** | 掉幅 | FP32 與 INT8 預測一致率 |
|---|---|---|---|---|---|
| ResNet18 寬度 16 | 92.71% | 92.71% | **92.44%** | 0.27% | 98.51% |
| MobileNetV1 α=0.5 | 90.79% | 90.79% | **90.88%** | −0.09% | 98.11% |

- 「摺疊 BN 後」與訓練時完全相同，證明第 4 節的摺疊與浮點路徑正確。
  這也是 `qref.py` 內建的防呆檢查項目
- MobileNet 的 INT8 略高於 FP32 屬於量化雜訊造成的個別樣本差異，不代表
  量化改善了模型

### 9.2 三方 bit-exact 驗證

記錄於 `verification/int8_models/bitexact.log`：

| 模型 | host C（10,000 張） | RV32IMA on isa_sim（前 100 張） | 每張指令數 |
|---|---|---|---|
| ResNet18 | **0 張不同** | **0 張不同** | 7.97 億 |
| MobileNetV1 | **0 張不同** | **0 張不同** | 6.44 億 |

比對的是**每一個 logit 的整數值**，不只是預測類別。結論：量化報告中的
INT8 準確率，就是 RV32IMA core 實際執行會得到的結果。

### 9.3 模型大小

| 模型 | FP32 權重 | INT8 權重 | 壓縮比 | activation 記憶體 |
|---|---|---|---|---|
| ResNet18 寬度 16 | 2.73 MB | 683 KB | 4× | 48 KB |
| MobileNetV1 α=0.5 | 3.18 MB | 794 KB | 4× | 64 KB |

activation 記憶體由 `gen_model_c.py` 規劃：依每個張量最後被讀取的層次，
以 first-fit 重複使用空間。

---

## 10. 量化後的模型剩下哪些操作

這是整份文件最實際的結論：**CPU 上到底在做什麼**。

### 10.1 消失的運算

| 原本的運算 | 去向 |
|---|---|
| BatchNorm | 摺進卷積的權重與 bias（第 4 節），執行期不存在 |
| ReLU | 併入 `clamp8` 的下界 `lo = 0`（第 6.3 節），不是獨立運算 |
| 平均池化的除法 | 併入 requantize 的乘數 `M`（第 7.3 節） |
| 輸入標準化（減 mean、除 std） | 離線完成，C 端拿到的已是量化後的整數（第 5 節） |
| softmax | 不需要，直接對 logit 取 argmax（第 2.2 節） |
| 所有浮點運算 | 全部在離線階段完成 |

### 10.2 執行期剩下的操作

只有 5 種，全部是整數運算：

| # | 操作 | 使用的 RV32IMA 指令 | 出現位置 |
|---|---|---|---|
| 1 | INT8 乘加累進 INT32 | `lb`、`mul`、`add` | 卷積、全連接 |
| 2 | 加上 INT32 bias | `add` | 卷積、全連接 |
| 3 | 64-bit 乘法（requantize） | `mul`、`mulh` | 卷積、add、gap |
| 4 | 64-bit 加法與算術右移（requantize） | `add`、`sltu`、`slli`、`srli`、`or` | 同上 |
| 5 | clamp 到 `[lo, 127]` 並存成 INT8 | 比較、`sb` | 同上 |

再加上迴圈控制（比較、分支）與位址計算（`add`、`slli`）。

**不需要**：浮點指令、除法（`div`／`rem`）、查表、指數或對數函式。

### 10.3 一張圖片的完整流程（以 ResNet18 為例）

```
輸入：3×32×32 的 INT8 常數陣列（已離線標準化並量化）
  │
  ├─ stem            conv3×3 → INT32 累加 → requantize → INT8（clamp 下界 0 = ReLU）
  ├─ layer1 區塊 ×2   conv3×3 ×2 → add（兩路各 requantize 後相加）
  ├─ layer2 區塊 ×2   同上，另含 1×1 投影卷積
  ├─ layer3 區塊 ×2   同上
  ├─ layer4 區塊 ×2   同上
  ├─ pool            INT32 加總 → requantize（除法已併入乘數）→ INT8
  └─ fc              INT32 乘加 → 10 個 INT32 logit（不 requantize）
  │
輸出：10 個 INT32 logit → argmax 得到預測類別
```

總計 30 層、7.97 億條指令、約 23 條指令／MAC。

### 10.4 目前的效能與後續最佳化

| 模型 | MAC 數 | 每張指令數 | 每 MAC 指令數 |
|---|---|---|---|
| ResNet18 | 3500 萬 | 7.97 億 | 約 23 |
| MobileNetV1 | 1220 萬 | 6.44 億 | 約 53 |

目前的 kernel 是最直接的寫法：最內層迴圈每次都做邊界檢查與位址計算。
MobileNet 的比值特別高，是因為 1×1 卷積也走 3×3 的迴圈結構（`ky`、`kx`
各只跑一次，但每次都有迴圈開銷）。

之後的軟體最佳化要處理這一點：把邊界檢查移出內層迴圈、
為 1×1 與 3×3 各寫專用版本。**整數加法與順序無關，只要 requantize 的步驟
不變，最佳化後的結果仍會逐位元相同。**

---

## 11. 必要檢查與數值邊界

離線工具（`qref.py`）必須檢查以下項目，任一項不通過就中止：

| 檢查項目 | 原因 | 實測結果 |
|---|---|---|
| `2^30 ≤ M0 < 2^31` 且 `1 ≤ 31 + shift ≤ 62` | `requant()` 只在此範圍有定義 | ResNet18：30～46；MobileNet：31～61 |
| `fan_in × 128 × 127 + max\|b_q\| < 2^31` | INT32 累加器不可溢位 | 最大 fan-in：ResNet18 為 1152（128 通道 ×3×3）→ 1.9e7；MobileNet 為 512 → 8.3e6，皆遠低於 2.1e9 |
| 每個 `b_q` 放得進 int32 | bias 的型別限制 | 由第 5 節步驟 3 的 scale 下限保證 `\|b_q\| ≤ 2^30` |
| 摺疊 BN 後的 FP32 準確率 == 訓練時的準確率 | 確認摺疊與浮點路徑正確 | 兩個模型皆完全相同 |
| 卷積累加值必須是整數 | 參考實作以 float64 計算整數和，必須精確 | 每次呼叫都檢查捨入誤差 < 1e−6 |
| Python、host C、RV32IMA C 三者結果相同 | 三份實作必須逐位元一致 | 見 9.2 節 |
