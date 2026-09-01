import { defineConfig } from 'vitepress'

export default defineConfig({
  srcDir: './docs',
  lang: 'zh-Hans',
  title: 'AiCode',
  description: 'Android 端 AI 编程工具 · 内置 Linux 终端 · AI Agent · MCP 协议 · Git 集成',
  cleanUrls: true,
  lastUpdated: true,
  sitemap: {
    hostname: 'https://aicode.murk.top'
  },
  head: [
    ['meta', { name: 'theme-color', content: '#3c8772' }]
  ],
  themeConfig: {
    nav: [
      { text: '使用手册', link: '/guide/quick-start', activeMatch: '/guide/' },
      { text: '进阶教程', link: '/advanced/build-android-app', activeMatch: '/advanced/' },
      { text: '下载', link: 'https://github.com/jieapi/aicode/releases/latest' }
    ],
    sidebar: {
      '/guide/': [
        {
          text: '入门',
          items: [
            { text: '快速上手', link: '/guide/quick-start' },
            { text: '功能总览', link: '/guide/overview' }
          ]
        },
        {
          text: '对话与会话',
          items: [
            { text: '聊天界面导览', link: '/guide/chat' },
            { text: '三种模式', link: '/guide/modes' },
            { text: '检查点与撤销', link: '/guide/checkpoint' }
          ]
        },
        {
          text: '模型与用量',
          items: [
            { text: 'AI 提供商与模型', link: '/guide/providers' },
            { text: '默认模型', link: '/guide/default-models' },
            { text: 'Token 统计与费用', link: '/guide/token-stats' }
          ]
        },
        {
          text: '执行环境',
          items: [
            { text: '容器与镜像', link: '/guide/container' },
            { text: '终端', link: '/guide/terminal' },
            { text: '远程 SSH 模式', link: '/guide/remote-ssh' },
            { text: '工作区同步', link: '/guide/sync' },
            { text: '网络代理', link: '/guide/proxy' }
          ]
        },
        {
          text: '扩展能力',
          items: [
            { text: 'MCP 服务器', link: '/guide/mcp' },
            { text: '技能', link: '/guide/skills' },
            { text: '子代理', link: '/guide/subagent' },
            { text: '自定义提示词', link: '/guide/custom-prompts' }
          ]
        },
        {
          text: '文件与版本',
          items: [
            { text: '文件浏览与代码编辑', link: '/guide/files' },
            { text: 'Git 版本管理', link: '/guide/git' }
          ]
        },
        {
          text: '设置与维护',
          items: [
            { text: '工具授权', link: '/guide/permissions' },
            { text: '软件权限', link: '/guide/app-permissions' },
            { text: '外观与语言', link: '/guide/appearance' },
            { text: '日志与故障排查', link: '/guide/logs' },
            { text: '备份与还原', link: '/guide/backup' },
            { text: '关于与更新', link: '/guide/about' }
          ]
        }
      ],
      '/advanced/': [
        {
          text: '环境搭建',
          items: [
            { text: '在容器中编译 Android 应用', link: '/advanced/build-android-app' },
            { text: '自定义容器镜像', link: '/advanced/custom-image' }
          ]
        },
        {
          text: '扩展开发',
          items: [
            { text: '自定义面板', link: '/advanced/dashboard-cards' }
          ]
        },
        {
          text: '排查',
          items: [
            { text: '常见问题', link: '/advanced/troubleshooting' }
          ]
        }
      ]
    },
    socialLinks: [
      { icon: 'github', link: 'https://github.com/jieapi/aicode' }
    ],
    search: {
      provider: 'local'
    },
    outline: { label: '页面导航', level: [2, 3] },
    docFooter: { prev: '上一页', next: '下一页' },
    lastUpdatedText: '最后更新于',
    darkModeSwitchLabel: '主题',
    lightModeSwitchTitle: '切换到浅色模式',
    darkModeSwitchTitle: '切换到深色模式',
    sidebarMenuLabel: '菜单',
    returnToTopLabel: '回到顶部',
    editLink: {
      pattern: 'https://github.com/jieapi/aicode/edit/main/docs-site/docs/:path',
      text: '在 GitHub 上编辑此页'
    },
    footer: {
      message: '基于 GPL-3.0 协议开源',
      copyright: 'Copyright © 2025-至今 AiCode'
    }
  }
})
