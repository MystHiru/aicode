import { tool } from '@opencode-ai/plugin';

// 覆盖 AiCode 的「返回型 hook」provider.models：动态向模型列表注入元数据。
// 放测试版容器的 /root/.aicode/plugins/ 下，重载插件后，
// 在设置页给某 provider 输入模型 ID "test-model-x" 时，应能看到插件注入的元数据。
export const ProviderModelsTest = async ({ directory, $ }) => {
  console.log('ProviderModelsTest initialized, directory=' + directory);
  return {
    "provider.models": async (input) => {
      const provider = input?.provider || 'unknown';
      console.log('provider.models hook called for provider=' + provider);
      return {
        "test-model-x": {
          contextTokens: 128000,
          supportsVision: true,
          supportsReasoning: true
        }
      };
    }
  };
};
