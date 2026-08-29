package com.aicode.feature.editor.domain

import android.content.Context
import io.github.rosemoe.sora.langs.textmate.registry.FileProviderRegistry
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel
import io.github.rosemoe.sora.langs.textmate.registry.provider.AssetsFileResolver
import org.eclipse.tm4e.core.registry.IThemeSource

/**
 * TextMate 语法与主题的一次性初始化。
 *
 * 语法包体积近 1 MB，[GrammarRegistry.loadGrammars] 会全量解析，必须在 IO 线程调用，
 * 不能放 Application.onCreate 或主线程，否则拖慢冷启动 / 卡首帧。
 */
object TextMateSetup {

    const val THEME_DARK = "dark_plus"
    const val THEME_LIGHT = "light_plus"

    @Volatile
    private var initialized = false

    /** 幂等：编辑器页每次打开都会调用，只有首次真正加载。 */
    fun ensureInitialized(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            FileProviderRegistry.getInstance()
                .addFileProvider(AssetsFileResolver(context.applicationContext.assets))
            loadThemes()
            GrammarRegistry.getInstance().loadGrammars(ASSET_LANGUAGES)
            initialized = true
        }
    }

    private fun loadThemes() {
        val registry = ThemeRegistry.getInstance()
        listOf(THEME_DARK to true, THEME_LIGHT to false).forEach { (name, dark) ->
            val path = "textmate/$name.json"
            val source = IThemeSource.fromInputStream(
                FileProviderRegistry.getInstance().tryGetInputStream(path),
                path,
                null
            )
            registry.loadTheme(ThemeModel(source, name).apply { isDark = dark })
        }
    }

    /** 切换当前语法主题，须与 Compose 主题深浅一致，否则 token 配色与背景对不上。 */
    fun applyTheme(dark: Boolean) {
        ThemeRegistry.getInstance().setTheme(if (dark) THEME_DARK else THEME_LIGHT)
    }

    /**
     * 给出 TextMate scopeName，仅覆盖 assets/textmate/languages.json 里实际打包的语法。
     * 返回 null 表示不高亮、按纯文本显示。
     */
    fun scopeNameFor(path: String): String? {
        val fileName = path.substringAfterLast('/')
        return scopeByExtension(fileName.substringAfterLast('.', "").lowercase())
    }

    private fun scopeByExtension(ext: String): String? = when (ext) {
        "kt", "kts" -> "source.kotlin"
        "java" -> "source.java"
        "py", "pyw", "pyi" -> "source.python"
        "js", "mjs", "cjs", "jsx" -> "source.js"
        "ts", "mts", "cts" -> "source.ts"
        "tsx" -> "source.tsx"
        "json" -> "source.json"
        "jsonc", "json5" -> "source.json.comments"
        "yaml", "yml" -> "source.yaml"
        "sh", "bash", "zsh", "ksh", "bashrc", "zshrc", "profile" -> "source.shell"
        "go" -> "source.go"
        "rs" -> "source.rust"
        "c", "h" -> "source.c"
        "cpp", "cc", "cxx", "hpp", "hh", "hxx" -> "source.cpp"
        "cs" -> "source.cs"
        "css" -> "source.css"
        "php" -> "source.php"
        "lua" -> "source.lua"
        "xml" -> "text.xml"
        "html", "htm" -> "text.html.basic"
        "md", "markdown" -> "text.html.markdown"
        else -> null
    }

    private const val ASSET_LANGUAGES = "textmate/languages.json"
}
