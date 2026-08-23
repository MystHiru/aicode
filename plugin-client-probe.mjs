/**
 * client API 验证插件（测试用）：在加载时与 event hook 里调用各类 client.* API，
 * 验证：app.log 落盘、session.get/list/messages 返回数据、files.* 本地读写、
 * 不支持的 API 返回明确错误且不崩溃。
 */
import { tool } from '@opencode-ai/plugin';

export const ClientProbe = async ({ client, project, directory }) => {
  const results = [];

  async function probe(name, fn) {
    try {
      const r = await fn();
      results.push(`${name}: OK ${JSON.stringify(r).slice(0, 300)}`);
    } catch (e) {
      results.push(`${name}: ERR ${e.message}`);
    }
  }

  // 初始化阶段即调用（此时 socket 可能尚未建立，验证等待机制）
  setTimeout(() => {
    client.app.log({
      body: {
        service: 'client-probe',
        level: 'info',
        message: 'plugin initialized',
        extra: { directory },
      },
    }).catch((e) => console.error('app.log failed:', e.message));
  }, 0);

  return {
    tool: {
      clientProbe: tool({
        description: '验证 plugin client API：返回各项调用结果文本',
        async execute() {
          await probe('app.log', () =>
            client.app.log({
              body: { service: 'client-probe', level: 'debug', message: 'probe from tool', extra: { a: 1 } },
            })
          );
          await probe('health', () => client.global.health());
          await probe('project', () => client.project.get());
          await probe('session.list', () => client.session.list());
          await probe('session.get(缺 id)', () => client.session.get({ path: { id: 'not-exist' } }));
          await probe('files.list', () => client.files.list({ path: { dirPath: '.' } }));
          await probe('files.read', () => client.files.read({ path: { filePath: 'README.md' } }));
          await probe('files.write+read', async () => {
            await client.files.write({ path: { filePath: '.aicode/probe.txt' }, body: { data: 'hello' } });
            return client.files.read({ path: { filePath: '.aicode/probe.txt' } });
          });
          await probe('config.get', () => client.config.get());
          await probe('session.prompt(不支持)', () => client.session.prompt());
          await probe('auth.set(不支持)', () => client.auth.set());
          return results.join('\n');
        },
      }),
    },
    event: async ({ event }) => {
      if (event?.type === 'session.created') {
        await probe('session.get', () => client.session.get({ path: { id: event.properties.sessionID } }))
          .then(() => client.app.log({ body: { service: 'client-probe', level: 'info', message: results.join('\n') } }))
          .catch(() => {});
      }
    },
  };
};