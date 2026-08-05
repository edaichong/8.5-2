package com.autoclicker

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * 统一的本地存储（SharedPreferences）。
 * 场景列表用 Gson 序列化；其余为简单键值。
 */
object ScenarioStore {

    private const val PREFS = "autoclicker_prefs"
    private const val KEY_SCENARIOS = "scenarios"
    private const val KEY_PAUSED = "paused"
    private const val KEY_STOPPED = "stopped"
    private const val KEY_TIMED = "timed_enabled"
    private const val KEY_INTERVAL = "interval_ms"
    private const val KEY_TX = "timed_x"
    private const val KEY_TY = "timed_y"
    private const val KEY_PX = "picked_x"
    private const val KEY_PY = "picked_y"

    private val gson = Gson()
    private val listType = object : TypeToken<MutableList<Scenario>>() {}.type

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(ctx: Context): MutableList<Scenario> {
        val str = prefs(ctx).getString(KEY_SCENARIOS, "[]")
        return try {
            gson.fromJson<MutableList<Scenario>>(str, listType) ?: mutableListOf()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    fun save(ctx: Context, list: List<Scenario>) {
        prefs(ctx).edit().putString(KEY_SCENARIOS, gson.toJson(list)).apply()
    }

    fun add(ctx: Context, s: Scenario) {
        val list = load(ctx)
        list.add(s)
        save(ctx, list)
    }

    fun remove(ctx: Context, id: String) {
        val list = load(ctx).filter { it.id != id }
        save(ctx, list)
    }

    fun update(ctx: Context, s: Scenario) {
        val list = load(ctx).map { if (it.id == s.id) s else it }
        save(ctx, list)
    }

    // 全局暂停
    fun isPaused(ctx: Context) = prefs(ctx).getBoolean(KEY_PAUSED, false)
    fun setPaused(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_PAUSED, v).apply()

    // 软停止：保留无障碍服务，仅停止执行，可随时「开始」恢复
    fun isStopped(ctx: Context) = prefs(ctx).getBoolean(KEY_STOPPED, false)
    fun setStopped(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_STOPPED, v).apply()

    // 定时点击
    fun isTimedEnabled(ctx: Context) = prefs(ctx).getBoolean(KEY_TIMED, false)
    fun setTimedEnabled(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean(KEY_TIMED, v).apply()

    fun getInterval(ctx: Context) = prefs(ctx).getInt(KEY_INTERVAL, 5000)
    fun setInterval(ctx: Context, v: Int) = prefs(ctx).edit().putInt(KEY_INTERVAL, v).apply()

    fun getTimedCoord(ctx: Context) = Pair(prefs(ctx).getInt(KEY_TX, 0), prefs(ctx).getInt(KEY_TY, 0))
    fun setTimedCoord(ctx: Context, x: Int, y: Int) =
        prefs(ctx).edit().putInt(KEY_TX, x).putInt(KEY_TY, y).apply()

    // 悬浮窗拾取的坐标
    fun savePicked(ctx: Context, x: Int, y: Int) =
        prefs(ctx).edit().putInt(KEY_PX, x).putInt(KEY_PY, y).apply()

    fun getPicked(ctx: Context) = Pair(prefs(ctx).getInt(KEY_PX, 0), prefs(ctx).getInt(KEY_PY, 0))
}
