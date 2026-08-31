# 使用与商标说明 / Notice

RR Edge Hunter Android（应用名：CF 优选IP）是独立第三方 Cloudflare 入口 IP 测量工具，与 Cloudflare, Inc.、Google 或 Android 平台不存在隶属、合作、赞助、认证或背书关系。相关名称和商标归其权利人所有。

默认测量固定使用 Cloudflare 公共测速主机 `speed.cloudflare.com:443`，候选限定在 Cloudflare 官方公布网段及用户导入后通过同一官方范围校验的地址。测试结果仅代表当前设备、网络出口、运营商和时间，不构成线路质量保证。

优选出的裸 IP 只用于替换节点的 `address/server`。节点端口、UUID、协议、SNI、Host 与 WS Path 应保持原配置；高级 Argo 复核仅用于用户主动选择的额外兼容验证。

Cloudflare DNS 同步是用户主动开启的可选功能，只操作用户明确指定的 Zone 和完整记录名，并采用 DNS-only A/AAAA、只读预览、明确确认与回读验证。使用者负责保护 API Token、确认记录用途和评估 DNS 变更影响。

用户应仅在自己拥有或获授权的网络、节点、域名和 Cloudflare Zone 上使用本项目，并遵守所在地法律、网络提供商政策和服务条款。项目不对不当配置、DNS 变更、第三方 IP 池或网络波动造成的损失承担责任。

---

This is an independent, unofficial project. Users are responsible for authorized testing, protecting API tokens, reviewing optional DNS changes, and complying with applicable laws and provider terms.
