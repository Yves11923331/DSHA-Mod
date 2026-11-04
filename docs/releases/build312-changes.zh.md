# DSHA dsha.v3 / build312

- 新增内置引导插件 `dsh-saf-guide`：把「跨应用文件访问」写进系统提示（端口只从 `/root/.dsh/.bridge_port` 读，不猜 3090），助手不用手装技能也知道这条通道；能力不可用时整段不出现。
- 跨应用文件访问进入共存版：设备能力授权新增一行 + 管理页（选择文件夹 / 允许写入 / 移除）+ 只读浏览页 + 桥的 9 个端点 `/app/saf/*`（trees/ls/stat/read/write/mkdir/rm/pull/push）。
- 基线仍为上游 build310（`f0f11905`）；横屏 Dock、屏幕常亮、冬装图标三样自用改动全部保留。
- 验证范围：本地离线编译与 `preBuild` 七道门禁通过；真机授权、读写与撤销待装机核对。版本码 312（基于 build310），版本名 dsha.v3，包名 dsga.mod。
