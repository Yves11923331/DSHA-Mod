/**
 * DSHA 内置引导插件「跨应用文件」：用户在 设置 → 设备能力授权 → 跨应用文件访问 里
 * 用系统文档选择器授权过的目录，助手可以用桥的 /app/saf/* 按相对路径读写。
 *
 * 这里只注入一段系统提示（能力不可用时整段不出现），端点、权限与硬约束都在桥侧：
 *   - 路由与处理器：HttpShellService（appSaf*）+ BridgeRoutes；
 *   - 授权、撤销与「允许写入」：SafGrants / SafAccessFragment；
 *   - 只读浏览页：SafBrowseFragment。
 * 不在提示里重复端点清单与错误码：/app/help 里有完整说明。
 */
import bridgePort from './bridge-port.cjs';

export const name = 'dsh-saf-guide';
const sectionName = 'dsha:saf-files';

/** 系统提示里的说明；端口未发布时返回空串，由调用方整段隐藏。 */
export function note(port) {
  return '跨应用文件：用户授权过的其它应用目录可用 http://127.0.0.1:' + port + '/app/saf/* 读写'
    + '（先 trees 拿 id，再 ls/read/write/pull/push；path 相对授权根，不许 ..；写完回读；'
    + 'writable=false 时 READ_ONLY，不改设置、不换通道）。端口只从 /root/.dsh/.bridge_port 读，'
    + '不猜 3090；返回 [NO_CMD] 说明当前安装没有这条通道。';
}

/** 端口每次现读：桥可能后于 Web 启动，双实例时 3090 也可能是别的安装。 */
export function promptNote() {
  try {
    return note(bridgePort());
  } catch {
    return '';
  }
}

export function apply(ctx) {
  ctx.inject(['systemPrompt'], promptCtx => {
    promptCtx.systemPrompt.section({
      name: sectionName,
      order: promptCtx.systemPrompt.getSectionOrder?.('TOOL_READ') ?? 150,
      text: () => promptNote(),
    });
  });
}
