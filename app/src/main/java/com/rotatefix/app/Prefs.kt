package com.rotatefix.app

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private fun sp(c: Context): SharedPreferences =
        c.getSharedPreferences("rotatefix", Context.MODE_PRIVATE)

    fun enabled(c: Context) = sp(c).getBoolean("enabled", false)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun position(c: Context): Pair<Int, Int>? {
        val p = sp(c)
        if (!p.contains("x")) return null
        return p.getInt("x", 0) to p.getInt("y", 0)
    }

    fun savePosition(c: Context, x: Int, y: Int) =
        sp(c).edit().putInt("x", x).putInt("y", y).apply()
}
