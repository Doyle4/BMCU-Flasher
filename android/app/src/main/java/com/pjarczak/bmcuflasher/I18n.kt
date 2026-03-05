package com.pjarczak.bmcuflasher

import android.content.Context
import org.json.JSONObject

class I18n(ctx: Context) {
  private val map: JSONObject

  init {
    val s = ctx.assets.open("en.json").bufferedReader(Charsets.UTF_8).readText()
    map = JSONObject(s)
  }

  fun t(k: String): String {
    val v = map.optString(k, "")
    return if (v.isNotEmpty()) v else k
  }
}
