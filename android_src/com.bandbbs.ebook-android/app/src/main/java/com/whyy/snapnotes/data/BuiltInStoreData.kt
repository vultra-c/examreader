package com.whyy.snapnotes.data

import android.content.Context
import android.util.Log
import com.whyy.snapnotes.R
import org.json.JSONObject

/**
 * 闪念小抄 - 内置知识点商店数据
 * 来源：原闪念小抄手环端内置知识点（10科目159条）
 * 开发者：SnapNotes | 价格：免费
 */

data class StoreSubject(
    val name: String,
    val entries: List<StoreEntry>
)

data class StoreEntry(
    val id: Int,
    val title: String,
    val desc: String,
    val raw: String = "",
    val points: List<String> = emptyList(),
    val formulas: List<String> = emptyList()
)

/**
 * 知识点包（商店展示单元）。
 * 一个包可包含多个科目，支持一键全量导入或选择部分科目导入。
 */
data class StorePack(
    val id: String,
    val name: String,
    val author: String,
    val description: String,
    val subjects: List<StoreSubject>,
    val isFree: Boolean = true
) {
    val totalEntries: Int get() = subjects.sumOf { it.entries.size }
}

/**
 * 全局 Context 持有者，供顶层 val 延迟加载内置知识点 JSON。
 * 在 [com.whyy.snapnotes.App.onCreate] 中调用 [initBuiltinData] 初始化。
 */
private var appContext: Context? = null

fun initBuiltinData(context: Context) {
    appContext = context.applicationContext
}

/**
 * 从 res/raw/builtin_knowledge.json 加载内置知识点。
 * JSON 结构：{ "科目名": [ { id, title, desc, raw?, points?, formulas? }, ... ] }
 * 与手环端 knowledgeData.js 完全一致，包含 raw（原文）和 points（速记要点）。
 */
private fun loadBuiltinStoreItems(): List<StoreSubject> {
    val ctx = appContext ?: run {
        Log.e("BuiltInStoreData", "appContext not initialized, builtin data empty")
        return emptyList()
    }
    return try {
        val text = ctx.resources.openRawResource(R.raw.builtin_knowledge)
            .bufferedReader().use { it.readText() }
        val root = JSONObject(text)
        val subjects = mutableListOf<StoreSubject>()
        val keys = root.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val arr = root.optJSONArray(name) ?: continue
            val entries = mutableListOf<StoreEntry>()
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val id = item.optInt("id", i + 1)
                val title = item.optString("title", "")
                if (title.isBlank()) continue
                val desc = item.optString("desc", "")
                val raw = item.optString("raw", "")
                val points = item.optJSONArray("points")?.let { pa ->
                    (0 until pa.length()).mapNotNull { pa.optString(it).takeIf { it.isNotBlank() } }
                } ?: emptyList()
                val formulas = item.optJSONArray("formulas")?.let { fa ->
                    (0 until fa.length()).mapNotNull { fa.optString(it).takeIf { it.isNotBlank() } }
                } ?: emptyList()
                entries.add(StoreEntry(id, title, desc, raw, points, formulas))
            }
            if (entries.isNotEmpty()) subjects.add(StoreSubject(name, entries))
        }
        Log.i("BuiltInStoreData", "loaded ${subjects.size} subjects, ${subjects.sumOf { it.entries.size }} entries")
        subjects
    } catch (e: Exception) {
        Log.e("BuiltInStoreData", "load builtin knowledge fail", e)
        emptyList()
    }
}

/**
 * 内置知识点包：10科目共159条，全部免费。
 * 延迟加载：首次访问时从 res/raw/builtin_knowledge.json 读取。
 */
val BUILTIN_STORE_ITEMS: List<StoreSubject> by lazy { loadBuiltinStoreItems() }

/**
 * 商店全部知识点包。官方包与未来用户上传包统一在此列出，不做特殊对待。
 */
val STORE_PACKS: List<StorePack> by lazy {
    listOf(
        StorePack(
            id = "official_high_school",
            name = "高中知识点全集",
            author = "SnapNotes",
            description = "涵盖高中十大学科的核心知识点合集，包含语文、数学、英语、物理、化学、生物、历史、地理、政治、信息技术。",
            subjects = BUILTIN_STORE_ITEMS
        )
    )
}
