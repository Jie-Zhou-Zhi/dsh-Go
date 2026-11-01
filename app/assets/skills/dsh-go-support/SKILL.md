---
# dsh-go:managed — the dsh-go app keeps this skill up to date; delete this line to keep your own edits.
name: dsh-go-support
description: dsh-go 手机 App 的排障入口：先读 App 写好的本机诊断快照，再按症状定位，并给出一份可直接粘贴的 GitHub issue 草稿。用户说 dsh-go 出问题、连不上、起不来、闪退、卡、某功能不work、要给上游提 bug 时先用它。dsh-go support — read the on-device diagnostics snapshot first, walk the symptom, then draft a paste-ready GitHub issue.
whenToUse: The person reports something wrong with the dsh-go app itself (engine won't start, can't connect, crash, freeze, a feature misbehaves) or asks to file an issue upstream. Not for "how do I use X" (dsh-go-guide).
---

# dsh-go 排障

## 1. 先读快照，再问人

App 会把一份脱敏的本机诊断写进引擎目录：

- 有 `$DSH_HOME` 时：`$DSH_HOME/dsh-app/diagnostics.md`
- 没有时：`~/.dsh/dsh-app/diagnostics.md`

先读它。里面有版本、机型、Android 版本、运行时、启动方式、连接、性能档位、最近的 App 错误和引擎日志尾部。**快照里已经有的事实不要再去问用户**。快照可能有一段时间没更新：看第一行的生成时间，明显过旧（比如跨了重启）就说明用户手动刷新一次（设置 → 诊断），或者先用工具看别处。

## 2. 按症状走，一次只走一条

1. **引擎起不来 / 一直转圈**：看 `运行时` 段的 phase、safeState、连续失败次数、`最近错误`，再看 `引擎日志尾部`。安全模式（safeState≠off）说明已经连续失败过；先说明这一点，再建议。
2. **能起但连不上 / 老断线**：看 `连接` 段和引擎地址，区分「本机引擎」和「远程主机」。远程主机的问题在对面，不在手机上，别在手机上乱改。
3. **闪退 / 卡**：看 `最近的 App 错误` 的时间，和用户说的时刻对一下；崩溃大概率有对应条目。
4. **某个功能不对**（手机操控、插件、桌宠、分享）：先确认版本号，再判断这是已知问题还是新问题；不确定就直说快照里看不出来。

## 3. 给结论，不要给整份日志

- 先一句话说结论或最可能的原因，再给 1–3 个具体可做的下一步。
- 引用快照时只引和结论有关的一两行，**不要整段贴给用户**（它很长，而且用户不需要）。
- 不确定就说不确定，别猜一个版本号或机型。

## 4. 需要上报时，给一份能直接粘的草稿

上游仓库：`https://github.com/ccch1mneyyy/dsh-Go`（issue 用它的模板）。草稿包含：

- 标题：一句话，`[现象] 在什么情况下`
- 版本：快照里的 App 版本、DSH 版本、运行时版本
- 机型 / Android 版本 / ABI
- 复现步骤（用用户刚才的原话整理成编号步骤）
- 期望 vs 实际
- 引擎错误和日志尾部的相关几行（脱敏后）

把草稿放在一个代码块里给用户，让他自己复制粘贴；**不要**替他创建 issue，也不要自动打开浏览器提交。

## 5. 别做的事

- 不改系统设置、不重新安装运行时、不清数据：这些会让问题现场消失，先让用户同意再说。
- 不把 API Key、账号、对话内容写进草稿；快照里没有这些，也不要自己补。
- 不承诺「下个版本会修」——你不是上游维护者。
