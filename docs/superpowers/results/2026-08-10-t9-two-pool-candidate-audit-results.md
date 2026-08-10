# T9 双池候选逐列去向审计

## 固定输入与运行状态

- solverStatus: `INTEGER_CANDIDATE_GATE_FAILED`
- pricingTermination: `NO_NEGATIVE_COLUMN_FOUND`
- broad/rawDeep/deep/total: `450/1715/550/1000`
- rawDeep SHA-256: `55bf66b756ac8fc20398f2e547fb7230d2cc91a59692014f68770501ae33a6db`
- completion diagnostics: `Diagnostics[scannedPatterns=60, carCandidates=524, localConfigurations=4038, beamConfigurations=11384, pricedCandidates=11384, invalidConfigurations=0, duplicateSignatures=10, negativeReducedCostColumns=0, retainedCandidates=10754, returnedColumns=1719, completionCandidates=11384, completionReturnedColumns=1719, deadlineReached=false]`
- 总耗时: `65133 ms`
- 完整 CSV: [2026-08-10-t9-two-pool-candidate-audit.csv](./2026-08-10-t9-two-pool-candidate-audit.csv)

## 人工 65 列去向摘要

- broad 命中: 5
- rawDeep 命中: 0
- quota12 命中: 0
- quota16 命中: 0
- quota12 Packing 选中: 2
- quota16 Packing 选中: 2
- 首损阶段: `{BROAD_KEPT=5, RAW_DEEP_NOT_GENERATED=60}`
- rawDeep 未生成原因: `{COMPLETION_CAR_COUNT_PRUNED=13, COMPLETION_LOCAL_CONFIGURATION_PRUNED=16, KEY_PATTERN_NOT_SELECTED=31}`
- rawDeep 同花型位次分布: `<=8:0, 9..12:0, 13..16:0, >16:0`

## 配额 12 与 16 对照

| 指标 | quota12 | quota16 |
|---|---:|---:|
| deep 列数 | 550 | 550 |
| deep 花型数 | 59 | 59 |
| 人工列精确命中 | 0 | 0 |
| Packing 状态 | FEASIBLE | FEASIBLE |
| Packing 关闭订单 | 70 | 67 |
| Packing odd/one | 7/2 | 7/2 |
| Packing 耗时(ms) | 14521 | 15005 |

候选替换：quota16 相对 quota12 新增 40 列、挤出 40 列；人工 oracle 新增命中 0、丢失命中 0。

## 逐列审计前 20 行

|ID|花型|车数|odd|one|订单覆盖|broad|rawDeep|bundle频率|闭合数|覆盖比|同花型位次|q12|q16|Top550|Pack12|Pack16|首损|detail|
|---:|---|---:|---|---|---|---|---|---:|---:|---:|---:|---|---|---|---|---|---|---|
|1|550x2,1010x1,1100x2|6|false|false|550:8=12\|1010:63=6\|1100:77=6\|1100:84=6|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|KEY_PATTERN_NOT_SELECTED|
|2|850x1,1100x2,1260x1|12|false|false|850:22=12\|1100:89=24\|1260:112=12|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|KEY_PATTERN_NOT_SELECTED|
|3|1060x3,1140x1|6|false|false|1060:69=12\|1060:70=6\|1140:93=6|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|KEY_PATTERN_NOT_SELECTED|
|4|970x1,1000x1,1100x1,1250x1|2|false|false|970:41=2\|1000:52=2\|1100:87=2\|1250:107=2|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|KEY_PATTERN_NOT_SELECTED|
|5|550x1,840x1,980x3|9|true|false|550:8=9\|840:120=9\|980:47=27|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|KEY_PATTERN_NOT_SELECTED|
|6|550x1,840x1,980x3|2|false|false|550:8=2\|840:17=2\|980:47=4\|980:49=2|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|KEY_PATTERN_NOT_SELECTED|
|7|850x1,1000x1,1240x2|10|false|false|850:20=10\|1000:60=10\|1240:103=20|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|COMPLETION_LOCAL_CONFIGURATION_PRUNED; locus=width=850; rank=0/8; configuration was not produced by full local enumeration|
|8|850x1,1000x1,1240x2|30|false|false|850:21=30\|1000:55=30\|1240:105=60|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|COMPLETION_CAR_COUNT_PRUNED; locus=cars=30; rank=0/0; target car count was not processed by completion|
|9|850x1,1000x1,1240x2|20|false|false|850:21=20\|1000:61=20\|1240:106=40|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|COMPLETION_CAR_COUNT_PRUNED; locus=cars=20; rank=0/0; target car count was not processed by completion|
|10|550x1,860x1,980x3|10|false|false|550:8=10\|860:24=10\|980:45=20\|980:49=10|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|COMPLETION_LOCAL_CONFIGURATION_PRUNED; locus=width=860; rank=0/8; configuration was not produced by full local enumeration|
|11|550x1,860x1,980x3|8|false|false|550:8=8\|860:28=8\|980:47=8\|980:51=16|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|COMPLETION_LOCAL_CONFIGURATION_PRUNED; locus=width=980; rank=0/8; configuration was not produced by full local enumeration|
|12|550x1,890x1,970x3|6|false|false|550:8=6\|890:121=6\|970:39=18|true|false|||||false|false|false|true|true|BROAD_KEPT|BROAD_KEPT|
|13|550x1,890x1,970x3|2|false|false|550:8=2\|890:31=2\|970:42=6|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|KEY_PATTERN_NOT_SELECTED|
|14|980x2,1090x1,1300x1|8|false|false|980:46=8\|980:49=8\|1090:76=8\|1300:115=8|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|COMPLETION_CAR_COUNT_PRUNED; locus=cars=8; rank=0/0; target car count was not processed by completion|
|15|1000x2,1100x1,1260x1|6|false|false|1000:54=6\|1000:57=6\|1100:85=6\|1260:113=6|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|COMPLETION_LOCAL_CONFIGURATION_PRUNED; locus=width=1100; rank=0/8; configuration was not produced by full local enumeration|
|16|1000x2,1100x1,1260x1|4|false|false|1000:56=4\|1000:57=4\|1100:85=4\|1260:112=4|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|COMPLETION_LOCAL_CONFIGURATION_PRUNED; locus=width=1100; rank=0/8; configuration was not produced by full local enumeration|
|17|1000x2,1100x1,1260x1|10|false|false|1000:62=20\|1100:85=10\|1260:111=10|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|COMPLETION_LOCAL_CONFIGURATION_PRUNED; locus=width=1000; rank=0/8; configuration was not produced by full local enumeration|
|18|1010x2,1100x1,1240x1|2|false|false|1010:63=4\|1100:84=2\|1240:103=2|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|KEY_PATTERN_NOT_SELECTED|
|19|1090x4|15|true|false|1090:74=60|false|false|||||false|false|false|false|false|RAW_DEEP_NOT_GENERATED|KEY_PATTERN_NOT_SELECTED|
|20|550x1,900x1,970x3|6|false|false|550:8=6\|900:32=6\|970:39=18|true|false|||||false|false|false|true|true|BROAD_KEPT|BROAD_KEPT|

