# 后端能力查询（API v2 增量）

`backendCapabilities(BackendCapabilitiesRequest.create("addon:capabilities"))` 异步返回不可变 BackendCapabilitiesResult。默认接口实现返回 UNSUPPORTED。NeoForge 实现使用现有 KnotLink 查询 GET_CAPABILITIES，解析 specVersion=1.0 的 func_list。回调不保证游戏线程。

SUCCESS 只证明命令与参数声明，不证明 Minecraft 插件启用、世界绑定、归档可恢复或组合合法。断线、旧协议、缺少清单、解析失败和世界关闭返回明确失败状态，不发起备份或恢复、不抢占现有操作句柄。附属模组仍需处理真实提交的拒绝结果。

只在 NeoForge-1.21 实现；API_VERSION 保持 2，根维护版本不单独提升。Time Machine 开发依赖以此配套构建的源码提交与 SHA256 标识。
