package io.github.ztfang.eye.domain.model

/** 离线词典条目 */
data class DictEntry(
    /** 词条原形（词典里记录的形式） */
    val word: String,
    /** 音标，形如 /ˈæpl/ */
    val phonetic: String = "",
    /** 词性 + 中文释义，多行 */
    val translation: String = "",
    /** 是否来自词形还原（如 studies → study），用于提示用户 */
    val lematized: Boolean = false,
)