## 审计判定

结论：**结果 C——损失发生在生成阶段，当前不是配额问题，也还没有证据支持改 Top550 排序。**

- 人工 65 列中，只有 5 列已经位于 broad450；其余 60 列均未进入 rawDeep。
- 60 列的首损分布为：关键花型未选中 31、候选车数未生成 13、局部订单配置未生成 16、Beam 裁剪 0。
- rawDeep 中人工列精确命中为 0，因此同花型位次分布全部为空；不存在“多数目标列排在第 13–16 名”的配额证据。
- quota12 与 quota16 都命中 0 个新增人工列。quota16 替换了 40 个深池成员，但同参 Packing 的关闭订单数由 70 降为 67。
- 两次完全同参运行均得到 rawDeep=1715、相同 SHA-256 指纹、相同 5/0/0/0 命中和 31/13/16 损失分布，结论可复现。
- 两次 Packing 都是 FEASIBLE 而非 OPTIMAL，因此 70 对 67 是受限时间内的实测表现，不是对所有 quota16 方案的数学否定。

人工 65 是诊断 oracle，不代表唯一好解；这里证明的是当前生成器无法物化这一类已知有效结构，而不是必须复制人工签名。

## 对原假设的回答

1. **配额 12 是否太小：否。** 目标列在配额生效前就没有进入 rawDeep。
2. **Top550 单列排序是否是本轮主损失点：没有证据。** 没有目标列进入 rawDeep，自然无法在 Top550 排序阶段被裁。
3. **Packing 是否拒绝了已生成的目标结构：只涉及 broad 中的 5 列。** 其中两列被 quota12/16 Packing 采用；其余 60 列没有到达 Packing。
4. **是否执行残差优先/奇偶平衡排序对照：不执行。** 设计规定只有 rawDeep 已包含大量目标列但选择阶段丢失时才进入该分支，本轮前置条件不成立。

## 下一步最小实验

停止继续提高 12/16/20 配额，改为分别审计三个确定的生成入口：

1. 对 31 个缺失花型比较其结构特征与当前 60 个关键花型，定位关键花型选择器为何排除已知互补花型。
2. 对 13 个车数损失检查候选车数全集、截断前位次和 max-car 上限，区分“从未构造”与“构造后被车数配额裁剪”。
3. 对 16 个局部配置损失记录完整局部枚举位次，并仅做离线 8→16 对照，判断局部配置限额能恢复多少目标列。

在这三项完成前，不修改生产配额、Top550 排序、Packing 目标或人工列注入策略。

## 验证记录

- `OrderGroupColumnPricingPrototypeTest`：实际执行 20，成功 20，失败 0，跳过 0。
- T9 双池逐列审计第 1 次：实际执行 1，成功 1，失败 0，跳过 0。
- T9 双池逐列审计第 2 次：实际执行 1，成功 1，失败 0，跳过 0。
- 快照契约覆盖：审计开关关闭/开启时生产候选签名一致，离线 quota12 与当前 deep550 签名一致。
- 审计开关默认关闭；本轮未改变生产配额、候选排序、Packing 目标、时间预算或人工列注入行为。
