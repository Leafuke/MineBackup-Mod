# KnotLink 列表参数发送契约

NeoForge-1.21 的当前世界备份/回档发送层区分普通字符串和列表。公共 API v2、请求 record 构造器和 `withParameters(Map<String,String>)` 签名不变。

| 操作 | 列表参数 |
| --- | --- |
| BACKUP | `backup_blacklist`、`backup_whitelist`、`scope_dimensions` |
| RESTORE | `restore_whitelist`、`restore_preserve_paths` |

调用方传入未编码文本。Map 中这些参数使用逗号分隔，空项及纯空白项忽略，其余项不额外裁剪；路径校验仍由调用方和后端执行。每项单独进行 RFC 3986 编码，再使用原始逗号连接。例如 `ftbquests/,ftbteams/` 发为 `ftbquests%2F,ftbteams%2F`，不能再对列表整体编码。

Map 列表格式不能表达单项自身含逗号，不支持转义或预编码输入。内部 `listField(key, List<String>)` 能表达此类单项：`["a,b", "中文"]` 编码为 `a%2Cb,%E4%B8%AD%E6%96%87`。普通 `field` 和 `KnotLinkCodec.serialize(Map)` 保持字符串整值编码。

`scope_areas` 是区域坐标文本，属于普通字符串；备注和文件名也不能按逗号自动拆分。未知参数保持字符串编码，仍受既有业务校验约束；类型分派不授予执行权限。重复键由协议解析器拒绝，不能用重复参数表达列表。

本次仅修改 NeoForge-1.21，不改变根维护版本。只运行 `assemble` 编译打包，未执行自动测试或游戏内验收。人工验证需同时覆盖 FTB 两目录、普通字符串中的逗号、区域坐标换行、中文、字面 `%2C`、列表空项和非法参数。
