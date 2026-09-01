# 使用与商标说明 / Notice

RR Edge Hunter Android（应用名：CF 优选IP）是独立第三方 Cloudflare 入口 IP 测量工具，与 Cloudflare, Inc.、Google 或 Android 平台不存在隶属、合作、赞助、认证或背书关系。相关名称和商标归其权利人所有。

默认测量使用公开维护接口动态提供的候选网段、测速地址和数据中心表，接口不可用时采用本机缓存或 Cloudflare 官方备用网段。用户导入的安全公网 IP 也必须通过同样的实时连通与下载门禁。测试结果仅代表当前设备、网络出口、运营商和时间，不构成线路质量保证。

默认维护接口与可观察测速流程参考 [badafans/better-cloudflare-ip](https://github.com/badafans/better-cloudflare-ip)。本项目为独立实现；该上游仓库目前没有声明开源许可证，因此本项目不复制、不修改也不分发其源代码。维护接口属于第三方服务，其可用性、内容与使用条件可能变化。

优选出的裸 IP 只用于替换节点的 `address/server`。节点端口、UUID、协议、SNI、Host 与 WS Path 应保持原配置。应用要求用户粘贴自己已有的 VMess/VLESS WS+TLS 节点，并以固定版本 XTLS/libXray 运行完整节点出站作为最终门禁。含凭据的配置仅在当前页面内存中使用；私有缓存中的单次临时配置立即删除，不写入日志、历史或导出。

Cloudflare DNS 同步是用户主动开启的可选功能，只操作用户明确指定的 Zone 和完整记录名，并采用 DNS-only A/AAAA、只读预览、明确确认与回读验证。使用者负责保护 API Token、确认记录用途和评估 DNS 变更影响。

用户应仅在自己拥有或获授权的网络、节点、域名和 Cloudflare Zone 上使用本项目，并遵守所在地法律、网络提供商政策和服务条款。项目不对不当配置、DNS 变更、第三方 IP 池或网络波动造成的损失承担责任。

---

This is an independent, unofficial project. Users are responsible for authorized testing, protecting API tokens, reviewing optional DNS changes, and complying with applicable laws and provider terms.
